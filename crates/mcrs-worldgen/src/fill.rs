//! `NoiseBasedChunkGenerator.doFill` with `Aquifer.NoiseBasedAquifer` (26.3).
//!
//! Produces the same block states, heightmap tops, fluid post-processing positions
//! and final density-cache state as vanilla, for chunks without structure terrain
//! adaptation or blending (the caller keeps those on vanilla).

#![allow(clippy::neg_cmp_op_on_partial_ord, clippy::if_same_then_else)]
// Negated comparisons keep Java's NaN behavior; branches mirror vanilla's.

use std::collections::HashMap;

use serde_json::Value;

use crate::density::{Beard, CacheCell, Context, Graph, LoadError, NodeId};
use crate::noise::{floor, DensityVolume};
use crate::random::{
    LegacyPositionalFactory, PositionalRandomFactory, RandomSource, XoroshiroPositionalFactory,
};

/// Properties of the few block states terrain filling can produce.
#[derive(Debug, Clone, Copy, Default)]
pub struct StateInfo {
    pub is_air: bool,
    pub is_water: bool,
    pub is_lava: bool,
    pub has_fluid: bool,
    pub blocks_motion: bool,
    pub world_surface: bool,
    /// `[nonEmpty, fluid, tickingBlock, tickingFluid]` contributions.
    pub counts: [bool; 4],
}

#[derive(Debug, Clone, Copy)]
enum AquiferRandom {
    Xoroshiro(XoroshiroPositionalFactory),
    Legacy(LegacyPositionalFactory),
}

impl AquiferRandom {
    /// `random.nextInt(10), nextInt(9), nextInt(10)` at a grid position.
    fn offsets(&self, x: i32, y: i32, z: i32) -> (i32, i32, i32) {
        fn pick(r: &mut impl RandomSource) -> (i32, i32, i32) {
            let a = r.next_int_bounded(10);
            let b = r.next_int_bounded(9);
            let c = r.next_int_bounded(10);
            (a, b, c)
        }
        match self {
            AquiferRandom::Xoroshiro(f) => pick(&mut f.at(x, y, z)),
            AquiferRandom::Legacy(f) => pick(&mut PositionalRandomFactory::at(f, x, y, z)),
        }
    }
}

/// Everything needed to fill chunks of one dimension.
pub struct Generator {
    pub graph: Graph,
    final_density: NodeId,
    aquifer: Option<AquiferNodes>,
    aquifer_random: Option<AquiferRandom>,
    pub sea_level: i32,
    pub default_block: u32,
    pub default_fluid: u32,
    pub air: u32,
    pub lava: u32,
    states: HashMap<u32, StateInfo>,
}

#[derive(Debug, Clone, Copy)]
struct AquiferNodes {
    barrier: NodeId,
    floodedness: NodeId,
    spread: NodeId,
    lava: NodeId,
    exclusion: NodeId,
    surface_level: NodeId,
}

fn get<'a>(v: &'a Value, name: &str) -> Result<&'a Value, LoadError> {
    v.get(name).ok_or_else(|| LoadError(format!("missing {name}")))
}

fn int(v: &Value, name: &str) -> Result<i64, LoadError> {
    get(v, name)?.as_i64().ok_or_else(|| LoadError(format!("{name} is not an integer")))
}

impl Generator {
    pub fn from_json(json: &Value) -> Result<Generator, LoadError> {
        let graph = Graph::from_json(json)?;
        let root = |name: &str| graph.root(name).ok_or_else(|| LoadError(format!("missing root {name}")));
        let final_density = root("final_density")?;
        let aquifers = get(json, "aquifers")?.as_bool().unwrap_or(false);
        let (aquifer, aquifer_random) = if aquifers {
            let nodes = AquiferNodes {
                barrier: root("barrier")?,
                floodedness: root("fluid_level_floodedness")?,
                spread: root("fluid_level_spread")?,
                lava: root("lava")?,
                exclusion: root("exclusion")?,
                surface_level: root("surface_level")?,
            };
            let r = get(json, "aquifer_random")?;
            let random = match get(r, "type")?.as_str() {
                Some("xoroshiro") => AquiferRandom::Xoroshiro(XoroshiroPositionalFactory { lo: int(r, "lo")?, hi: int(r, "hi")? }),
                Some("legacy") => AquiferRandom::Legacy(LegacyPositionalFactory { seed: int(r, "seed")? }),
                other => return Err(LoadError(format!("aquifer random {other:?}"))),
            };
            (Some(nodes), Some(random))
        } else {
            (None, None)
        };
        let mut states = HashMap::new();
        for (id, s) in get(json, "states")?.as_object().ok_or_else(|| LoadError("states".into()))? {
            let flag = |name: &str| s.get(name).and_then(Value::as_bool).unwrap_or(false);
            let info = StateInfo {
                is_air: flag("air"),
                is_water: flag("water"),
                is_lava: flag("lava"),
                has_fluid: flag("has_fluid"),
                blocks_motion: flag("blocks_motion"),
                world_surface: flag("world_surface"),
                counts: [flag("count_non_empty"), flag("count_fluid"), flag("count_ticking_block"), flag("count_ticking_fluid")],
            };
            states.insert(id.parse::<u32>().map_err(|_| LoadError("state id".into()))?, info);
        }
        Ok(Generator {
            graph,
            final_density,
            aquifer,
            aquifer_random,
            sea_level: int(json, "sea_level")? as i32,
            default_block: int(json, "default_block")? as u32,
            default_fluid: int(json, "default_fluid")? as u32,
            air: int(json, "air")? as u32,
            lava: int(json, "lava")? as u32,
            states,
        })
    }

    fn info(&self, id: u32) -> StateInfo {
        self.states.get(&id).copied().unwrap_or_default()
    }

    /// `createFluidPicker`: lava below y=-54, otherwise the sea.
    fn global_fluid(&self, y: i32) -> FluidStatus {
        if y < (-54).min(self.sea_level) {
            FluidStatus { level: -54, fluid: self.lava }
        } else {
            FluidStatus { level: self.sea_level, fluid: self.default_fluid }
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FluidStatus {
    pub level: i32,
    pub fluid: u32,
}

/// `DimensionType.WAY_BELOW_MIN_Y`
const WAY_BELOW_MIN_Y: i32 = -2032 << 4;

const SURFACE_SAMPLING_OFFSETS_IN_CHUNKS: [[i32; 2]; 13] = [
    [0, 0],
    [-2, -1],
    [-1, -1],
    [0, -1],
    [1, -1],
    [-3, 0],
    [-2, 0],
    [-1, 0],
    [1, 0],
    [-2, 1],
    [-1, 1],
    [0, 1],
    [1, 1],
];

fn similarity(d1: i32, d2: i32) -> f64 {
    1.0 - (d2 - d1) as f64 / 25.0
}

fn flowing_update_similarity() -> f64 {
    similarity(100, 144)
}

/// `Mth.clamp(double)` / `Math.min(double)`
fn clamp_d(v: f64, min: f64, max: f64) -> f64 {
    if v < min {
        min
    } else if v.is_nan() {
        v
    } else if v <= max {
        v
    } else {
        max
    }
}

fn lerp_d(a: f64, p0: f64, p1: f64) -> f64 {
    p0 + a * (p1 - p0)
}

fn map_d(v: f64, from_min: f64, from_max: f64, to_min: f64, to_max: f64) -> f64 {
    lerp_d((v - from_min) / (from_max - from_min), to_min, to_max)
}

fn clamped_map_d(v: f64, from_min: f64, from_max: f64, to_min: f64, to_max: f64) -> f64 {
    let factor = (v - from_min) / (from_max - from_min);
    if factor < 0.0 {
        to_min
    } else if factor > 1.0 {
        to_max
    } else {
        lerp_d(factor, to_min, to_max)
    }
}

struct Aquifer<'a> {
    generator: &'a Generator,
    nodes: AquiferNodes,
    random: AquiferRandom,
    cache: Vec<Option<FluidStatus>>,
    location_cache: Vec<Option<(i32, i32, i32)>>,
    should_schedule: bool,
    skip_sampling_above_y: i32,
    min_grid_x: i32,
    min_grid_y: i32,
    min_grid_z: i32,
    grid_size_x: i32,
    grid_size_z: i32,
    surface_level_cache: HashMap<(i32, i32), i32>,
}

fn grid_x(b: i32) -> i32 {
    b >> 4
}

fn from_grid_x(g: i32, offset: i32) -> i32 {
    (g << 4) + offset
}

fn grid_y(b: i32) -> i32 {
    b.div_euclid(12)
}

fn from_grid_y(g: i32, offset: i32) -> i32 {
    g * 12 + offset
}

impl<'a> Aquifer<'a> {
    fn new(generator: &'a Generator, nodes: AquiferNodes, random: AquiferRandom, ctx: &mut Context, volume: &DensityVolume) -> Aquifer<'a> {
        let min_grid_x = grid_x(volume.min_x - 5);
        let max_grid_x = grid_x(volume.max_block_x() - 5) + 1;
        let grid_size_x = max_grid_x - min_grid_x + 1;
        let min_grid_y = grid_y(volume.min_y + 1) - 1;
        let max_grid_y = grid_y(volume.max_block_y() + 1) + 1;
        let grid_size_y = max_grid_y - min_grid_y + 1;
        let min_grid_z = grid_x(volume.min_z - 5);
        let max_grid_z = grid_x(volume.max_block_z() - 5) + 1;
        let grid_size_z = max_grid_z - min_grid_z + 1;
        let total = (grid_size_x * grid_size_y * grid_size_z) as usize;
        let mut aquifer = Aquifer {
            generator,
            nodes,
            random,
            cache: vec![None; total],
            location_cache: vec![None; total],
            should_schedule: false,
            skip_sampling_above_y: 0,
            min_grid_x,
            min_grid_y,
            min_grid_z,
            grid_size_x,
            grid_size_z,
            surface_level_cache: HashMap::new(),
        };
        let max_surface = aquifer.max_surface_level(
            ctx,
            from_grid_x(min_grid_x, 0),
            from_grid_x(min_grid_z, 0),
            from_grid_x(max_grid_x, 9),
            from_grid_x(max_grid_z, 9),
        );
        let max_adjusted = max_surface + 8;
        let skip_grid_y = grid_y(max_adjusted + 12) + 1;
        aquifer.skip_sampling_above_y = from_grid_y(skip_grid_y, 11) - 1;
        aquifer
    }

    fn max_surface_level(&mut self, ctx: &mut Context, min_x: i32, min_z: i32, max_x: i32, max_z: i32) -> i32 {
        let (min_qx, max_qx, min_qz, max_qz) = (min_x >> 2, max_x >> 2, min_z >> 2, max_z >> 2);
        let volume = DensityVolume::new([max_qx - min_qx + 1, 1, max_qz - min_qz + 1], [min_qx << 2, 0, min_qz << 2], [4, 1, 4]);
        let buffer = ctx.sample_volume_new(self.nodes.surface_level, &volume);
        let mut max_y = i32::MIN;
        for z in 0..volume.size_z {
            for x in 0..volume.size_x {
                let level = floor(buffer[volume.index_unchecked(x, 0, z)] as f64);
                self.surface_level_cache.insert((volume.block_x(x), volume.block_z(z)), level);
                max_y = max_y.max(level);
            }
        }
        max_y
    }

    fn surface_level(&mut self, ctx: &mut Context, x: i32, z: i32) -> i32 {
        let (qx, qz) = ((x >> 2) << 2, (z >> 2) << 2);
        if let Some(level) = self.surface_level_cache.get(&(qx, qz)) {
            return *level;
        }
        let level = floor(ctx.sample_value(self.nodes.surface_level, qx, 0, qz) as f64);
        self.surface_level_cache.insert((qx, qz), level);
        level
    }

    fn index(&self, gx: i32, gy: i32, gz: i32) -> usize {
        let (x, y, z) = (gx - self.min_grid_x, gy - self.min_grid_y, gz - self.min_grid_z);
        ((y * self.grid_size_z + z) * self.grid_size_x + x) as usize
    }

    fn at(&self, status: FluidStatus, y: i32) -> u32 {
        if y < status.level {
            status.fluid
        } else {
            self.generator.air
        }
    }

    /// `computeSubstance`; `None` means the default block.
    fn compute_substance(&mut self, ctx: &mut Context, x: i32, y: i32, z: i32, density: f64) -> Option<u32> {
        if density > 0.0 {
            self.should_schedule = false;
            return None;
        }
        let g = self.generator;
        let global = g.global_fluid(y);
        if y > self.skip_sampling_above_y {
            self.should_schedule = false;
            return Some(self.at(global, y));
        }
        if g.info(self.at(global, y)).is_lava {
            self.should_schedule = false;
            return Some(g.lava);
        }
        let (xa, ya, za) = (grid_x(x - 5), grid_y(y + 1), grid_x(z - 5));
        let (mut d1, mut d2, mut d3, mut d4) = (i32::MAX, i32::MAX, i32::MAX, i32::MAX);
        let (mut c1, mut c2, mut c3, mut c4) = (0usize, 0usize, 0usize, 0usize);
        for x1 in 0..=1 {
            for y1 in -1..=1 {
                for z1 in 0..=1 {
                    let (gx, gy, gz) = (xa + x1, ya + y1, za + z1);
                    let index = self.index(gx, gy, gz);
                    let location = match self.location_cache[index] {
                        Some(l) => l,
                        None => {
                            let (ox, oy, oz) = self.random.offsets(gx, gy, gz);
                            let l = (from_grid_x(gx, ox), from_grid_y(gy, oy), from_grid_x(gz, oz));
                            self.location_cache[index] = Some(l);
                            l
                        }
                    };
                    let (dx, dy, dz) = (location.0 - x, location.1 - y, location.2 - z);
                    let d = dx * dx + dy * dy + dz * dz;
                    if d1 >= d {
                        c4 = c3;
                        c3 = c2;
                        c2 = c1;
                        c1 = index;
                        d4 = d3;
                        d3 = d2;
                        d2 = d1;
                        d1 = d;
                    } else if d2 >= d {
                        c4 = c3;
                        c3 = c2;
                        c2 = index;
                        d4 = d3;
                        d3 = d2;
                        d2 = d;
                    } else if d3 >= d {
                        c4 = c3;
                        c3 = index;
                        d4 = d3;
                        d3 = d;
                    } else if d4 >= d {
                        c4 = index;
                        d4 = d;
                    }
                }
            }
        }
        let s1 = self.status(ctx, c1);
        let sim12 = similarity(d1, d2);
        let fluid = self.at(s1, y);
        let flowing = flowing_update_similarity();
        if sim12 <= 0.0 {
            if sim12 >= flowing {
                let s2 = self.status(ctx, c2);
                self.should_schedule = s1 != s2;
            } else {
                self.should_schedule = false;
            }
            return Some(fluid);
        }
        if g.info(fluid).is_water && g.info(self.at(g.global_fluid(y - 1), y - 1)).is_lava {
            self.should_schedule = true;
            return Some(fluid);
        }
        let mut barrier = f64::NAN;
        let s2 = self.status(ctx, c2);
        let b12 = sim12 * self.pressure(ctx, x, y, z, &mut barrier, s1, s2);
        if density + b12 > 0.0 {
            self.should_schedule = false;
            return None;
        }
        let s3 = self.status(ctx, c3);
        let sim13 = similarity(d1, d3);
        if sim13 > 0.0 {
            let b13 = sim12 * sim13 * self.pressure(ctx, x, y, z, &mut barrier, s1, s3);
            if density + b13 > 0.0 {
                self.should_schedule = false;
                return None;
            }
        }
        let sim23 = similarity(d2, d3);
        if sim23 > 0.0 {
            let b23 = sim12 * sim23 * self.pressure(ctx, x, y, z, &mut barrier, s2, s3);
            if density + b23 > 0.0 {
                self.should_schedule = false;
                return None;
            }
        }
        let flow12 = s1 != s2;
        let flow23 = sim23 >= flowing && s2 != s3;
        let flow13 = sim13 >= flowing && s1 != s3;
        if !flow12 && !flow23 && !flow13 {
            self.should_schedule = sim13 >= flowing && similarity(d1, d4) >= flowing && s1 != self.status(ctx, c4);
        } else {
            self.should_schedule = true;
        }
        Some(fluid)
    }

    #[allow(clippy::too_many_arguments)]
    fn pressure(&mut self, ctx: &mut Context, x: i32, y: i32, z: i32, barrier: &mut f64, a: FluidStatus, b: FluidStatus) -> f64 {
        let g = self.generator;
        let (t1, t2) = (g.info(self.at(a, y)), g.info(self.at(b, y)));
        if (t1.is_lava && t2.is_water) || (t1.is_water && t2.is_lava) {
            return 2.0;
        }
        let diff = (a.level - b.level).abs();
        if diff == 0 {
            return 0.0;
        }
        let average = 0.5 * (a.level + b.level) as f64;
        let above = y as f64 + 0.5 - average;
        let base = diff as f64 / 2.0;
        let towards_middle = base - above.abs();
        let gradient = if above > 0.0 {
            let center = 0.0 + towards_middle;
            if center > 0.0 {
                center / 1.5
            } else {
                center / 2.5
            }
        } else {
            let center = 3.0 + towards_middle;
            if center > 0.0 {
                center / 3.0
            } else {
                center / 10.0
            }
        };
        let noise = if !(gradient < -2.0) && !(gradient > 2.0) {
            if barrier.is_nan() {
                *barrier = ctx.sample_value(self.nodes.barrier, x, y, z) as f64;
            }
            *barrier
        } else {
            0.0
        };
        2.0 * (noise + gradient)
    }

    fn status(&mut self, ctx: &mut Context, index: usize) -> FluidStatus {
        if let Some(s) = self.cache[index] {
            return s;
        }
        let (x, y, z) = self.location_cache[index].expect("location computed before status");
        let s = self.compute_fluid(ctx, x, y, z);
        self.cache[index] = Some(s);
        s
    }

    fn compute_fluid(&mut self, ctx: &mut Context, x: i32, y: i32, z: i32) -> FluidStatus {
        let g = self.generator;
        let global = g.global_fluid(y);
        let mut lowest = i32::MAX;
        let top = y + 12;
        let bottom = y - 12;
        let mut center_under_fluid = false;
        for offset in SURFACE_SAMPLING_OFFSETS_IN_CHUNKS {
            let sx = x + (offset[0] << 4);
            let sz = z + (offset[1] << 4);
            let level = self.surface_level(ctx, sx, sz);
            let adjusted = level + 8;
            let start = offset[0] == 0 && offset[1] == 0;
            if start && bottom > adjusted {
                return global;
            }
            let pokes_above = top > adjusted;
            if pokes_above || start {
                let at_surface = g.global_fluid(adjusted);
                if !g.info(self.at(at_surface, adjusted)).is_air {
                    if start {
                        center_under_fluid = true;
                    }
                    if pokes_above {
                        return at_surface;
                    }
                }
            }
            lowest = lowest.min(level);
        }
        let level = self.compute_surface_level(ctx, x, y, z, global, lowest, center_under_fluid);
        FluidStatus { level, fluid: self.compute_fluid_type(ctx, x, y, z, global, level) }
    }

    #[allow(clippy::too_many_arguments)]
    fn compute_surface_level(
        &mut self,
        ctx: &mut Context,
        x: i32,
        y: i32,
        z: i32,
        global: FluidStatus,
        lowest: i32,
        center_under_fluid: bool,
    ) -> i32 {
        let (partially, fully);
        if ctx.sample_value(self.nodes.exclusion, x, y, z) > 0.0 {
            partially = -1.0;
            fully = -1.0;
        } else {
            let below_surface = lowest + 8 - y;
            let factor = if center_under_fluid { clamped_map_d(below_surface as f64, 0.0, 64.0, 1.0, 0.0) } else { 0.0 };
            let noise = clamp_d(ctx.sample_value(self.nodes.floodedness, x, y, z) as f64, -1.0, 1.0);
            let fully_threshold = map_d(factor, 1.0, 0.0, -0.3, 0.8);
            let partially_threshold = map_d(factor, 1.0, 0.0, -0.8, 0.4);
            partially = noise - partially_threshold;
            fully = noise - fully_threshold;
        }
        if fully > 0.0 {
            global.level
        } else if partially > 0.0 {
            self.randomized_surface_level(ctx, x, y, z, lowest)
        } else {
            WAY_BELOW_MIN_Y
        }
    }

    fn randomized_surface_level(&mut self, ctx: &mut Context, x: i32, y: i32, z: i32, lowest: i32) -> i32 {
        let (cx, cy, cz) = (x.div_euclid(16), y.div_euclid(40), z.div_euclid(16));
        let middle = cy * 40 + 20;
        let spread = (ctx.sample_value(self.nodes.spread, cx, cy, cz) * 10.0) as f64;
        let quantized = floor(spread / 3.0) * 3;
        lowest.min(middle + quantized)
    }

    fn compute_fluid_type(&mut self, ctx: &mut Context, x: i32, y: i32, z: i32, global: FluidStatus, level: i32) -> u32 {
        let g = self.generator;
        let mut fluid = global.fluid;
        if level <= -10 && level != WAY_BELOW_MIN_Y && global.fluid != g.lava {
            let (cx, cy, cz) = (x.div_euclid(64), y.div_euclid(40), z.div_euclid(64));
            let lava = ctx.sample_value(self.nodes.lava, cx, cy, cz) as f64;
            if lava.abs() > 0.3 {
                fluid = g.lava;
            }
        }
        fluid
    }
}

/// One filled chunk section: vanilla's in-memory palette and 4..8-bit storage.
#[derive(Debug, Clone, Default)]
pub struct SectionOut {
    /// Empty when the section stays all air.
    pub palette: Vec<u32>,
    pub data: Vec<u64>,
    pub counts: [i32; 4],
}

#[derive(Debug)]
pub struct FillOut {
    pub sections: Vec<SectionOut>,
    /// Per column (x + z * 16): top y and state for OCEAN_FLOOR_WG / WORLD_SURFACE_WG.
    pub ocean_floor: [Option<(i32, u32)>; 256],
    pub world_surface: [Option<(i32, u32)>; 256],
    /// Block positions passed to `markPosForPostProcessing`, in vanilla's order.
    pub post_processing: Vec<(i32, i32, i32)>,
    pub cache: Vec<CacheCell>,
    /// Aquifer state after filling, so Java's aquifer continues exactly like vanilla's.
    pub aquifer: Option<AquiferState>,
}

#[derive(Debug, Clone, Default)]
pub struct AquiferState {
    pub status: Vec<Option<FluidStatus>>,
    pub location: Vec<Option<(i32, i32, i32)>>,
    /// Insertion order of `surfaceLevelCache` doesn't matter (a hash map in vanilla).
    pub surface_levels: Vec<((i32, i32), i32)>,
    pub should_schedule: bool,
}

impl Generator {
    /// `doFill` for the chunk at `chunk_x, chunk_z` over `[min_y, min_y + height)`.
    pub fn fill(&self, chunk_x: i32, chunk_z: i32, min_y: i32, height: i32, beard: Beard) -> FillOut {
        let volume = DensityVolume::new([16, height, 16], [chunk_x << 4, min_y, chunk_z << 4], [1, 1, 1]);
        let mut ctx = Context::new(&self.graph);
        ctx.beard = beard;
        // NoiseChunk's constructor builds the aquifer (sampling surface levels) first.
        let mut aquifer = match (self.aquifer, self.aquifer_random) {
            (Some(nodes), Some(random)) => Some(Aquifer::new(self, nodes, random, &mut ctx, &volume)),
            _ => None,
        };
        let density = ctx.sample_volume_new(self.final_density, &volume);

        let section_count = (height / 16) as usize;
        // Per section: palette indices (vanilla's palette starts with the initial air).
        let mut blocks: Vec<Vec<u8>> = vec![Vec::new(); section_count];
        let mut palettes: Vec<Vec<u32>> = vec![Vec::new(); section_count];
        let mut out = FillOut {
            sections: Vec::new(),
            ocean_floor: [None; 256],
            world_surface: [None; 256],
            post_processing: Vec::new(),
            cache: Vec::new(),
            aquifer: None,
        };
        let mut last_info = (u32::MAX, StateInfo::default());

        for z in 0..16 {
            let bz = volume.block_z(z);
            for x in 0..16 {
                let bx = volume.block_x(x);
                let column = (x + z * 16) as usize;
                for y in (0..height).rev() {
                    let by = volume.block_y(y);
                    let d = density[volume.index_unchecked(x, y, z)] as f64;
                    let (state, schedule) = match aquifer.as_mut() {
                        Some(a) => {
                            let s = a.compute_substance(&mut ctx, bx, by, bz, d);
                            (s, a.should_schedule)
                        }
                        None => (if d > 0.0 { None } else { Some(self.at_global(by)) }, false),
                    };
                    let state = state.unwrap_or(self.default_block);
                    if state == self.air {
                        continue;
                    }
                    let section = (y / 16) as usize;
                    if blocks[section].is_empty() {
                        blocks[section] = vec![0u8; 4096];
                        palettes[section] = vec![self.air];
                    }
                    let palette = &mut palettes[section];
                    let index = match palette.iter().position(|s| *s == state) {
                        Some(i) => i,
                        None => {
                            assert!(palette.len() < 256, "terrain fill produced more than 256 states in a section");
                            palette.push(state);
                            palette.len() - 1
                        }
                    };
                    blocks[section][((y & 15) * 256 + z * 16 + x) as usize] = index as u8;
                    if last_info.0 != state {
                        last_info = (state, self.info(state));
                    }
                    let info = last_info.1;
                    if out.world_surface[column].is_none() && info.world_surface {
                        out.world_surface[column] = Some((by, state));
                    }
                    if out.ocean_floor[column].is_none() && info.blocks_motion {
                        out.ocean_floor[column] = Some((by, state));
                    }
                    if schedule && info.has_fluid {
                        out.post_processing.push((bx, by, bz));
                    }
                }
            }
        }

        out.sections = blocks
            .iter()
            .zip(&palettes)
            .map(|(b, p)| if b.is_empty() { SectionOut::default() } else { self.pack_section(b, p) })
            .collect();
        out.cache = std::mem::take(&mut ctx.cells);
        out.aquifer = aquifer.map(|a| AquiferState {
            status: a.cache,
            location: a.location_cache,
            surface_levels: a.surface_level_cache.into_iter().collect(),
            should_schedule: a.should_schedule,
        });
        out
    }

    /// Benchmark helper: only the `finalDensity` volume of a chunk, optionally profiled.
    #[doc(hidden)]
    pub fn bench_density(&self, chunk_x: i32, chunk_z: i32, min_y: i32, height: i32, profile: &mut Option<Vec<(u64, u64)>>) -> f32 {
        let volume = DensityVolume::new([16, height, 16], [chunk_x << 4, min_y, chunk_z << 4], [1, 1, 1]);
        let mut ctx = Context::new(&self.graph);
        ctx.profile = profile.take();
        let density = ctx.sample_volume_new(self.final_density, &volume);
        *profile = ctx.profile.take();
        density[0]
    }

    fn at_global(&self, y: i32) -> u32 {
        let s = self.global_fluid(y);
        if y < s.level {
            s.fluid
        } else {
            self.air
        }
    }

    fn pack_section(&self, indices: &[u8], palette: &[u32]) -> SectionOut {
        // Vanilla's block configurations: 4-bit linear up to 16 entries, 5..8-bit hashmap.
        let bits = (usize::BITS - (palette.len() - 1).leading_zeros()).max(4) as usize;
        let per_long = 64 / bits;
        let mut data = vec![0u64; 4096usize.div_ceil(per_long)];
        let mut histogram = [0i32; 256];
        for (word, chunk) in data.iter_mut().zip(indices.chunks(per_long)) {
            let mut w = 0u64;
            for (i, &index) in chunk.iter().enumerate() {
                w |= (index as u64) << (i * bits);
                histogram[index as usize] += 1;
            }
            *word = w;
        }
        let mut counts = [0i32; 4];
        for (state, n) in palette.iter().zip(histogram) {
            let info = self.info(*state);
            for (c, on) in counts.iter_mut().zip(info.counts) {
                if on {
                    *c += n;
                }
            }
        }
        SectionOut { palette: palette.to_vec(), data, counts }
    }
}
