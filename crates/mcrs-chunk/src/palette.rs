//! Resolves chunk section palettes to vanilla's global ids in Rust.
//!
//! Vanilla decodes every palette entry (`{Name, Properties}` for blocks, an id
//! string for biomes) through DFU codecs, which is the most expensive part of
//! chunk loading. Here each section's `palette` list is replaced by an
//! `mcrs:palette` int array of global ids; the bridge mod builds the container
//! from those ids with vanilla's own `PalettedContainer.unpack`.
//!
//! An entry is only resolved when it is the default-state shorthand of a known
//! block or names one complete, valid state exactly.
//!
//! For resolved block palettes Rust also writes `mcrs:counts`, the four counters
//! `LevelChunkSection.recalcBlockCounts` would compute, so Java skips that scan.
//! Anything else (missing or extra properties, unknown blocks, old data
//! versions) leaves the section untouched, so vanilla decodes it with all of
//! its usual fallback behavior.

use std::collections::HashMap;

use crate::nbt::{self, Compound, Tag, TAG_COMPOUND, TAG_STRING};
use crate::Result;

pub const PALETTE_KEY: &str = "mcrs:palette";
pub const COUNTS_KEY: &str = "mcrs:counts";

/// Per-state flags (`ChunkIo.stateFlags`), mirroring `LevelChunkSection$1BlockCounter`.
pub const FLAG_NON_EMPTY: u8 = 1;
pub const FLAG_TICKING_BLOCK: u8 = 2;
pub const FLAG_FLUID: u8 = 4;
pub const FLAG_TICKING_FLUID: u8 = 8;

const SECTION_BLOCKS: usize = 4096;
/// Larger palettes use vanilla's global-palette configuration; not counted here.
const MAX_COUNTED_PALETTE: usize = 256;

/// Global id tables exported from the running server (see `RegistryExport.java`).
#[derive(Debug, Default)]
pub struct PaletteRegistry {
    /// `SharedConstants` data version; chunks from other versions go through DFU.
    pub data_version: i32,
    /// `name[k=v,...]` (properties sorted by name) -> `Block.getId(state)`, plus
    /// bare `name` -> the block's default state
    pub block_states: HashMap<Vec<u8>, i32>,
    /// biome id -> biome registry id
    pub biomes: HashMap<Vec<u8>, i32>,
    /// `FLAG_*` bits per global block state id.
    pub state_flags: Vec<u8>,
}

impl PaletteRegistry {
    /// Parses lines of `<id>\t<key>`.
    pub fn parse_table(text: &str) -> HashMap<Vec<u8>, i32> {
        text.lines()
            .filter_map(|line| {
                let (id, key) = line.split_once('\t')?;
                Some((key.as_bytes().to_vec(), id.parse().ok()?))
            })
            .collect()
    }

    /// Resolves one block palette entry in the 26.x format:
    /// - `"ns:block"`: the block's default state (inside mixed lists wrapped as `{"": "ns:block"}`)
    /// - `{id: "ns:block", properties: {k: v, ...}}`: must name every property
    ///
    /// The pre-26 `{Name, Properties}` form is deliberately not accepted.
    fn block_state_id(&self, entry: &Tag<'_>, key: &mut Vec<u8>) -> Option<i32> {
        let (name, props) = match entry {
            Tag::String(name) => (*name, None),
            Tag::Compound(c) => match c.entries.as_slice() {
                [(b"", Tag::String(name))] => (*name, None),
                _ => (c.get("id")?.as_str_bytes()?, c.get("properties")),
            },
            _ => return None,
        };
        key.clear();
        key.extend_from_slice(name);
        if let Some(props) = props {
            let props = props.as_compound()?;
            if !props.entries.is_empty() {
                let mut pairs: Vec<(&[u8], &[u8])> = Vec::with_capacity(props.entries.len());
                for (k, v) in &props.entries {
                    pairs.push((k, v.as_str_bytes()?));
                }
                pairs.sort_unstable_by(|a, b| a.0.cmp(b.0));
                key.push(b'[');
                for (i, (k, v)) in pairs.iter().enumerate() {
                    if i > 0 {
                        key.push(b',');
                    }
                    key.extend_from_slice(k);
                    key.push(b'=');
                    key.extend_from_slice(v);
                }
                key.push(b']');
            }
        }
        self.block_states.get(key.as_slice()).copied()
    }

    fn resolve_blocks(&self, palette: &Tag<'_>) -> Option<Vec<i32>> {
        let list = palette.as_list()?;
        if !matches!(list.element_type, TAG_COMPOUND | TAG_STRING) || list.items.is_empty() {
            return None;
        }
        let mut key = Vec::with_capacity(64);
        list.items.iter().map(|e| self.block_state_id(e, &mut key)).collect()
    }

    fn resolve_biomes(&self, palette: &Tag<'_>) -> Option<Vec<i32>> {
        let list = palette.as_list()?;
        if list.element_type != TAG_STRING || list.items.is_empty() {
            return None;
        }
        list.items.iter().map(|e| self.biomes.get(e.as_str_bytes()?).copied()).collect()
    }
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct TransformStats {
    pub containers_resolved: u32,
    pub containers_skipped: u32,
}

/// Rewrites a chunk's section palettes to global ids. Returns `None` when nothing
/// was resolved (the caller then hands vanilla the original bytes).
///
/// With `keep_original`, the original `palette` stays next to `mcrs:palette` so
/// the Java side can decode both and verify they match.
pub fn transform_chunk(
    nbt_bytes: &[u8],
    registry: &PaletteRegistry,
    keep_original: bool,
) -> Result<(Option<Vec<u8>>, TransformStats)> {
    let mut stats = TransformStats::default();
    let (name, mut root) = nbt::parse_root(nbt_bytes)?;
    if root.get("DataVersion").and_then(Tag::as_int) != Some(registry.data_version) {
        return Ok((None, stats));
    }
    let Some(sections) = root.get_mut("sections").and_then(Tag::as_list_mut) else {
        return Ok((None, stats));
    };
    for section in sections.items.iter_mut().filter_map(Tag::as_compound_mut) {
        for (container, is_blocks) in [("block_states", true), ("biomes", false)] {
            let Some(container) = section.get_mut(container).and_then(Tag::as_compound_mut) else {
                continue;
            };
            let Some(palette) = container.get("palette") else { continue };
            let ids = if is_blocks {
                registry.resolve_blocks(palette)
            } else {
                registry.resolve_biomes(palette)
            };
            match ids {
                Some(ids) => {
                    stats.containers_resolved += 1;
                    if is_blocks {
                        if let Some(counts) = block_counts(container, &ids, &registry.state_flags) {
                            container.insert(COUNTS_KEY, Tag::IntArrayOwned(counts.to_vec()));
                        }
                    }
                    replace_palette(container, ids, keep_original);
                }
                None => stats.containers_skipped += 1,
            }
        }
    }
    if stats.containers_resolved == 0 {
        return Ok((None, stats));
    }
    let mut out = Vec::with_capacity(nbt_bytes.len());
    nbt::write_root(name, &root, &mut out);
    Ok((Some(out), stats))
}

/// `[nonEmpty, fluid, tickingBlock, tickingFluid]` for a section, or `None` when the
/// data isn't exactly what vanilla's `PalettedContainer.unpack` accepts for a palette
/// of this size (vanilla then counts as usual).
fn block_counts(container: &Compound<'_>, ids: &[i32], flags: &[u8]) -> Option<[i32; 4]> {
    if ids.len() > MAX_COUNTED_PALETTE {
        return None;
    }
    let mut per_entry = [0u32; MAX_COUNTED_PALETTE];
    if ids.len() == 1 {
        per_entry[0] = SECTION_BLOCKS as u32;
    } else {
        // Vanilla's block configurations: 4-bit linear up to 16 entries, then 5..8 bits.
        let bits = (usize::BITS - (ids.len() - 1).leading_zeros()).max(4) as usize;
        let per_long = 64 / bits;
        let Some(Tag::LongArray(raw)) = container.get("data") else { return None };
        if raw.len() / 8 != SECTION_BLOCKS.div_ceil(per_long) {
            return None;
        }
        let mask = (1u64 << bits) - 1;
        let mut remaining = SECTION_BLOCKS;
        for word in raw.chunks_exact(8) {
            let mut word = u64::from_be_bytes(word.try_into().unwrap());
            for _ in 0..per_long.min(remaining) {
                let index = (word & mask) as usize;
                if index >= ids.len() {
                    return None;
                }
                per_entry[index] += 1;
                word >>= bits;
            }
            remaining = remaining.saturating_sub(per_long);
        }
    }
    let mut counts = [0i32; 4];
    let order = [FLAG_NON_EMPTY, FLAG_FLUID, FLAG_TICKING_BLOCK, FLAG_TICKING_FLUID];
    for (&id, &n) in ids.iter().zip(&per_entry) {
        let f = *flags.get(usize::try_from(id).ok()?)?;
        for (bit, count) in order.iter().zip(&mut counts) {
            if f & bit != 0 {
                *count += n as i32;
            }
        }
    }
    Some(counts)
}

fn replace_palette(container: &mut Compound<'_>, ids: Vec<i32>, keep_original: bool) {
    if !keep_original {
        container.remove("palette");
    }
    container.insert(PALETTE_KEY, Tag::IntArrayOwned(ids));
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::nbt::{List, TAG_COMPOUND};

    fn registry() -> PaletteRegistry {
        PaletteRegistry {
            data_version: 100,
            block_states: PaletteRegistry::parse_table(
                "0\tminecraft:air\n1\tminecraft:stone\n7\tminecraft:oak_log[axis=x]\n8\tminecraft:oak_log[axis=y]\n\
                 8\tminecraft:oak_log\n20\tminecraft:chest[facing=north,type=single,waterlogged=false]\n",
            ),
            biomes: PaletteRegistry::parse_table("0\tminecraft:plains\n3\tminecraft:forest\n"),
            state_flags: {
                let mut flags = vec![FLAG_NON_EMPTY; 21];
                flags[0] = 0; // air
                flags[8] = FLAG_NON_EMPTY | FLAG_TICKING_BLOCK;
                flags[20] = FLAG_NON_EMPTY | FLAG_FLUID | FLAG_TICKING_FLUID;
                flags
            },
        }
    }

    /// `{id, properties}` entry.
    fn state<'a>(name: &'a str, props: &[(&'a str, &'a str)]) -> Tag<'a> {
        let mut c = Compound::default();
        c.insert("id", Tag::String(name.as_bytes()));
        if !props.is_empty() {
            let mut p = Compound::default();
            for (k, v) in props {
                p.insert(k, Tag::String(v.as_bytes()));
            }
            c.insert("properties", Tag::Compound(p));
        }
        Tag::Compound(c)
    }

    /// Default-state shorthand as written inside a mixed list.
    fn wrapped(name: &str) -> Tag<'_> {
        let mut c = Compound::default();
        c.insert("", Tag::String(name.as_bytes()));
        Tag::Compound(c)
    }

    fn mixed(items: Vec<Tag<'static>>) -> List<'static> {
        List { element_type: TAG_COMPOUND, items }
    }

    fn chunk(data_version: i32, block_palette: List<'static>) -> Vec<u8> {
        let mut blocks = Compound::default();
        blocks.insert("palette", Tag::List(block_palette));
        blocks.insert("data", Tag::LongArray(&[0; 8]));
        let mut biomes = Compound::default();
        biomes.insert(
            "palette",
            Tag::List(List { element_type: TAG_STRING, items: vec![Tag::String(b"minecraft:forest")] }),
        );
        let mut section = Compound::default();
        section.insert("Y", Tag::Byte(0));
        section.insert("block_states", Tag::Compound(blocks));
        section.insert("biomes", Tag::Compound(biomes));
        let mut root = Compound::default();
        root.insert("DataVersion", Tag::Int(data_version));
        root.insert(
            "sections",
            Tag::List(List { element_type: TAG_COMPOUND, items: vec![Tag::Compound(section)] }),
        );
        let mut out = Vec::new();
        nbt::write_root(b"", &root, &mut out);
        out
    }

    fn palettes(bytes: &[u8]) -> (Option<Vec<i32>>, Option<Vec<i32>>) {
        let (_, root) = nbt::parse_root(bytes).unwrap();
        let section = root.get("sections").unwrap().as_list().unwrap().items[0].as_compound().unwrap();
        let ids = |name| {
            section.get(name)?.as_compound()?.get(PALETTE_KEY).map(|t| match t {
                Tag::IntArray(raw) => {
                    raw.chunks(4).map(|c| i32::from_be_bytes(c.try_into().unwrap())).collect()
                }
                _ => panic!("palette is not an int array"),
            })
        };
        (ids("block_states"), ids("biomes"))
    }

    #[test]
    fn resolves_all_26x_entry_forms() {
        let input = chunk(
            100,
            mixed(vec![
                wrapped("minecraft:air"),
                wrapped("minecraft:oak_log"),
                state("minecraft:oak_log", &[("axis", "x")]),
                state("minecraft:chest", &[("waterlogged", "false"), ("type", "single"), ("facing", "north")]),
            ]),
        );
        let (out, stats) = transform_chunk(&input, &registry(), false).unwrap();
        assert_eq!(stats, TransformStats { containers_resolved: 2, containers_skipped: 0 });
        assert_eq!(palettes(&out.unwrap()), (Some(vec![0, 8, 7, 20]), Some(vec![3])));
    }

    #[test]
    fn resolves_homogeneous_string_lists() {
        let strings = List {
            element_type: TAG_STRING,
            items: vec![Tag::String(b"minecraft:air"), Tag::String(b"minecraft:stone")],
        };
        let (out, _) = transform_chunk(&chunk(100, strings), &registry(), false).unwrap();
        assert_eq!(palettes(&out.unwrap()).0, Some(vec![0, 1]));
    }

    #[test]
    fn leaves_incomplete_unknown_or_legacy_states_to_vanilla() {
        let mut legacy = Compound::default();
        legacy.insert("Name", Tag::String(b"minecraft:stone"));
        for bad in [
            state("minecraft:chest", &[("facing", "north")]),            // missing properties
            state("minecraft:oak_log", &[("axis", "y"), ("x", "1")]),    // extra property
            state("minecraft:oak_log", &[("axis", "w")]),                // invalid value
            state("modded:thing", &[]),                                  // unknown block
            wrapped("stone"),                                            // namespace omitted
            Tag::Compound(legacy),                                       // pre-26 format
        ] {
            let input = chunk(100, mixed(vec![wrapped("minecraft:stone"), bad]));
            let (out, stats) = transform_chunk(&input, &registry(), false).unwrap();
            assert_eq!(stats.containers_skipped, 1);
            // Biomes still resolve; the block palette stays in vanilla format.
            assert_eq!(palettes(&out.unwrap()), (None, Some(vec![3])));
        }
    }

    #[test]
    fn other_data_versions_are_untouched() {
        let input = chunk(99, mixed(vec![wrapped("minecraft:stone")]));
        assert_eq!(transform_chunk(&input, &registry(), false).unwrap().0, None);
    }

    #[test]
    fn keep_original_retains_palette() {
        let input = chunk(100, mixed(vec![wrapped("minecraft:stone")]));
        let out = transform_chunk(&input, &registry(), true).unwrap().0.unwrap();
        let (_, root) = nbt::parse_root(&out).unwrap();
        let section = root.get("sections").unwrap().as_list().unwrap().items[0].as_compound().unwrap();
        let blocks = section.get("block_states").unwrap().as_compound().unwrap();
        assert!(blocks.get("palette").is_some() && blocks.get(PALETTE_KEY).is_some());
    }

    fn section_counts(bytes: &[u8]) -> Option<Vec<i32>> {
        let (_, root) = nbt::parse_root(bytes).unwrap();
        let section = root.get("sections").unwrap().as_list().unwrap().items[0].as_compound().unwrap();
        match section.get("block_states")?.as_compound()?.get(COUNTS_KEY)? {
            Tag::IntArray(raw) => {
                Some(raw.chunks(4).map(|c| i32::from_be_bytes(c.try_into().unwrap())).collect())
            }
            _ => None,
        }
    }

    /// Packs palette indices like vanilla's `SimpleBitStorage` (big-endian on disk).
    fn pack(indices: &[usize], bits: usize) -> &'static [u8] {
        let mut out = Vec::new();
        for chunk in indices.chunks(64 / bits) {
            let mut word = 0u64;
            for (i, &v) in chunk.iter().enumerate() {
                word |= (v as u64) << (i * bits);
            }
            out.extend_from_slice(&word.to_be_bytes());
        }
        Box::leak(out.into_boxed_slice())
    }

    fn chunk_with_data(palette: Vec<Tag<'static>>, data: &'static [u8]) -> Vec<u8> {
        let mut blocks = Compound::default();
        blocks.insert("palette", Tag::List(mixed(palette)));
        blocks.insert("data", Tag::LongArray(data));
        let mut section = Compound::default();
        section.insert("block_states", Tag::Compound(blocks));
        let mut root = Compound::default();
        root.insert("DataVersion", Tag::Int(100));
        root.insert(
            "sections",
            Tag::List(List { element_type: TAG_COMPOUND, items: vec![Tag::Compound(section)] }),
        );
        let mut out = Vec::new();
        nbt::write_root(b"", &root, &mut out);
        out
    }

    #[test]
    fn counts_blocks_like_vanilla() {
        let indices: Vec<usize> = (0..4096).map(|i| [0, 0, 1, 2][i % 4]).collect();
        let palette = vec![
            wrapped("minecraft:air"),
            wrapped("minecraft:oak_log"),
            state("minecraft:chest", &[("facing", "north"), ("type", "single"), ("waterlogged", "false")]),
        ];
        let input = chunk_with_data(palette, pack(&indices, 4));
        let (out, _) = transform_chunk(&input, &registry(), false).unwrap();
        // nonEmpty, fluid, tickingBlock, tickingFluid
        assert_eq!(section_counts(&out.unwrap()), Some(vec![2048, 1024, 1024, 1024]));
    }

    #[test]
    fn counts_wide_palettes() {
        // 20 entries need 5 bits per index.
        let names = ["minecraft:air", "minecraft:stone"];
        let palette: Vec<Tag<'static>> = (0..20).map(|i| wrapped(names[i % 2])).collect();
        let indices: Vec<usize> = (0..4096).map(|i| i % 20).collect();
        let (out, _) = transform_chunk(&chunk_with_data(palette, pack(&indices, 5)), &registry(), false).unwrap();
        let stone = indices.iter().filter(|&&i| i % 2 == 1).count() as i32;
        assert_eq!(section_counts(&out.unwrap()), Some(vec![stone, 0, 0, 0]));
    }

    #[test]
    fn single_entry_palette_counts_whole_section() {
        let input = chunk(100, mixed(vec![wrapped("minecraft:stone")]));
        let (out, _) = transform_chunk(&input, &registry(), false).unwrap();
        assert_eq!(section_counts(&out.unwrap()), Some(vec![4096, 0, 0, 0]));
    }

    #[test]
    fn no_counts_for_malformed_data() {
        let palette = || vec![wrapped("minecraft:air"), wrapped("minecraft:stone")];
        let short: &'static [u8] = &[0; 8 * 10];
        let (out, _) = transform_chunk(&chunk_with_data(palette(), short), &registry(), false).unwrap();
        assert_eq!(section_counts(&out.unwrap()), None);
        let beyond_palette = pack(&[5usize; 4096], 4);
        let (out, _) = transform_chunk(&chunk_with_data(palette(), beyond_palette), &registry(), false).unwrap();
        assert_eq!(section_counts(&out.unwrap()), None);
    }
}
