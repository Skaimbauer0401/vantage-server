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
    // Warm up, then measure a 10x10 area on one thread.
    for i in 0..10 {
        generator.fill(1000 + i, 1000, min_y, height);
    }
    let start = Instant::now();
    let mut blocks = 0usize;
    for x in 0..10 {
        for z in 0..10 {
            let out = generator.fill(x * 7 - 300, z * 5 + 200, min_y, height);
            blocks += out.sections.iter().filter(|s| !s.palette.is_empty()).count();
        }
    }
    let elapsed = start.elapsed();
    println!("100 chunks: {:.2} ms/chunk ({} non-empty sections)", elapsed.as_secs_f64() * 10.0, blocks);
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
    println!("top nodes by inclusive time (10 chunks):");
    for &i in order.iter().take(25) {
        let node = format!("{:?}", generator.graph.nodes[i]);
        println!("{:4} {:8.2} ms {:9} pts  {}", i, profile[i].0 as f64 / 1e6, profile[i].1, &node[..node.len().min(110)]);
    }
}
