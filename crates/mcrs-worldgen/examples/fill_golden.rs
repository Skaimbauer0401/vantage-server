//! Replays fill requests recorded by the server (`MCRS_DUMP_FILLS=fills.bin`) and either
//! records a hash of every output or checks the outputs against recorded hashes, then times
//! the replay single-threaded:
//!
//!     cargo run --release -p mcrs-worldgen --example fill_golden -- \
//!         run/mcrs-worldgen-0.json fills.bin golden.txt [record|check] [timing rounds]
//!
//! Record with a build verified against vanilla; any later optimization must check clean.
//! `FILL_THREADS=n` also times the replay on n threads at once.

use std::hash::{DefaultHasher, Hasher};
use std::time::Instant;

use mcrs_worldgen::fill::{FillOut, FillParams, Generator};
use mcrs_worldgen::noise::DensityVolume;
use mcrs_worldgen::surface::{BiomeArea, GenContext, SurfaceInput};

type Recorded = (i32, i32, i32, i32, Vec<i32>);

fn load(file: &str) -> Vec<Recorded> {
    let bytes = std::fs::read(file).unwrap();
    let ints: Vec<i32> = bytes.chunks_exact(4).map(|c| i32::from_le_bytes(c.try_into().unwrap())).collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i + 5 <= ints.len() {
        let len = ints[i + 4] as usize;
        out.push((ints[i], ints[i + 1], ints[i + 2], ints[i + 3], ints[i + 5..i + 5 + len].to_vec()));
        i += 5 + len;
    }
    out
}

fn run(generator: &Generator, r: &Recorded) -> FillOut {
    let (cx, cz, fill_min_y, fill_height, p) = r;
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
    generator.fill(*cx, *cz, *fill_min_y, *fill_height, Default::default(), &params)
}

fn hash(out: &mut FillOut) -> u64 {
    // Vanilla keeps these in a hash map; their order carries no meaning.
    if let Some(a) = out.aquifer.as_mut() {
        a.surface_levels.sort();
    }
    let mut h = DefaultHasher::new();
    h.write(format!("{:?}", out).as_bytes());
    h.finish()
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let json: serde_json::Value = serde_json::from_slice(&std::fs::read(&args[0]).unwrap()).unwrap();
    let generator = Generator::from_json(&json).unwrap();
    let recorded = load(&args[1]);
    let golden = &args[2];
    let mode = args.get(3).map_or("check", |s| s.as_str());
    let rounds: usize = args.get(4).map_or(3, |s| s.parse().unwrap());

    let hashes: Vec<u64> = recorded.iter().map(|r| hash(&mut run(&generator, r))).collect();
    if mode == "record" {
        let text: String = hashes.iter().map(|h| format!("{h:016x}\n")).collect();
        std::fs::write(golden, text).unwrap();
        println!("recorded {} hashes", hashes.len());
    } else {
        let expected: Vec<u64> = std::fs::read_to_string(golden).unwrap().lines().map(|l| u64::from_str_radix(l, 16).unwrap()).collect();
        let bad: Vec<usize> = (0..hashes.len()).filter(|&i| expected.get(i) != Some(&hashes[i])).collect();
        if bad.is_empty() && expected.len() == hashes.len() {
            println!("IDENTICAL: {} fills", hashes.len());
        } else {
            println!("DIFFERENT: {} of {} fills, first {:?}", bad.len(), hashes.len(), &bad[..bad.len().min(10)]);
        }
    }

    let mut best = f64::MAX;
    for _ in 0..rounds {
        let start = Instant::now();
        for r in &recorded {
            std::hint::black_box(run(&generator, r));
        }
        best = best.min(start.elapsed().as_secs_f64() * 1000.0 / recorded.len() as f64);
    }
    println!("best of {rounds}: {best:.3} ms/chunk");

    // The same replay on several threads at once (as the server's workers do).
    let threads: usize = std::env::var("FILL_THREADS").ok().map_or(0, |s| s.parse().unwrap());
    if threads > 0 {
        let start = Instant::now();
        std::thread::scope(|scope| {
            for t in 0..threads {
                let (generator, recorded) = (&generator, &recorded);
                scope.spawn(move || {
                    for i in 0..recorded.len() {
                        std::hint::black_box(run(generator, &recorded[(i + t * 37) % recorded.len()]));
                    }
                });
            }
        });
        let wall = start.elapsed().as_secs_f64() * 1000.0;
        println!("{threads} threads: {:.3} ms/chunk per thread, {:.0} chunks/s total", wall / recorded.len() as f64, (threads * recorded.len()) as f64 / wall * 1000.0);
    }
}
