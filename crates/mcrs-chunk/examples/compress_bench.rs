//! Compression speed on real chunk data (like chunk packets): zlib-rs and libdeflate at
//! level 6 (java.util.zip.Deflater's default). Also writes the samples for a Java comparison.
//!
//!     cargo run --release -p mcrs-chunk --example compress_bench -- <region dir> <samples out>

use std::io::Write;
use std::time::Instant;

fn main() {
    let mut args = std::env::args().skip(1);
    let dir = args.next().expect("region dir");
    let out = args.next().expect("samples file");
    let mut samples: Vec<Vec<u8>> = Vec::new();
    for entry in std::fs::read_dir(&dir).unwrap() {
        let path = entry.unwrap().path();
        if path.extension().and_then(|e| e.to_str()) != Some("mca") {
            continue;
        }
        let region = mcrs_chunk::region::RegionFile::open(&path).unwrap();
        let chunks: Vec<_> = region.chunks().collect();
        for (x, z) in chunks {
            if let Ok(Some((compression, data))) = region.read_raw(x, z) {
                if let Ok(raw) = compression.decompress(&data) {
                    samples.push(raw);
                }
            }
            if samples.len() >= 2000 {
                break;
            }
        }
        if samples.len() >= 2000 {
            break;
        }
    }
    let total: usize = samples.iter().map(Vec::len).sum();
    println!("{} chunks, {:.1} MB uncompressed", samples.len(), total as f64 / 1e6);
    let mut f = std::fs::File::create(&out).unwrap();
    for s in &samples {
        f.write_all(&(s.len() as u32).to_le_bytes()).unwrap();
        f.write_all(s).unwrap();
    }

    let start = Instant::now();
    let mut size = 0;
    for s in &samples {
        let mut e = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::new(6));
        e.write_all(s).unwrap();
        size += e.finish().unwrap().len();
    }
    let t = start.elapsed().as_secs_f64();
    println!("zlib-rs level 6:    {:.0} MB/s, ratio {:.3}", total as f64 / 1e6 / t, size as f64 / total as f64);

    for level in [6, 4, 1] {
        let mut c = libdeflater::Compressor::new(libdeflater::CompressionLvl::new(level).unwrap());
        let start = Instant::now();
        let mut size = 0;
        let mut buf = Vec::new();
        for s in &samples {
            buf.resize(c.zlib_compress_bound(s.len()), 0);
            size += c.zlib_compress(s, &mut buf).unwrap();
        }
        let t = start.elapsed().as_secs_f64();
        println!("libdeflate level {level}: {:.0} MB/s, ratio {:.3}", total as f64 / 1e6 / t, size as f64 / total as f64);
    }
}
