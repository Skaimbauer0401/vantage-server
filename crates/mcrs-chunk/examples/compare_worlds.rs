//! Compares the blocks of all fully generated chunks in two region folders.
//!
//!     cargo run --release -p mcrs-chunk --example compare_worlds -- <regionA> <regionB>
//!
//! Prints how many chunks and blocks differ. Palettes are compared by entry (raw
//! NBT of each palette entry), so this works without a block registry.

use std::collections::HashMap;
use std::path::Path;

use mcrs_chunk::nbt::{self, Tag};
use mcrs_chunk::RegionFile;

/// Block entries of one section, 4096 per section in vanilla index order.
fn section_blocks(section: &nbt::Compound<'_>) -> Option<Vec<String>> {
    let states = section.get("block_states")?.as_compound()?;
    let palette: Vec<String> = states
        .get("palette")?
        .as_list()?
        .items
        .iter()
        .map(|e| format!("{e:?}"))
        .collect();
    if palette.len() == 1 {
        return Some(vec![palette[0].clone(); 4096]);
    }
    let Some(Tag::LongArray(raw)) = states.get("data") else { return None };
    let bits = (usize::BITS - (palette.len() - 1).leading_zeros()).max(4) as usize;
    let per_long = 64 / bits;
    let mut out = Vec::with_capacity(4096);
    for i in 0..4096 {
        let word = u64::from_be_bytes(raw[(i / per_long) * 8..(i / per_long) * 8 + 8].try_into().unwrap());
        let index = ((word >> ((i % per_long) * bits)) & ((1 << bits) - 1)) as usize;
        out.push(palette.get(index)?.clone());
    }
    Some(out)
}

/// (section y -> blocks) of a chunk that reached full status.
fn chunk_blocks(data: &[u8]) -> Option<HashMap<i32, Vec<String>>> {
    let (_, root) = nbt::parse_root(data).ok()?;
    let status = root.get("Status")?.as_str_bytes()?;
    if status != b"minecraft:full" {
        return None;
    }
    let mut out = HashMap::new();
    for section in &root.get("sections")?.as_list()?.items {
        let section = section.as_compound()?;
        let y = section.get("Y")?.as_int()?;
        if let Some(blocks) = section_blocks(section) {
            out.insert(y, blocks);
        }
    }
    Some(out)
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let (a, b) = (Path::new(&args[1]), Path::new(&args[2]));
    let (mut compared, mut differing_chunks, mut differing_blocks) = (0u64, 0u64, 0u64);
    let mut examples = Vec::new();
    for entry in std::fs::read_dir(a).unwrap().flatten() {
        let name = entry.file_name();
        let Ok(ra) = RegionFile::open(entry.path()) else { continue };
        let Ok(rb) = RegionFile::open(b.join(&name)) else { continue };
        for (x, z) in ra.chunks() {
            let (Ok(Some(da)), Ok(Some(db))) = (ra.read(x, z), rb.read(x, z)) else { continue };
            let (Some(ca), Some(cb)) = (chunk_blocks(&da), chunk_blocks(&db)) else { continue };
            compared += 1;
            let mut diff = 0u64;
            for (y, blocks_a) in &ca {
                let Some(blocks_b) = cb.get(y) else {
                    diff += 4096;
                    continue;
                };
                for (i, (ba, bb)) in blocks_a.iter().zip(blocks_b).enumerate() {
                    if ba != bb {
                        diff += 1;
                        if examples.len() < 5 {
                            let pos = ra.pos();
                            examples.push(format!(
                                "chunk {} {} section {y} index {i}: {ba} vs {bb}",
                                pos.x * 32 + x as i32,
                                pos.z * 32 + z as i32
                            ));
                        }
                    }
                }
            }
            if diff > 0 {
                differing_chunks += 1;
                differing_blocks += diff;
            }
        }
    }
    println!("{compared} full chunks compared: {differing_chunks} differ, {differing_blocks} blocks differ");
    for e in examples {
        println!("  {}", &e[..e.len().min(220)]);
    }
}
