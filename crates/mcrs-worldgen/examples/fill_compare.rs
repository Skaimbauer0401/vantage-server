//! Exact vs approximate fill (`mcrs_worldgen::approx`) on recorded fills: speed and how
//! far the terrain moves.
//!
//!     cargo run --release -p mcrs-worldgen --example fill_compare -- <export.json> <fills.bin> [level]

use std::time::Instant;

use mcrs_worldgen::fill::{FillOut, FillParams, Generator};
use mcrs_worldgen::noise::DensityVolume;
use mcrs_worldgen::surface::{BiomeArea, GenContext, SurfaceInput};

fn blocks(out: &FillOut) -> Vec<u32> {
    let mut all = Vec::new();
    for s in &out.sections {
        if s.palette.is_empty() {
            all.extend(std::iter::repeat(u32::MAX).take(4096));
            continue;
        }
        let bits = (usize::BITS - (s.palette.len() - 1).leading_zeros()).max(4) as usize;
        let per_long = 64 / bits;
        for i in 0..4096 {
            let word = s.data[i / per_long];
            let index = (word >> ((i % per_long) * bits)) & ((1 << bits) - 1);
            all.push(s.palette[index as usize]);
        }
    }
    all
}

fn main() {
    let mut args = std::env::args().skip(1);
    let path = args.next().expect("usage: fill_compare <export.json> <fills.bin> [level]");
    let fills = args.next().expect("fills.bin");
    let level: u8 = args.next().map_or(1, |v| v.parse().unwrap());
    let bytes = std::fs::read(fills).unwrap();
    let ints: Vec<i32> = bytes.chunks_exact(4).map(|c| i32::from_le_bytes(c.try_into().unwrap())).collect();
    let mut recorded = Vec::new();
    let mut i = 0;
    while i + 5 <= ints.len() {
        let len = ints[i + 4] as usize;
        recorded.push((ints[i], ints[i + 1], ints[i + 2], ints[i + 3], ints[i + 5..i + 5 + len].to_vec()));
        i += 5 + len;
    }
    let json: serde_json::Value = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
    mcrs_worldgen::approx::set_level(0);
    let exact = Generator::from_json(&json).unwrap();
    mcrs_worldgen::approx::set_level(level);
    let approx = Generator::from_json(&json).unwrap();

    let run = |g: &Generator, (cx, cz, fill_min_y, fill_height, p): &(i32, i32, i32, i32, Vec<i32>)| {
        let biomes = BiomeArea { chunk_x: *cx, chunk_z: *cz, min_quart_y: p[10], quart_height: p[11], ids: p[12..].iter().map(|v| *v as u32).collect() };
        let input = SurfaceInput {
            biomes: &biomes,
            gen: GenContext { min_gen_y: p[5], gen_depth: p[6], sea_level: p[7] },
            gen_min_y: p[3],
            gen_max_y: p[4],
            default_block: p[8] as u32,
            plain_air: p[9] as u32,
            sea_level: p[7],
            noise_volume: DensityVolume::new([16, *fill_height, 16], [cx << 4, *fill_min_y, cz << 4], [1, 1, 1]),
        };
        let params = FillParams { chunk_min_y: p[0], chunk_height: p[1], surface: (p[2] == 1).then_some(input), carving: None };
        g.fill(*cx, *cz, *fill_min_y, *fill_height, Default::default(), &params)
    };
    // Speed: each generator over all recorded fills, twice (the first round warms up).
    let mut times = [0.0f64; 2];
    for round in 0..2 {
        for (k, g) in [&exact, &approx].into_iter().enumerate() {
            let start = Instant::now();
            for r in &recorded {
                std::hint::black_box(run(g, r));
            }
            if round == 1 {
                times[k] = start.elapsed().as_secs_f64() * 1e3 / recorded.len() as f64;
                let p = Generator::take_phase_nanos();
                let n = recorded.len() as f64 * 1e6;
                println!(
                    "{}: density {:.2}, blocks+aquifer {:.2}, surface {:.2} (vein prefill {:.2})",
                    if k == 0 { "exact" } else { "approximate" },
                    p[1] as f64 / n, p[2] as f64 / n, p[3] as f64 / n,
                    mcrs_worldgen::surface::PREFILL_NANOS.swap(0, std::sync::atomic::Ordering::Relaxed) as f64 / n
                );
            } else {
                Generator::take_phase_nanos();
                mcrs_worldgen::surface::PREFILL_NANOS.swap(0, std::sync::atomic::Ordering::Relaxed);
            }
        }
    }
    // Difference: surface heights and blocks.
    let (mut columns, mut same_height, mut sum_dh, mut max_dh) = (0u64, 0u64, 0u64, 0i32);
    let (mut total_blocks, mut same_blocks, mut same_top) = (0u64, 0u64, 0u64);
    for r in &recorded {
        let (a, b) = (run(&exact, r), run(&approx, r));
        for c in 0..256 {
            let ha = a.world_surface[c].map_or(i32::MIN, |v| v.0);
            let hb = b.world_surface[c].map_or(i32::MIN, |v| v.0);
            columns += 1;
            if ha == hb {
                same_height += 1;
            }
            if a.world_surface[c].map(|v| v.1) == b.world_surface[c].map(|v| v.1) {
                same_top += 1;
            }
            let dh = (ha - hb).abs().min(1000);
            sum_dh += dh as u64;
            max_dh = max_dh.max(dh);
        }
        let (ba, bb) = (blocks(&a), blocks(&b));
        total_blocks += ba.len() as u64;
        same_blocks += ba.iter().zip(&bb).filter(|(x, y)| x == y).count() as u64;
    }
    println!("level {level}: exact {:.2} ms/chunk, approximate {:.2} ms/chunk ({:.2}x)", times[0], times[1], times[0] / times[1]);
    println!(
        "surface height equal in {:.1}% of columns, mean |dh| {:.2}, max {}; top block equal {:.1}%; blocks equal {:.2}%",
        100.0 * same_height as f64 / columns as f64,
        sum_dh as f64 / columns as f64,
        max_dh,
        100.0 * same_top as f64 / columns as f64,
        100.0 * same_blocks as f64 / total_blocks as f64
    );
}
