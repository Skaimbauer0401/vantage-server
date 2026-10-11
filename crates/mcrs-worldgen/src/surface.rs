//! The surface step: `MaterialSystem.buildSurface` with its material rules (26.3).
//!
//! Runs on the chunk Rust just filled, with the same density context, so the
//! density samples it takes (preliminary surface level, ore veins) see exactly the
//! cache state vanilla's would. Everything that can touch those caches keeps
//! vanilla's laziness and order; pure parts are evaluated directly.

#![allow(clippy::neg_cmp_op_on_partial_ord)]
// Negated comparisons keep Java's NaN behavior.

use serde_json::Value;

use crate::density::{Context, LoadError, NodeId};
use crate::noise::{floor, DensityVolume, Noise};
use crate::random::{AnyPositional, LegacyPositionalFactory, RandomSource, XoroshiroPositionalFactory};

/// Per-state flag bits exported by `SurfaceExport.stateFlags`.
pub const STATE_AIR: u16 = 1;
pub const STATE_HAS_FLUID: u16 = 2;
pub const STATE_BLOCKS_MOTION: u16 = 4;
pub const STATE_WORLD_SURFACE: u16 = 8;
pub const STATE_WATER: u16 = 16;
pub const STATE_LAVA: u16 = 32;
/// Set in Rust: the state belongs to the dimension's default block (e.g. stone).
pub const STATE_DEFAULT_BLOCK: u16 = 64;
pub const STATE_COUNT_NON_EMPTY: u16 = 128;
pub const STATE_COUNT_FLUID: u16 = 256;
pub const STATE_COUNT_TICKING_BLOCK: u16 = 512;
pub const STATE_COUNT_TICKING_FLUID: u16 = 1024;
/// `BlockTags.UNCARVABLE`
pub const STATE_UNCARVABLE: u16 = 2048;
/// `Blocks.GRASS_BLOCK` or `Blocks.MYCELIUM`
pub const STATE_GRASS_OR_MYCELIUM: u16 = 4096;
/// `Blocks.DIRT`
pub const STATE_DIRT: u16 = 8192;

/// `DimensionType.WAY_BELOW_MIN_Y`
const WAY_BELOW_MIN_Y: i32 = -2032 << 4;

/// One chunk section: vanilla's palette (in insertion order) and indices into it.
#[derive(Debug, Clone)]
pub struct Section {
    pub palette: Vec<u32>,
    pub indices: Vec<u16>,
    pub non_air: i32,
}

/// The blocks and worldgen heightmaps of a chunk being generated.
#[derive(Clone)]
pub struct ChunkBlocks {
    pub min_y: i32,
    pub sections: Vec<Option<Section>>,
    pub air: u32,
    /// `firstAvailable` per column (x + z * 16) for WORLD_SURFACE_WG and OCEAN_FLOOR_WG.
    pub world_surface: [i32; 256],
    pub ocean_floor: [i32; 256],
    /// `markPosForPostProcessing` calls in order.
    pub post_processing: Vec<(i32, i32, i32)>,
}

impl ChunkBlocks {
    pub fn new(min_y: i32, height: i32, air: u32) -> ChunkBlocks {
        ChunkBlocks {
            min_y,
            sections: (0..height / 16).map(|_| None).collect(),
            air,
            world_surface: [min_y; 256],
            ocean_floor: [min_y; 256],
            post_processing: Vec::new(),
        }
    }

    pub fn max_y(&self) -> i32 {
        self.min_y + self.sections.len() as i32 * 16 - 1
    }

    /// The state at a chunk-local position; `None` outside the build height (`VOID_AIR`).
    #[inline]
    pub fn get(&self, x: i32, y: i32, z: i32) -> Option<u32> {
        if y < self.min_y || y > self.max_y() {
            return None;
        }
        let s = ((y - self.min_y) >> 4) as usize;
        Some(match &self.sections[s] {
            Some(section) => section.palette[section.indices[(((y & 15) << 8) | (z << 4) | x) as usize] as usize],
            None => self.air,
        })
    }

    /// `LevelChunkSection.setBlockState`: grows the palette in insertion order.
    pub fn set_raw(&mut self, x: i32, y: i32, z: i32, state: u32, flags: &[u16]) -> u32 {
        let s = ((y - self.min_y) >> 4) as usize;
        let air = self.air;
        let section = self.sections[s].get_or_insert_with(|| Section { palette: vec![air], indices: vec![0; 4096], non_air: 0 });
        let index = match section.palette.iter().position(|p| *p == state) {
            Some(i) => i,
            None => {
                section.palette.push(state);
                section.palette.len() - 1
            }
        };
        let slot = &mut section.indices[(((y & 15) << 8) | (z << 4) | x) as usize];
        let old = section.palette[*slot as usize];
        *slot = index as u16;
        let is_air = |s: u32| flags[s as usize] & STATE_AIR != 0;
        if is_air(old) && !is_air(state) {
            section.non_air += 1;
        } else if !is_air(old) && is_air(state) {
            section.non_air -= 1;
        }
        old
    }

    fn section_empty(&self, y: i32) -> bool {
        match &self.sections[((y - self.min_y) >> 4) as usize] {
            Some(s) => s.non_air == 0,
            None => true,
        }
    }

    /// `ProtoChunk.setBlockState` before INITIALIZE_LIGHT: sets the block and updates
    /// the worldgen heightmaps.
    pub fn set_block_state(&mut self, x: i32, y: i32, z: i32, state: u32, flags: &[u16], plain_air: u32) {
        if y < self.min_y || y > self.max_y() {
            return;
        }
        if self.section_empty(y) && state == plain_air {
            return;
        }
        self.set_raw(x, y, z, state, flags);
        self.heightmap_update(true, x, y, z, state, flags);
        self.heightmap_update(false, x, y, z, state, flags);
    }

    /// `Heightmap.update` for WORLD_SURFACE_WG (`world_surface = true`) or OCEAN_FLOOR_WG.
    fn heightmap_update(&mut self, world_surface: bool, x: i32, y: i32, z: i32, state: u32, flags: &[u16]) {
        let predicate = if world_surface { STATE_WORLD_SURFACE } else { STATE_BLOCKS_MOTION };
        let column = (x + z * 16) as usize;
        let first = if world_surface { self.world_surface[column] } else { self.ocean_floor[column] };
        if y <= first - 2 {
            return;
        }
        let new_height = if flags[state as usize] & predicate != 0 {
            if y >= first {
                Some(y + 1)
            } else {
                None
            }
        } else if first - 1 == y {
            let mut height = self.min_y;
            let mut yy = y - 1;
            while yy >= self.min_y {
                let s = self.get(x, yy, z).unwrap_or(self.air);
                if flags[s as usize] & predicate != 0 {
                    height = yy + 1;
                    break;
                }
                yy -= 1;
            }
            Some(height)
        } else {
            None
        };
        if let Some(h) = new_height {
            if world_surface {
                self.world_surface[column] = h;
            } else {
                self.ocean_floor[column] = h;
            }
        }
    }

    pub fn highest_filled_section(&self) -> i32 {
        for (i, s) in self.sections.iter().enumerate().rev() {
            if let Some(s) = s {
                if s.non_air != 0 {
                    return i as i32;
                }
            }
        }
        -1
    }
}

#[derive(Debug, Clone, Copy)]
enum Anchor {
    Absolute(i32),
    AboveBottom(i32),
    BelowTop(i32),
    RelativeToSeaLevel(i32),
}

/// `WorldGenerationContext` of the chunk being generated.
#[derive(Debug, Clone, Copy)]
pub struct GenContext {
    pub min_gen_y: i32,
    pub gen_depth: i32,
    pub sea_level: i32,
}

impl Anchor {
    fn resolve(self, c: &GenContext) -> i32 {
        match self {
            Anchor::Absolute(y) => y,
            Anchor::AboveBottom(o) => c.min_gen_y + o,
            Anchor::BelowTop(o) => c.gen_depth - 1 + c.min_gen_y - o,
            Anchor::RelativeToSeaLevel(o) => c.sea_level + o,
        }
    }
}

#[derive(Debug, Clone)]
enum Cond {
    /// Bitset over biome ids.
    Biome(Vec<u64>),
    NoiseThreshold { noise: usize, min: f64, max: f64, is3d: bool },
    VerticalGradient { random: AnyPositional, true_at: Anchor, false_at: Anchor },
    YAbove { anchor: Anchor, mult: i32, add_stone_depth: bool },
    Water { offset: i32, mult: i32, add_stone_depth: bool },
    Temperature,
    Steep,
    Not(Box<Cond>),
    Hole,
    AbovePreliminarySurface,
    StoneDepth { offset: i32, add_surface_depth: bool, secondary_range: i32, ceiling: bool },
}

#[derive(Debug, Clone)]
enum Rule {
    Sequence(Vec<Rule>),
    Condition(Cond, Box<Rule>),
    Block(u32),
    Bandlands,
    OreVein { ore: u32, raw_ore: u32, filler: u32, raw_ore_chance: f32, density: NodeId, richness: NodeId, filler_gap: NodeId, random: AnyPositional },
}

#[derive(Debug, Clone, Copy, Default)]
struct BiomeInfo {
    temperature: f32,
    frozen: bool,
    eroded_badlands: bool,
    frozen_ocean: bool,
}

/// Everything the surface step needs for one dimension.
pub struct Surface {
    rule: Rule,
    preliminary_surface: NodeId,
    surface_noise: usize,
    surface_secondary_noise: usize,
    clay_bands_offset_noise: usize,
    badlands_pillar_noise: usize,
    badlands_pillar_roof_noise: usize,
    badlands_surface_noise: usize,
    iceberg_pillar_noise: usize,
    iceberg_pillar_roof_noise: usize,
    iceberg_surface_noise: usize,
    temperature_noise: usize,
    frozen_temperature_noise: usize,
    biome_info_noise: usize,
    noise_random: AnyPositional,
    clay_bands: Vec<u32>,
    snow_block: u32,
    packed_ice: u32,
    zoom_seed: i64,
    biomes: Vec<BiomeInfo>,
}

fn get<'a>(v: &'a Value, name: &str) -> Result<&'a Value, LoadError> {
    v.get(name).ok_or_else(|| LoadError(format!("surface: missing {name}")))
}

fn int(v: &Value, name: &str) -> Result<i64, LoadError> {
    get(v, name)?.as_i64().ok_or_else(|| LoadError(format!("surface: {name} is not an integer")))
}

fn boolean(v: &Value, name: &str) -> Result<bool, LoadError> {
    get(v, name)?.as_bool().ok_or_else(|| LoadError(format!("surface: {name} is not a boolean")))
}

fn text<'a>(v: &'a Value, name: &str) -> Result<&'a str, LoadError> {
    get(v, name)?.as_str().ok_or_else(|| LoadError(format!("surface: {name} is not a string")))
}

/// A positional random factory as written by `WorldgenExport.positional`.
pub fn parse_positional(v: &Value) -> Result<AnyPositional, LoadError> {
    Ok(match text(v, "type")? {
        "xoroshiro" => AnyPositional::Xoroshiro(XoroshiroPositionalFactory { lo: int(v, "lo")?, hi: int(v, "hi")? }),
        "legacy" => AnyPositional::Legacy(LegacyPositionalFactory { seed: int(v, "seed")? }),
        other => return Err(LoadError(format!("positional random {other}"))),
    })
}

fn parse_anchor(v: &Value) -> Result<Anchor, LoadError> {
    let value = int(v, "v")? as i32;
    Ok(match text(v, "kind")? {
        "absolute" => Anchor::Absolute(value),
        "above_bottom" => Anchor::AboveBottom(value),
        "below_top" => Anchor::BelowTop(value),
        "relative_to_sea_level" => Anchor::RelativeToSeaLevel(value),
        other => return Err(LoadError(format!("anchor {other}"))),
    })
}

fn parse_cond(v: &Value) -> Result<Cond, LoadError> {
    Ok(match text(v, "t")? {
        "biome" => {
            let mut bits = Vec::new();
            for b in get(v, "biomes")?.as_array().ok_or_else(|| LoadError("biomes".into()))? {
                let id = b.as_i64().unwrap_or(-1);
                if id >= 0 {
                    let (word, bit) = (id as usize / 64, id as usize % 64);
                    if bits.len() <= word {
                        bits.resize(word + 1, 0u64);
                    }
                    bits[word] |= 1 << bit;
                }
            }
            Cond::Biome(bits)
        }
        "noise_threshold" => Cond::NoiseThreshold {
            noise: int(v, "noise")? as usize,
            min: f64::from_bits(int(v, "min")? as u64),
            max: f64::from_bits(int(v, "max")? as u64),
            is3d: boolean(v, "is3d")?,
        },
        "vertical_gradient" => Cond::VerticalGradient {
            random: parse_positional(get(v, "random")?)?,
            true_at: parse_anchor(get(v, "true_at")?)?,
            false_at: parse_anchor(get(v, "false_at")?)?,
        },
        "y_above" => Cond::YAbove {
            anchor: parse_anchor(get(v, "anchor")?)?,
            mult: int(v, "mult")? as i32,
            add_stone_depth: boolean(v, "add_stone_depth")?,
        },
        "water" => Cond::Water {
            offset: int(v, "offset")? as i32,
            mult: int(v, "mult")? as i32,
            add_stone_depth: boolean(v, "add_stone_depth")?,
        },
        "temperature" => Cond::Temperature,
        "steep" => Cond::Steep,
        "not" => Cond::Not(Box::new(parse_cond(get(v, "cond")?)?)),
        "hole" => Cond::Hole,
        "above_preliminary_surface" => Cond::AbovePreliminarySurface,
        "stone_depth" => Cond::StoneDepth {
            offset: int(v, "offset")? as i32,
            add_surface_depth: boolean(v, "add_surface_depth")?,
            secondary_range: int(v, "secondary_range")? as i32,
            ceiling: boolean(v, "ceiling")?,
        },
        other => return Err(LoadError(format!("material condition {other}"))),
    })
}

fn parse_rule(v: &Value) -> Result<Rule, LoadError> {
    Ok(match text(v, "t")? {
        "sequence" => Rule::Sequence(
            get(v, "rules")?.as_array().ok_or_else(|| LoadError("rules".into()))?.iter().map(parse_rule).collect::<Result<_, _>>()?,
        ),
        "condition" => Rule::Condition(parse_cond(get(v, "if")?)?, Box::new(parse_rule(get(v, "then")?)?)),
        "block" => Rule::Block(int(v, "state")? as u32),
        "bandlands" => Rule::Bandlands,
        "ore_vein" => Rule::OreVein {
            ore: int(v, "ore")? as u32,
            raw_ore: int(v, "raw_ore")? as u32,
            filler: int(v, "filler")? as u32,
            raw_ore_chance: f32::from_bits(int(v, "raw_ore_chance")? as u32),
            density: int(v, "density")? as usize,
            richness: int(v, "richness")? as usize,
            filler_gap: int(v, "filler_gap")? as usize,
            random: parse_positional(get(v, "random")?)?,
        },
        other => return Err(LoadError(format!("material rule {other}"))),
    })
}

impl Surface {
    pub fn from_json(v: &Value) -> Result<Surface, LoadError> {
        let noises = get(v, "noises")?;
        let noise = |name: &str| -> Result<usize, LoadError> { Ok(int(noises, name)? as usize) };
        let mut biomes = Vec::new();
        for b in get(v, "biomes")?.as_array().ok_or_else(|| LoadError("biomes".into()))? {
            let id = int(b, "id")? as usize;
            if biomes.len() <= id {
                biomes.resize(id + 1, BiomeInfo::default());
            }
            biomes[id] = BiomeInfo {
                temperature: f32::from_bits(int(b, "temperature")? as u32),
                frozen: boolean(b, "frozen")?,
                eroded_badlands: boolean(b, "eroded_badlands")?,
                frozen_ocean: boolean(b, "frozen_ocean")?,
            };
        }
        Ok(Surface {
            rule: parse_rule(get(v, "rule")?)?,
            preliminary_surface: int(v, "preliminary_surface")? as usize,
            surface_noise: noise("surfaceNoise")?,
            surface_secondary_noise: noise("surfaceSecondaryNoise")?,
            clay_bands_offset_noise: noise("clayBandsOffsetNoise")?,
            badlands_pillar_noise: noise("badlandsPillarNoise")?,
            badlands_pillar_roof_noise: noise("badlandsPillarRoofNoise")?,
            badlands_surface_noise: noise("badlandsSurfaceNoise")?,
            iceberg_pillar_noise: noise("icebergPillarNoise")?,
            iceberg_pillar_roof_noise: noise("icebergPillarRoofNoise")?,
            iceberg_surface_noise: noise("icebergSurfaceNoise")?,
            temperature_noise: noise("temperature")?,
            frozen_temperature_noise: noise("frozen_temperature")?,
            biome_info_noise: noise("biome_info")?,
            noise_random: parse_positional(get(v, "noise_random")?)?,
            clay_bands: get(v, "clay_bands")?.as_array().ok_or_else(|| LoadError("clay_bands".into()))?.iter().map(|b| b.as_i64().unwrap_or(0) as u32).collect(),
            snow_block: int(v, "snow_block")? as u32,
            packed_ice: int(v, "packed_ice")? as u32,
            zoom_seed: int(v, "zoom_seed")?,
            biomes,
        })
    }
}

/// Noise biomes of the 3x3 chunks around the one being generated, at quart
/// resolution: `[chunk (dz+1)*3+(dx+1)][quartY][quartZ][quartX]`.
pub struct BiomeArea {
    pub chunk_x: i32,
    pub chunk_z: i32,
    /// Quart Y of the lowest stored layer (chunk min Y / 4).
    pub min_quart_y: i32,
    pub quart_height: i32,
    pub ids: Vec<u32>,
}

impl BiomeArea {
    /// `ChunkAccess.getNoiseBiome` (y clamped) of the chunk containing the quart.
    fn noise_biome(&self, qx: i32, qy: i32, qz: i32) -> u32 {
        let cx = (qx >> 2) - self.chunk_x + 1;
        let cz = (qz >> 2) - self.chunk_z + 1;
        assert!((0..3).contains(&cx) && (0..3).contains(&cz), "biome lookup outside the 3x3 region");
        let y = (qy.clamp(self.min_quart_y, self.min_quart_y + self.quart_height - 1) - self.min_quart_y) as usize;
        let per_chunk = self.quart_height as usize * 16;
        self.ids[(cz * 3 + cx) as usize * per_chunk + y * 16 + ((qz & 3) * 4 + (qx & 3)) as usize]
    }
}

/// `LinearCongruentialGenerator.next`
fn lcg(rval: i64, c: i64) -> i64 {
    rval.wrapping_mul(rval.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407)).wrapping_add(c)
}

fn fiddle(rval: i64) -> f64 {
    let uniform = (rval >> 24).rem_euclid(1024) as f64 / 1024.0;
    (uniform - 0.5) * 0.9
}

/// The three fiddle offsets of a zoom corner (pure function of the corner).
fn corner_fiddles(seed: i64, cx: i32, cy: i32, cz: i32) -> [f64; 3] {
    let mut r = seed;
    r = lcg(r, cx as i64);
    r = lcg(r, cy as i64);
    r = lcg(r, cz as i64);
    r = lcg(r, cx as i64);
    r = lcg(r, cy as i64);
    r = lcg(r, cz as i64);
    let fiddle_x = fiddle(r);
    r = lcg(r, seed);
    let fiddle_y = fiddle(r);
    r = lcg(r, seed);
    let fiddle_z = fiddle(r);
    [fiddle_x, fiddle_y, fiddle_z]
}

/// `BiomeManager.getBiome` for the blocks of one chunk. Per parent quart cell it caches the
/// corner fiddles, the 8 candidate noise biomes and whether they all agree; the 16 columns
/// over a cell share them. The distance arithmetic per lookup is unchanged.
struct ColumnZoom {
    x: i32,
    z: i32,
    px0: i32,
    pz0: i32,
    base_y: i32,
    ny: usize,
    /// Corner fiddles per cell (px, corner y, pz): the 4 xz corners.
    cache: Vec<Option<[[f64; 3]; 4]>>,
    /// Per cell: Some(id) if all 8 corner noise biomes are `id`, None if mixed.
    uniform: Vec<Option<Option<u32>>>,
    /// Per cell: the 8 corner noise biomes the zoom picks from, as a bit set over biome ids
    /// (None inside: an id too large for the set).
    candidates: Vec<Option<Option<[u64; 4]>>>,
}

impl ColumnZoom {
    fn new() -> ColumnZoom {
        ColumnZoom { x: i32::MIN, z: i32::MIN, px0: 0, pz0: 0, base_y: 0, ny: 0, cache: Vec::new(), uniform: Vec::new(), candidates: Vec::new() }
    }

    /// Sets up the cells for the chunk with these block bounds.
    fn init_chunk(&mut self, min_bx: i32, min_bz: i32, min_y: i32, max_y: i32) {
        self.px0 = (min_bx - 2) >> 2;
        self.pz0 = (min_bz - 2) >> 2;
        self.base_y = ((min_y - 2) >> 2) - 1;
        self.ny = (((max_y - 2) >> 2) + 2 - self.base_y + 1).max(1) as usize;
        let len = 25 * self.ny;
        self.cache.clear();
        self.cache.resize(len, None);
        self.uniform.clear();
        self.uniform.resize(len, None);
        self.candidates.clear();
        self.candidates.resize(len, None);
    }

    fn slot(&self, px: i32, py: i32, pz: i32) -> Option<usize> {
        let (i, j, k) = (px - self.px0, pz - self.pz0, py - self.base_y);
        if (0..5).contains(&i) && (0..5).contains(&j) && k >= 0 && (k as usize) < self.ny {
            Some((i * 5 + j) as usize * self.ny + k as usize)
        } else {
            None
        }
    }

    fn reset(&mut self, x: i32, z: i32) {
        self.x = x;
        self.z = z;
    }

    /// Whether the zoomed biome at (x, y, z) is in the set `bits`, when that's decided
    /// without zooming: the zoom always picks one of the 8 surrounding noise biomes, so if
    /// all or none of them are in the set, that is the answer. None otherwise.
    fn in_set_for_candidates(&mut self, area: &BiomeArea, x: i32, y: i32, z: i32, bits: &[u64]) -> Option<bool> {
        if x != self.x || z != self.z {
            return None;
        }
        let (px, py, pz) = ((x - 2) >> 2, (y - 2) >> 2, (z - 2) >> 2);
        let slot = self.slot(px, py, pz)?;
        let mask = (*self.candidates[slot].get_or_insert_with(|| {
            let mut mask = [0u64; 4];
            for i in 0..8 {
                let id = area.noise_biome(px + (i >> 2 & 1), py + (i >> 1 & 1), pz + (i & 1)) as usize;
                if id >= 256 {
                    return None;
                }
                mask[id / 64] |= 1 << (id % 64);
            }
            Some(mask)
        }))?;
        let (mut any, mut all) = (false, true);
        for (w, m) in mask.iter().enumerate() {
            let b = bits.get(w).copied().unwrap_or(0);
            any |= m & b != 0;
            all &= m & !b == 0;
        }
        if all {
            Some(true)
        } else if !any {
            Some(false)
        } else {
            None
        }
    }

    fn biome(&mut self, seed: i64, area: &BiomeArea, x: i32, y: i32, z: i32) -> u32 {
        if x != self.x || z != self.z {
            return zoomed_biome(seed, area, x, y, z);
        }
        let (ax, ay, az) = (x - 2, y - 2, z - 2);
        let (px, py, pz) = (ax >> 2, ay >> 2, az >> 2);
        // The zoom always picks one of these 8 noise biomes; if they agree, that's it.
        if let Some(slot) = self.slot(px, py, pz) {
            let uniform = *self.uniform[slot].get_or_insert_with(|| {
                let first = area.noise_biome(px, py, pz);
                [(1, 0, 0), (0, 1, 0), (0, 0, 1), (1, 1, 0), (1, 0, 1), (0, 1, 1), (1, 1, 1)]
                    .iter()
                    .all(|&(dx, dy, dz)| area.noise_biome(px + dx, py + dy, pz + dz) == first)
                    .then_some(first)
            });
            if let Some(id) = uniform {
                return id;
            }
        }
        let (fx, fy, fz) = ((ax & 3) as f64 / 4.0, (ay & 3) as f64 / 4.0, (az & 3) as f64 / 4.0);
        let mut min_i = 0;
        let mut min_distance = f64::INFINITY;
        for i in 0..8 {
            let (x_even, y_even, z_even) = (i & 4 == 0, i & 2 == 0, i & 1 == 0);
            let cy = if y_even { py } else { py + 1 };
            let Some(slot) = self.slot(px, cy, pz) else {
                return zoomed_biome(seed, area, x, y, z);
            };
            let corners = *self.cache[slot].get_or_insert_with(|| {
                [
                    corner_fiddles(seed, px, cy, pz),
                    corner_fiddles(seed, px, cy, pz + 1),
                    corner_fiddles(seed, px + 1, cy, pz),
                    corner_fiddles(seed, px + 1, cy, pz + 1),
                ]
            });
            let [fiddle_x, fiddle_y, fiddle_z] = corners[(!x_even as usize) * 2 + !z_even as usize];
            let dx = if x_even { fx } else { fx - 1.0 };
            let dy = if y_even { fy } else { fy - 1.0 };
            let dz = if z_even { fz } else { fz - 1.0 };
            let next = (dz + fiddle_z) * (dz + fiddle_z) + (dy + fiddle_y) * (dy + fiddle_y) + (dx + fiddle_x) * (dx + fiddle_x);
            if min_distance > next {
                min_i = i;
                min_distance = next;
            }
        }
        let bx = if min_i & 4 == 0 { px } else { px + 1 };
        let by = if min_i & 2 == 0 { py } else { py + 1 };
        let bz = if min_i & 1 == 0 { pz } else { pz + 1 };
        area.noise_biome(bx, by, bz)
    }
}

/// `BiomeManager.getBiome`
fn zoomed_biome(seed: i64, area: &BiomeArea, x: i32, y: i32, z: i32) -> u32 {
    let (ax, ay, az) = (x - 2, y - 2, z - 2);
    let (px, py, pz) = (ax >> 2, ay >> 2, az >> 2);
    let (fx, fy, fz) = ((ax & 3) as f64 / 4.0, (ay & 3) as f64 / 4.0, (az & 3) as f64 / 4.0);
    let mut min_i = 0;
    let mut min_distance = f64::INFINITY;
    for i in 0..8 {
        let (x_even, y_even, z_even) = (i & 4 == 0, i & 2 == 0, i & 1 == 0);
        let cx = if x_even { px } else { px + 1 };
        let cy = if y_even { py } else { py + 1 };
        let cz = if z_even { pz } else { pz + 1 };
        let dx = if x_even { fx } else { fx - 1.0 };
        let dy = if y_even { fy } else { fy - 1.0 };
        let dz = if z_even { fz } else { fz - 1.0 };
        let mut r = seed;
        r = lcg(r, cx as i64);
        r = lcg(r, cy as i64);
        r = lcg(r, cz as i64);
        r = lcg(r, cx as i64);
        r = lcg(r, cy as i64);
        r = lcg(r, cz as i64);
        let fiddle_x = fiddle(r);
        r = lcg(r, seed);
        let fiddle_y = fiddle(r);
        r = lcg(r, seed);
        let fiddle_z = fiddle(r);
        let next = (dz + fiddle_z) * (dz + fiddle_z) + (dy + fiddle_y) * (dy + fiddle_y) + (dx + fiddle_x) * (dx + fiddle_x);
        if min_distance > next {
            min_i = i;
            min_distance = next;
        }
    }
    let bx = if min_i & 4 == 0 { px } else { px + 1 };
    let by = if min_i & 2 == 0 { py } else { py + 1 };
    let bz = if min_i & 1 == 0 { pz } else { pz + 1 };
    area.noise_biome(bx, by, bz)
}

/// Per-chunk surface state (`MaterialRuleContext` and the column loop).
struct Run<'a, 'g> {
    s: &'a Surface,
    noises: &'a [std::sync::Arc<dyn Noise>],
    flags: &'a [u16],
    ctx: &'a mut Context<'g>,
    biomes: &'a BiomeArea,
    gen: GenContext,
    sea_level: i32,
    expected_volume: DensityVolume,
    preliminary_volume: DensityVolume,
    preliminary_buffer: Option<Vec<f32>>,
    block_x: i32,
    block_y: i32,
    block_z: i32,
    gradient_x: i32,
    gradient_z: i32,
    surface_depth: i32,
    surface_secondary: Option<f64>,
    min_surface_level: Option<i32>,
    biome: Option<u32>,
    water_height: i32,
    stone_below: i32,
    stone_above: i32,
    /// Noise memos indexed by noise id: (update counter, value).
    noise_2d: Vec<(u64, f64)>,
    noise_3d: Vec<(u64, f64)>,
    update_xz: u64,
    update_y: u64,
    zoom: ColumnZoom,
    veins: Vec<Vein>,
}

/// A rule after `compile`; ore veins refer to their prefilled density buffers in `Run::veins`.
#[derive(Clone)]
enum Compiled {
    Sequence(Vec<Compiled>),
    Condition(Cond, Box<Compiled>),
    Block(u32),
    Bandlands,
    OreVein(usize),
}

struct Vein {
    ore: u32,
    raw_ore: u32,
    filler: u32,
    raw_ore_chance: f32,
    density: Vec<f32>,
    density_node: NodeId,
    richness: Vec<f32>,
    richness_node: NodeId,
    filler_gap: NodeId,
    random: AnyPositional,
}

/// Pruned copies of the compiled rule for blocks whose vertical gradients (by Y band) and
/// `above_preliminary_surface` answer are already known. A condition is only dropped when
/// evaluating it has no side effect: gradients outside their random range, and the
/// preliminary surface once the column has computed it.
struct Variants {
    /// Sorted Y values where some vertical gradient changes state.
    breaks: Vec<i32>,
    /// Per (band, preliminary state), built on first use; inner None: never places a block.
    cache: Vec<Option<Option<Compiled>>>,
}

impl Run<'_, '_> {
    fn noise(&self, index: usize) -> &dyn Noise {
        &*self.noises[index]
    }

    /// `MaterialRule.compile`, in vanilla's traversal order (ore veins prefill here).
    fn compile(&mut self, rule: &Rule) -> Compiled {
        match rule {
            Rule::Sequence(rules) if rules.len() == 1 => self.compile(&rules[0]),
            Rule::Sequence(rules) => Compiled::Sequence(rules.iter().map(|r| self.compile(r)).collect()),
            Rule::Condition(c, then) => Compiled::Condition(c.clone(), Box::new(self.compile(then))),
            Rule::Block(s) => Compiled::Block(*s),
            Rule::Bandlands => Compiled::Bandlands,
            Rule::OreVein { ore, raw_ore, filler, raw_ore_chance, density, richness, filler_gap, random } => {
                let v = self.expected_volume;
                let density_buffer = self.ctx.sample_volume_new(*density, &v);
                let richness_buffer = self.ctx.sample_volume_new(*richness, &v);
                self.veins.push(Vein {
                    ore: *ore,
                    raw_ore: *raw_ore,
                    filler: *filler,
                    raw_ore_chance: *raw_ore_chance,
                    density: density_buffer,
                    density_node: *density,
                    richness: richness_buffer,
                    richness_node: *richness,
                    filler_gap: *filler_gap,
                    random: *random,
                });
                Compiled::OreVein(self.veins.len() - 1)
            }
        }
    }

    /// The answer of `c` for every block with Y in `[lo, hi]`, when known without evaluating
    /// it: vertical gradients outside their random range, and the preliminary surface test
    /// once the column has computed it (`prelim`).
    fn known(&self, c: &Cond, lo: i32, hi: i32, prelim: Option<bool>) -> Option<bool> {
        match c {
            Cond::VerticalGradient { true_at, false_at, .. } => {
                let (t, f) = (true_at.resolve(&self.gen), false_at.resolve(&self.gen));
                if hi <= t {
                    Some(true)
                } else if lo >= f {
                    Some(false)
                } else {
                    None
                }
            }
            Cond::AbovePreliminarySurface => prelim,
            Cond::Not(inner) => self.known(inner, lo, hi, prelim).map(|v| !v),
            _ => None,
        }
    }

    fn has_side_effects(c: &Cond) -> bool {
        match c {
            Cond::AbovePreliminarySurface => true,
            Cond::Not(inner) => Self::has_side_effects(inner),
            _ => false,
        }
    }

    /// `rule` for blocks with Y in `[lo, hi]` given `prelim`; None if it never places a
    /// block. Kept conditions are evaluated as before, in the same order.
    fn prune(&self, rule: &Compiled, lo: i32, hi: i32, prelim: Option<bool>) -> Option<Compiled> {
        fn always(rule: &Compiled) -> bool {
            match rule {
                Compiled::Block(_) | Compiled::Bandlands => true,
                Compiled::Sequence(rules) => rules.iter().any(always),
                Compiled::Condition(..) | Compiled::OreVein(_) => false,
            }
        }
        match rule {
            Compiled::Sequence(rules) => {
                let mut out = Vec::new();
                for r in rules {
                    if let Some(p) = self.prune(r, lo, hi, prelim) {
                        let stop = always(&p);
                        match p {
                            Compiled::Sequence(inner) => out.extend(inner),
                            p => out.push(p),
                        }
                        if stop {
                            break;
                        }
                    }
                }
                match out.len() {
                    0 => None,
                    1 => out.pop(),
                    _ => Some(Compiled::Sequence(out)),
                }
            }
            Compiled::Condition(c, then) => match self.known(c, lo, hi, prelim) {
                Some(false) => None,
                Some(true) => self.prune(then, lo, hi, prelim),
                None => match self.prune(then, lo, hi, prelim) {
                    Some(t) => Some(Compiled::Condition(c.clone(), Box::new(t))),
                    // The test itself has an effect (computing the preliminary surface): keep it.
                    None if Self::has_side_effects(c) => Some(Compiled::Condition(c.clone(), Box::new(Compiled::Sequence(Vec::new())))),
                    None => None,
                },
            },
            other => Some(other.clone()),
        }
    }

    fn variants(&self, rule: &Compiled) -> Variants {
        fn collect(rule: &Compiled, out: &mut Vec<(Anchor, Anchor)>) {
            match rule {
                Compiled::Sequence(rules) => rules.iter().for_each(|r| collect(r, out)),
                Compiled::Condition(c, then) => {
                    collect_cond(c, out);
                    collect(then, out);
                }
                _ => {}
            }
        }
        fn collect_cond(c: &Cond, out: &mut Vec<(Anchor, Anchor)>) {
            match c {
                Cond::VerticalGradient { true_at, false_at, .. } => out.push((*true_at, *false_at)),
                Cond::Not(inner) => collect_cond(inner, out),
                _ => {}
            }
        }
        let mut gradients = Vec::new();
        collect(rule, &mut gradients);
        let mut breaks = Vec::new();
        for (true_at, false_at) in gradients {
            breaks.push(true_at.resolve(&self.gen).saturating_add(1));
            breaks.push(false_at.resolve(&self.gen));
        }
        breaks.sort_unstable();
        breaks.dedup();
        let slots = (breaks.len() + 1) * 3;
        Variants { breaks, cache: (0..slots).map(|_| None).collect() }
    }

    /// The pruned rule for a block at `y` with preliminary state `prelim`.
    fn variant<'v>(&self, rule: &Compiled, variants: &'v mut Variants, y: i32, prelim: Option<bool>) -> Option<&'v Compiled> {
        let band = variants.breaks.partition_point(|&b| b <= y);
        let lo = if band == 0 { i32::MIN } else { variants.breaks[band - 1] };
        let hi = if band == variants.breaks.len() { i32::MAX } else { variants.breaks[band] - 1 };
        let slot = band * 3
            + match prelim {
                None => 0,
                Some(false) => 1,
                Some(true) => 2,
            };
        variants.cache[slot].get_or_insert_with(|| self.prune(rule, lo, hi, prelim)).as_ref()
    }

    fn update_xz(&mut self, x: i32, z: i32, gradient_x: i32, gradient_z: i32) {
        self.block_x = x;
        self.block_z = z;
        self.gradient_x = gradient_x;
        self.gradient_z = gradient_z;
        // MaterialSystem.getSurfaceDepth
        let noise = self.noise(self.s.surface_noise).get3(x as f64, 0.0, z as f64) as f64;
        let random = self.s.noise_random.at(x, 0, z).next_double();
        self.surface_depth = (noise * 2.75 + 3.0 + random * 0.25) as i32;
        self.surface_secondary = None;
        self.min_surface_level = None;
        self.update_xz += 1;
        self.update_y += 1;
        self.biome = None;
    }

    fn update_y(&mut self, stone_above: i32, stone_below: i32, water_height: i32, y: i32) {
        self.biome = None;
        self.block_y = y;
        self.water_height = water_height;
        self.stone_below = stone_below;
        self.stone_above = stone_above;
        self.update_y += 1;
    }

    fn surface_secondary(&mut self) -> f64 {
        if let Some(v) = self.surface_secondary {
            return v;
        }
        let v = self.noise(self.s.surface_secondary_noise).get3(self.block_x as f64, 0.0, self.block_z as f64) as f64;
        self.surface_secondary = Some(v);
        v
    }

    fn biome(&mut self) -> u32 {
        if let Some(b) = self.biome {
            return b;
        }
        let b = self.zoom.biome(self.s.zoom_seed, self.biomes, self.block_x, self.block_y, self.block_z);
        self.biome = Some(b);
        b
    }

    /// `MaterialRuleContext.getMinSurfaceLevel`: touches the density caches, so it keeps
    /// vanilla's laziness exactly (once per column, buffer on first use).
    fn min_surface_level(&mut self) -> i32 {
        if let Some(v) = self.min_surface_level {
            return v;
        }
        let level = match self.preliminary_volume.index_of_block(self.block_x, 0, self.block_z) {
            Some(index) => {
                if self.preliminary_buffer.is_none() {
                    let v = self.preliminary_volume;
                    self.preliminary_buffer = Some(self.ctx.sample_volume_new(self.s.preliminary_surface, &v));
                }
                self.preliminary_buffer.as_ref().unwrap()[index]
            }
            None => self.ctx.sample_value(self.s.preliminary_surface, self.block_x, 0, self.block_z),
        };
        let v = floor(level as f64) + self.surface_depth - 8;
        self.min_surface_level = Some(v);
        v
    }

    /// `Biome.getTemperature` (with the FROZEN modifier and height adjustment).
    fn temperature(&self, biome: u32, x: i32, y: i32, z: i32) -> f32 {
        let info = self.s.biomes.get(biome as usize).copied().unwrap_or_default();
        let mut adjusted = info.temperature;
        if info.frozen {
            let large = (self.noise(self.s.frozen_temperature_noise).get2(x as f64 * 0.05, z as f64 * 0.05) * 7.0) as f64;
            let edge = self.noise(self.s.biome_info_noise).get2(x as f64 * 0.2, z as f64 * 0.2) as f64;
            if large + edge < 0.3 {
                let small = self.noise(self.s.biome_info_noise).get2(x as f64 * 0.09, z as f64 * 0.09) as f64;
                if small < 0.8 {
                    adjusted = 0.2;
                }
            }
        }
        let snow_level = self.sea_level + 17;
        if y > snow_level {
            let v = self.noise(self.s.temperature_noise).get2((x as f32 / 8.0) as f64, (z as f32 / 8.0) as f64) * 8.0;
            adjusted - (v + y as f32 - snow_level as f32) * 0.05 / 40.0
        } else {
            adjusted
        }
    }

    fn test(&mut self, c: &Cond) -> bool {
        match c {
            Cond::Biome(bits) => {
                let contains = |b: u32| bits.get(b as usize / 64).is_some_and(|w| w & (1 << (b % 64)) != 0);
                if self.biome.is_none() {
                    if let Some(known) = self.zoom.in_set_for_candidates(self.biomes, self.block_x, self.block_y, self.block_z, bits) {
                        return known;
                    }
                }
                let b = self.biome();
                contains(b)
            }
            Cond::NoiseThreshold { noise, min, max, is3d } => {
                let value = if *is3d {
                    let (stamp, v) = self.noise_3d[*noise];
                    if stamp == self.update_y {
                        v
                    } else {
                        let v = self.noise(*noise).get3(self.block_x as f64, self.block_y as f64, self.block_z as f64) as f64;
                        self.noise_3d[*noise] = (self.update_y, v);
                        v
                    }
                } else {
                    let (stamp, v) = self.noise_2d[*noise];
                    if stamp == self.update_xz {
                        v
                    } else {
                        let v = self.noise(*noise).get3(self.block_x as f64, 0.0, self.block_z as f64) as f64;
                        self.noise_2d[*noise] = (self.update_xz, v);
                        v
                    }
                };
                value >= *min && value <= *max
            }
            Cond::VerticalGradient { random, true_at, false_at } => {
                let (t, f) = (true_at.resolve(&self.gen), false_at.resolve(&self.gen));
                let y = self.block_y;
                if y <= t {
                    return true;
                }
                if y >= f {
                    return false;
                }
                let probability = 1.0 + ((y as f64 - t as f64) / (f as f64 - t as f64)) * (0.0 - 1.0);
                (random.at(self.block_x, y, self.block_z).next_float() as f64) < probability
            }
            Cond::YAbove { anchor, mult, add_stone_depth } => {
                self.block_y + if *add_stone_depth { self.stone_above } else { 0 }
                    >= anchor.resolve(&self.gen) + self.surface_depth * mult
            }
            Cond::Water { offset, mult, add_stone_depth } => {
                self.water_height == i32::MIN
                    || self.block_y + if *add_stone_depth { self.stone_above } else { 0 }
                        >= self.water_height + offset + self.surface_depth * mult
            }
            Cond::Temperature => {
                let b = self.biome();
                !(self.temperature(b, self.block_x, self.block_y, self.block_z) >= 0.15)
            }
            Cond::Steep => self.gradient_x <= -4 || self.gradient_z >= 4,
            Cond::Not(inner) => !self.test(inner),
            Cond::Hole => self.surface_depth <= 0,
            Cond::AbovePreliminarySurface => self.block_y >= self.min_surface_level(),
            Cond::StoneDepth { offset, add_surface_depth, secondary_range, ceiling } => {
                let depth = if *ceiling { self.stone_below } else { self.stone_above };
                let surface = if *add_surface_depth { self.surface_depth } else { 0 };
                let secondary = if *secondary_range == 0 {
                    0
                } else {
                    let s = self.surface_secondary();
                    // Mth.map(s, -1, 1, 0, range) = lerp(inverseLerp(s, -1, 1), 0, range)
                    (0.0 + ((s - -1.0) / (1.0 - -1.0)) * (*secondary_range as f64 - 0.0)) as i32
                };
                depth <= 1 + offset + surface + secondary
            }
        }
    }

    fn band(&self, x: i32, y: i32, z: i32) -> u32 {
        let offset = crate::density::java_round(self.noise(self.s.clay_bands_offset_noise).get3(x as f64, 0.0, z as f64) * 4.0);
        let n = self.s.clay_bands.len() as i32;
        self.s.clay_bands[((y + offset + n) % n) as usize]
    }

    fn density_getter(&mut self, vein: usize, richness: bool) -> f32 {
        match self.expected_volume.index_of_block(self.block_x, self.block_y, self.block_z) {
            Some(index) => {
                let v = &self.veins[vein];
                if richness {
                    v.richness[index]
                } else {
                    v.density[index]
                }
            }
            None => {
                let v = &self.veins[vein];
                let node = if richness { v.richness_node } else { v.density_node };
                self.ctx.sample_value(node, self.block_x, self.block_y, self.block_z)
            }
        }
    }

    fn apply(&mut self, rule: &Compiled) -> Option<u32> {
        match rule {
            Compiled::Sequence(rules) => {
                for r in rules {
                    if let Some(s) = self.apply(r) {
                        return Some(s);
                    }
                }
                None
            }
            Compiled::Condition(c, then) => {
                if !self.test(c) {
                    None
                } else {
                    self.apply(then)
                }
            }
            Compiled::Block(s) => Some(*s),
            Compiled::Bandlands => Some(self.band(self.block_x, self.block_y, self.block_z)),
            Compiled::OreVein(index) => {
                let index = *index;
                let d = self.density_getter(index, false);
                if d <= 0.0 {
                    return None;
                }
                let v = &self.veins[index];
                let (ore, raw_ore, filler, raw_ore_chance, filler_gap) = (v.ore, v.raw_ore, v.filler, v.raw_ore_chance, v.filler_gap);
                let mut r = v.random.at(self.block_x, self.block_y, self.block_z);
                if r.next_float() > d {
                    return None;
                }
                let rich = self.density_getter(index, true);
                if r.next_float() < rich && self.ctx.sample_value(filler_gap, self.block_x, self.block_y, self.block_z) < 0.0 {
                    Some(if r.next_float() < raw_ore_chance { raw_ore } else { ore })
                } else {
                    Some(filler)
                }
            }
        }
    }
}

fn is_stone(flags: &[u16], state: Option<u32>) -> bool {
    match state {
        Some(s) => flags[s as usize] & (STATE_AIR | STATE_HAS_FLUID) == 0,
        None => false,
    }
}

/// Inputs of one surface run.
pub struct SurfaceInput<'a> {
    pub biomes: &'a BiomeArea,
    pub gen: GenContext,
    /// Height accessor for generation (`minY`, `maxY`).
    pub gen_min_y: i32,
    pub gen_max_y: i32,
    pub default_block: u32,
    pub plain_air: u32,
    pub sea_level: i32,
    /// The noise volume `doFill` used (its minY may be above the chunk's).
    pub noise_volume: DensityVolume,
}

impl Surface {
    /// `MaterialSystem.buildSurface` on `chunk`.
    pub fn build(&self, ctx: &mut Context, noises: &[std::sync::Arc<dyn Noise>], flags: &[u16], chunk: &mut ChunkBlocks, input: &SurfaceInput) {
        let full = input.noise_volume;
        let highest = chunk.highest_filled_section();
        let max_block_y = (chunk.min_y >> 4) * 16 + highest * 16 + 15;
        let narrowed = DensityVolume::new(
            [full.size_x, (max_block_y - full.min_y + 1).max(1), full.size_z],
            [full.min_x, full.min_y, full.min_z],
            [1, 1, 1],
        );
        let preliminary_volume = DensityVolume::new([narrowed.size_x, 1, narrowed.size_z], [narrowed.min_x, 0, narrowed.min_z], [1, 1, 1]);
        let mut run = Run {
            s: self,
            noises,
            flags,
            ctx,
            biomes: input.biomes,
            gen: input.gen,
            sea_level: input.sea_level,
            expected_volume: narrowed,
            preliminary_volume,
            preliminary_buffer: None,
            block_x: 0,
            block_y: 0,
            block_z: 0,
            gradient_x: 0,
            gradient_z: 0,
            surface_depth: 0,
            surface_secondary: None,
            min_surface_level: None,
            biome: None,
            water_height: 0,
            stone_below: 0,
            stone_above: 0,
            noise_2d: vec![(0, 0.0); noises.len()],
            noise_3d: vec![(0, 0.0); noises.len()],
            update_xz: 0,
            update_y: 0,
            zoom: ColumnZoom::new(),
            veins: Vec::new(),
        };
        run.zoom.init_chunk(full.min_x, full.min_z, chunk.min_y, chunk.max_y() + 16);
        let prefill_start = std::time::Instant::now();
        let rule = run.compile(&self.rule);
        PREFILL_NANOS.fetch_add(prefill_start.elapsed().as_nanos() as u64, std::sync::atomic::Ordering::Relaxed);
        let mut variants = run.variants(&rule);
        let min_bx = full.min_x;
        let min_bz = full.min_z;
        let height_at = |chunk: &ChunkBlocks, x: i32, z: i32| chunk.world_surface[(x + z * 16) as usize] - 1;
        for x in 0..16 {
            for z in 0..16 {
                let bx = min_bx + x;
                let bz = min_bz + z;
                let starting_height = height_at(chunk, x, z) + 1;
                let surface_biome = zoomed_biome(self.zoom_seed, input.biomes, bx, starting_height, bz);
                let info = self.biomes.get(surface_biome as usize).copied().unwrap_or_default();
                if info.eroded_badlands {
                    run.eroded_badlands(chunk, input, x, z, bx, bz, starting_height);
                }
                let height = height_at(chunk, x, z) + 1;
                let gradient_x = height_at(chunk, (x + 1).min(15), z) - height_at(chunk, (x - 1).max(0), z);
                let gradient_z = height_at(chunk, x, (z + 1).min(15)) - height_at(chunk, x, (z - 1).max(0));
                run.update_xz(bx, bz, gradient_x, gradient_z);
                run.zoom.reset(bx, bz);
                let mut stone_above = 0;
                let mut water_height = i32::MIN;
                let mut next_ceiling = i32::MAX;
                let end_y = chunk.min_y;
                let mut y = height;
                while y >= end_y {
                    let old = chunk.get(x, y, z);
                    let old_flags = old.map_or(STATE_AIR, |s| flags[s as usize]);
                    if old_flags & STATE_AIR != 0 {
                        stone_above = 0;
                        water_height = i32::MIN;
                    } else if old_flags & STATE_HAS_FLUID != 0 {
                        if water_height == i32::MIN {
                            water_height = y + 1;
                        }
                    } else {
                        if next_ceiling >= y {
                            next_ceiling = WAY_BELOW_MIN_Y;
                            let mut look = y - 1;
                            while look >= end_y - 1 {
                                if !is_stone(flags, chunk.get(x, look, z)) {
                                    next_ceiling = look + 1;
                                    break;
                                }
                                look -= 1;
                            }
                        }
                        stone_above += 1;
                        let stone_below = y - next_ceiling + 1;
                        run.update_y(stone_above, stone_below, water_height, y);
                        if y >= input.gen_min_y && y <= input.gen_max_y {
                            let prelim = run.min_surface_level.map(|level| y >= level);
                            if let Some(pruned) = run.variant(&rule, &mut variants, y, prelim) {
                                if let Some(state) = run.apply(pruned) {
                                    set_column_block(chunk, flags, input, x, y, z, bx, bz, state);
                                }
                            }
                        }
                    }
                    y -= 1;
                }
                if info.frozen_ocean {
                    let min_surface = run.min_surface_level();
                    run.frozen_ocean(chunk, input, surface_biome, min_surface, x, z, bx, bz, starting_height);
                }
            }
        }
        if let Some(b) = run.preliminary_buffer.take() {
            crate::density::recycle([b]);
        }
        for vein in run.veins.drain(..) {
            crate::density::recycle([vein.density, vein.richness]);
        }
    }
}

/// Time spent prefilling ore vein volumes (diagnostics).
pub static PREFILL_NANOS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// State of `Surface::top_material` kept for one chunk: compiling the rule (which prefills
/// ore vein volumes) once instead of per call. Vein values elsewhere are sampled per point,
/// which gives the same values.
#[derive(Default)]
pub struct TopMaterialState {
    rule: Option<Compiled>,
    veins: Vec<Vein>,
    zoom: Option<ColumnZoom>,
    volume: Option<DensityVolume>,
}

impl TopMaterialState {
    pub fn recycle(&mut self) {
        for vein in self.veins.drain(..) {
            crate::density::recycle([vein.density, vein.richness]);
        }
    }
}

impl Surface {
    /// `MaterialSystem.topMaterial`: the surface rule for one block with stone depth 1 above
    /// and below, water just above it if `under_fluid`.
    #[allow(clippy::too_many_arguments)]
    pub fn top_material(
        &self,
        state: &mut TopMaterialState,
        ctx: &mut Context,
        noises: &[std::sync::Arc<dyn Noise>],
        flags: &[u16],
        chunk: &ChunkBlocks,
        input: &SurfaceInput,
        x: i32,
        y: i32,
        z: i32,
        under_fluid: bool,
    ) -> Option<u32> {
        let bx = input.noise_volume.min_x + x;
        let bz = input.noise_volume.min_z + z;
        let volume = *state.volume.get_or_insert(DensityVolume::new([1, 1, 1], [bx, y, bz], [1, 1, 1]));
        let zoom = state.zoom.take().unwrap_or_else(|| {
            let mut zoom = ColumnZoom::new();
            zoom.init_chunk(input.noise_volume.min_x, input.noise_volume.min_z, chunk.min_y, chunk.max_y() + 16);
            zoom
        });
        let mut run = Run {
            s: self,
            noises,
            flags,
            ctx,
            biomes: input.biomes,
            gen: input.gen,
            sea_level: input.sea_level,
            expected_volume: volume,
            preliminary_volume: DensityVolume::new([1, 1, 1], [volume.min_x, 0, volume.min_z], [1, 1, 1]),
            preliminary_buffer: None,
            block_x: 0,
            block_y: 0,
            block_z: 0,
            gradient_x: 0,
            gradient_z: 0,
            surface_depth: 0,
            surface_secondary: None,
            min_surface_level: None,
            biome: None,
            water_height: 0,
            stone_below: 0,
            stone_above: 0,
            noise_2d: vec![(0, 0.0); noises.len()],
            noise_3d: vec![(0, 0.0); noises.len()],
            update_xz: 0,
            update_y: 0,
            zoom,
            veins: std::mem::take(&mut state.veins),
        };
        let rule = match state.rule.take() {
            Some(rule) => rule,
            None => run.compile(&self.rule),
        };
        let height_at = |x: i32, z: i32| chunk.world_surface[(x + z * 16) as usize] - 1;
        let gradient_x = height_at((x + 1).min(15), z) - height_at((x - 1).max(0), z);
        let gradient_z = height_at(x, (z + 1).min(15)) - height_at(x, (z - 1).max(0));
        run.update_xz(bx, bz, gradient_x, gradient_z);
        run.zoom.reset(bx, bz);
        run.update_y(1, 1, if under_fluid { y + 1 } else { i32::MIN }, y);
        let result = run.apply(&rule);
        if let Some(b) = run.preliminary_buffer.take() {
            crate::density::recycle([b]);
        }
        state.veins = std::mem::take(&mut run.veins);
        state.zoom = Some(run.zoom);
        state.rule = Some(rule);
        result
    }
}

/// `BlockColumn.setBlock` of `MaterialSystem.buildSurface`.
#[allow(clippy::too_many_arguments)]
fn set_column_block(chunk: &mut ChunkBlocks, flags: &[u16], input: &SurfaceInput, x: i32, y: i32, z: i32, bx: i32, bz: i32, state: u32) {
    if y >= input.gen_min_y && y <= input.gen_max_y {
        chunk.set_block_state(x, y, z, state, flags, input.plain_air);
        if flags[state as usize] & STATE_HAS_FLUID != 0 {
            chunk.post_processing.push((bx, y, bz));
        }
    }
}

impl Run<'_, '_> {
    #[allow(clippy::too_many_arguments)]
    fn eroded_badlands(&mut self, chunk: &mut ChunkBlocks, input: &SurfaceInput, x: i32, z: i32, bx: i32, bz: i32, height: i32) {
        let surface = (self.noise(self.s.badlands_surface_noise).get3(bx as f64, 0.0, bz as f64) as f64 * 8.25).abs();
        let pillar = (self.noise(self.s.badlands_pillar_noise).get3(bx as f64 * 0.2, 0.0, bz as f64 * 0.2) * 15.0) as f64;
        let pillar_buffer = java_min_d(surface, pillar);
        if pillar_buffer <= 0.0 {
            return;
        }
        let pillar_floor = (self.noise(self.s.badlands_pillar_roof_noise).get3(bx as f64 * 0.75, 0.0, bz as f64 * 0.75) as f64 * 1.5).abs();
        let extension_top = 64.0 + java_min_d(pillar_buffer * pillar_buffer * 2.5, (pillar_floor * 50.0).ceil() + 24.0);
        let start_y = floor(extension_top);
        if height > start_y {
            return;
        }
        let mut y = start_y;
        while y >= chunk.min_y {
            let old = chunk.get(x, y, z);
            let f = old.map_or(STATE_AIR, |s| self.flags[s as usize]);
            if f & STATE_DEFAULT_BLOCK != 0 {
                break;
            }
            if f & STATE_WATER != 0 {
                return;
            }
            y -= 1;
        }
        let mut y = start_y;
        while y >= chunk.min_y && chunk.get(x, y, z).is_none_or(|s| self.flags[s as usize] & STATE_AIR != 0) {
            set_column_block(chunk, self.flags, input, x, y, z, bx, bz, input.default_block);
            y -= 1;
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn frozen_ocean(
        &mut self,
        chunk: &mut ChunkBlocks,
        input: &SurfaceInput,
        biome: u32,
        min_surface_level: i32,
        x: i32,
        z: i32,
        bx: i32,
        bz: i32,
        height: i32,
    ) {
        let surface = (self.noise(self.s.iceberg_surface_noise).get3(bx as f64, 0.0, bz as f64) as f64 * 8.25).abs();
        let pillar = (self.noise(self.s.iceberg_pillar_noise).get3(bx as f64 * 1.28, 0.0, bz as f64 * 1.28) * 15.0) as f64;
        let iceberg = java_min_d(surface, pillar);
        if iceberg <= 1.8 {
            return;
        }
        let roof = (self.noise(self.s.iceberg_pillar_roof_noise).get3(bx as f64 * 1.17, 0.0, bz as f64 * 1.17) as f64 * 1.5).abs();
        let mut top = java_min_d(iceberg * iceberg * 1.2, (roof * 40.0).ceil() + 14.0);
        let sea = self.sea_level;
        if self.temperature(biome, bx, sea, bz) > 0.1 {
            top -= 2.0;
        }
        if top <= 2.0 {
            return;
        }
        let extension_bottom = sea as f64 - top - 7.0;
        top += sea as f64;
        let extension_top = top;
        let mut random = self.s.noise_random.at(bx, 0, bz);
        let max_snow_depth = 2 + random.next_int_bounded(4);
        let min_snow_height = sea + 18 + random.next_int_bounded(10);
        let mut snow_depth = 0;
        let mut y = height.max(extension_top as i32 + 1);
        while y >= min_surface_level {
            let state = chunk.get(x, y, z);
            let f = state.map_or(STATE_AIR, |s| self.flags[s as usize]);
            let place = (f & STATE_AIR != 0 && y < extension_top as i32 && random.next_double() > 0.01)
                || (f & STATE_WATER != 0 && y > extension_bottom as i32 && y < sea && random.next_double() > 0.15);
            if place {
                if snow_depth <= max_snow_depth && y > min_snow_height {
                    set_column_block(chunk, self.flags, input, x, y, z, bx, bz, self.s.snow_block);
                    snow_depth += 1;
                } else {
                    set_column_block(chunk, self.flags, input, x, y, z, bx, bz, self.s.packed_ice);
                }
            }
            y -= 1;
        }
    }
}

/// `Math.min(double, double)`
fn java_min_d(a: f64, b: f64) -> f64 {
    if a.is_nan() {
        return a;
    }
    if a == 0.0 && b == 0.0 && a.to_bits() != b.to_bits() {
        return if a.is_sign_negative() { a } else { b };
    }
    if a <= b {
        a
    } else {
        b
    }
}
