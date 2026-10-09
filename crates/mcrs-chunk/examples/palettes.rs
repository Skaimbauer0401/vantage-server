//! Runs the palette transform over every chunk of a region folder and reports
//! which palette entries stay unresolved. Needs registry tables dumped by a server
//! started with MCRS_DUMP_REGISTRY=<dir>.
//!
//!     cargo run --release -p mcrs-chunk --example palettes -- <region dir> <registry dir>

use std::collections::HashMap;
use std::path::Path;

use mcrs_chunk::nbt::{self, Tag};
use mcrs_chunk::palette::{transform_chunk, PaletteRegistry};
use mcrs_chunk::RegionFile;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let (region_dir, registry_dir) = (Path::new(&args[1]), Path::new(&args[2]));
    let read = |name| std::fs::read_to_string(registry_dir.join(name)).expect("registry dump");
    let registry = PaletteRegistry {
        data_version: read("data_version.txt").trim().parse().unwrap(),
        block_states: PaletteRegistry::parse_table(&read("block_states.tsv")),
        biomes: PaletteRegistry::parse_table(&read("biomes.tsv")),
    };
    let mut unresolved: HashMap<String, usize> = HashMap::new();
    let (mut resolved, mut skipped) = (0, 0);
    for entry in std::fs::read_dir(region_dir).unwrap().flatten() {
        let Ok(region) = RegionFile::open(entry.path()) else { continue };
        for (x, z) in region.chunks() {
            let data = region.read(x, z).unwrap().unwrap();
            let (out, stats) = transform_chunk(&data, &registry, false).unwrap();
            resolved += stats.containers_resolved;
            skipped += stats.containers_skipped;
            if stats.containers_skipped == 0 {
                continue;
            }
            // Report the palettes that stayed in vanilla format.
            let bytes = out.unwrap_or(data);
            let (_, root) = nbt::parse_root(&bytes).unwrap();
            for section in &root.get("sections").unwrap().as_list().unwrap().items {
                let section = section.as_compound().unwrap();
                for kind in ["block_states", "biomes"] {
                    let Some(palette) = section.get(kind).and_then(Tag::as_compound).and_then(|c| c.get("palette")) else { continue };
                    for e in &palette.as_list().unwrap().items {
                        *unresolved.entry(format!("{kind}: {}", describe(e))).or_default() += 1;
                    }
                }
            }
        }
    }
    println!("containers resolved {resolved}, skipped {skipped}");
    let mut top: Vec<_> = unresolved.into_iter().collect();
    top.sort_by_key(|e| std::cmp::Reverse(e.1));
    for (entry, n) in top.iter().filter(|(e, _)| std::env::var("FILTER").map_or(true, |f| e.contains(&f))).take(15) {
        println!("{n:8}  {entry}");
    }
}

fn describe(tag: &Tag<'_>) -> String {
    match tag {
        Tag::String(s) => String::from_utf8_lossy(s).into_owned(),
        Tag::Compound(c) => c
            .entries
            .iter()
            .map(|(k, v)| format!("{}={}", String::from_utf8_lossy(k), describe(v)))
            .collect::<Vec<_>>()
            .join(" "),
        other => format!("{other:?}"),
    }
}
