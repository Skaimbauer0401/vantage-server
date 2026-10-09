//! Reads and decompresses every chunk of a world's region folder, serially and in
//! parallel, and reports throughput.
//!
//!     cargo run --release -p mcrs-chunk --example scan -- run/world/region

use std::time::Instant;

use mcrs_chunk::RegionFile;
use rayon::prelude::*;

fn main() {
    let dir = std::env::args().nth(1).expect("usage: scan <region dir>");
    let regions: Vec<RegionFile> = std::fs::read_dir(&dir)
        .expect("read region dir")
        .flatten()
        .filter(|e| e.file_name().to_string_lossy().ends_with(".mca"))
        .filter_map(|e| RegionFile::open(e.path()).ok())
        .collect();
    let jobs: Vec<(&RegionFile, usize, usize)> =
        regions.iter().flat_map(|r| r.chunks().map(move |(x, z)| (r, x, z))).collect();
    println!("{} regions, {} chunks", regions.len(), jobs.len());

    let read = |&(r, x, z): &(&RegionFile, usize, usize)| r.read(x, z).unwrap().unwrap().len();

    for (name, parallel) in [("serial", false), ("parallel", true)] {
        let start = Instant::now();
        let bytes: usize =
            if parallel { jobs.par_iter().map(read).sum() } else { jobs.iter().map(read).sum() };
        let secs = start.elapsed().as_secs_f64();
        println!(
            "{name:>8}: {:.0} chunks/s, {:.0} MB/s decompressed ({:.3} s, {} threads)",
            jobs.len() as f64 / secs,
            bytes as f64 / secs / 1e6,
            secs,
            if parallel { rayon::current_num_threads() } else { 1 }
        );
    }
}
