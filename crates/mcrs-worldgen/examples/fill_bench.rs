//! Fills chunks from a server worldgen export (`-Dmcrs.worldgen.dump=true`) and
//! reports throughput, without running a server.
//!
//!     cargo run --release -p mcrs-worldgen --example fill_bench -- run/mcrs-worldgen-0.json

use std::time::Instant;

use mcrs_worldgen::fill::Generator;

fn main() {
    let path = std::env::args().nth(1).expect("usage: fill_bench <export.json>");
    let json: serde_json::Value = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
    let generator = Generator::from_json(&json).unwrap();
    let (min_y, height) = (-64, 384);
    let params = mcrs_worldgen::fill::FillParams { chunk_min_y: min_y, chunk_height: height, surface: None };
    // Warm up, then measure a 10x10 area on one thread.
    for i in 0..10 {
        generator.fill(1000 + i, 1000, min_y, height, Default::default(), &params);
    }
    let start = Instant::now();
    let mut blocks = 0usize;
    for x in 0..10 {
        for z in 0..10 {
            let out = generator.fill(x * 7 - 300, z * 5 + 200, min_y, height, Default::default(), &params);
            blocks += out.sections.iter().filter(|s| !s.palette.is_empty()).count();
        }
    }
    let elapsed = start.elapsed();
    println!("100 chunks: {:.2} ms/chunk ({} non-empty sections)", elapsed.as_secs_f64() * 10.0, blocks);

    // Fill + surface with a varied (synthetic) biome layout.
    if generator.surface.is_some() {
        let quart_height = height / 4;
        let per_chunk = 16 * quart_height as usize;
        let ids: Vec<u32> = (0..9 * per_chunk).map(|i| ((i / per_chunk) * 7 % 67) as u32).collect();
        let start = Instant::now();
        for x in 0..10 {
            for z in 0..10 {
                let (cx, cz) = (x * 7 - 300, z * 5 + 200);
                let biomes = mcrs_worldgen::surface::BiomeArea { chunk_x: cx, chunk_z: cz, min_quart_y: min_y >> 2, quart_height, ids: ids.clone() };
                let input = mcrs_worldgen::surface::SurfaceInput {
                    biomes: &biomes,
                    gen: mcrs_worldgen::surface::GenContext { min_gen_y: min_y, gen_depth: height, sea_level: generator.sea_level },
                    gen_min_y: min_y,
                    gen_max_y: min_y + height - 1,
                    default_block: generator.default_block,
                    plain_air: generator.air,
                    sea_level: generator.sea_level,
                    noise_volume: mcrs_worldgen::noise::DensityVolume::new([16, height, 16], [cx << 4, min_y, cz << 4], [1, 1, 1]),
                };
                let p = mcrs_worldgen::fill::FillParams { chunk_min_y: min_y, chunk_height: height, surface: Some(input) };
                generator.fill(cx, cz, min_y, height, Default::default(), &p);
            }
        }
        println!("fill + surface: {:.2} ms/chunk", start.elapsed().as_secs_f64() * 10.0);
        let mut total = 0u128;
        for x in 0..5 {
            for z in 0..4 {
                let (cx, cz) = (x * 7 - 300, z * 5 + 200);
                let biomes = mcrs_worldgen::surface::BiomeArea { chunk_x: cx, chunk_z: cz, min_quart_y: min_y >> 2, quart_height, ids: ids.clone() };
                let input = mcrs_worldgen::surface::SurfaceInput {
                    biomes: &biomes,
                    gen: mcrs_worldgen::surface::GenContext { min_gen_y: min_y, gen_depth: height, sea_level: generator.sea_level },
                    gen_min_y: min_y,
                    gen_max_y: min_y + height - 1,
                    default_block: generator.default_block,
                    plain_air: generator.air,
                    sea_level: generator.sea_level,
                    noise_volume: mcrs_worldgen::noise::DensityVolume::new([16, height, 16], [cx << 4, min_y, cz << 4], [1, 1, 1]),
                };
                total += generator.bench_surface(cx, cz, min_y, height, &input, 7);
            }
        }
        println!("surface only (min of 7 runs): {:.3} ms/chunk", total as f64 / 20.0 / 1e6);
    }
    let start = Instant::now();
    let mut sink = 0.0;
    for x in 0..10 {
        for z in 0..10 {
            sink += generator.bench_density(x * 7 - 300, z * 5 + 200, min_y, height, &mut None);
        }
    }
    println!("density only: {:.2} ms/chunk ({sink})", start.elapsed().as_secs_f64() * 10.0);

    let mut profile = Some(vec![(0u64, 0u64); generator.graph.nodes.len()]);
    for x in 0..10 {
        generator.bench_density(x * 7 - 300, 200, min_y, height, &mut profile);
    }
    let profile = profile.unwrap();
    let mut order: Vec<usize> = (0..profile.len()).collect();
    order.sort_by_key(|&i| std::cmp::Reverse(profile[i].0));
    // Leaf-like node kinds (they sample noise directly): their inclusive time is their own.
    let mut kinds: std::collections::BTreeMap<String, (f64, u64)> = Default::default();
    for (i, node) in generator.graph.nodes.iter().enumerate() {
        let name = format!("{:?}", node);
        let kind: String = name.chars().take_while(|c| c.is_alphanumeric()).collect();
        if kind.contains("Noise") || kind == "Beardifier" || kind == "YClampedGradient" || kind == "EndIslands" || kind == "WeirdScaledSampler" {
            let e = kinds.entry(kind).or_default();
            e.0 += profile[i].0 as f64 / 1e6;
            e.1 += profile[i].1;
        }
    }
    println!("leaf node kinds (10 chunks): {kinds:?}");
    println!("top nodes by inclusive time (10 chunks):");
    for &i in order.iter().take(25) {
        let node = format!("{:?}", generator.graph.nodes[i]);
        println!("{:4} {:8.2} ms {:9} pts  {}", i, profile[i].0 as f64 / 1e6, profile[i].1, &node[..node.len().min(110)]);
    }
}
