//! Sampling profile of fill + surface, without external tools or admin rights: a thread
//! suspends the worker every ~0.5 ms, records its instruction pointer, and the samples are
//! resolved (including inlined frames) at the end. Needs line tables in the release build:
//!
//!     CARGO_PROFILE_RELEASE_DEBUG=line-tables-only cargo run --release -p mcrs-worldgen \
//!         --example fill_profile -- run/mcrs-worldgen-0.json [seconds] [fills.bin]
//!
//! With `fills.bin` (recorded by the server with `MCRS_DUMP_FILLS=fills.bin`) it replays
//! real fill requests, biomes included; otherwise it uses a synthetic biome layout.

#[cfg(not(windows))]
fn main() {
    eprintln!("fill_profile samples threads with Win32 APIs; on other systems use perf.");
}

#[cfg(windows)]
fn main() {
    use std::collections::HashMap;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::{Arc, Mutex};
    use std::time::{Duration, Instant};

    use mcrs_worldgen::fill::{FillParams, Generator};
    use mcrs_worldgen::noise::DensityVolume;
    use mcrs_worldgen::surface::{BiomeArea, GenContext, SurfaceInput};
    use windows_sys::Win32::Foundation::{CloseHandle, DuplicateHandle, DUPLICATE_SAME_ACCESS, HANDLE};
    use windows_sys::Win32::System::Diagnostics::Debug::{GetThreadContext, CONTEXT, CONTEXT_CONTROL_AMD64};
    use windows_sys::Win32::System::Threading::{GetCurrentProcess, GetCurrentThread, ResumeThread, SuspendThread};

    let mut args = std::env::args().skip(1);
    let path = args.next().expect("usage: fill_profile <export.json> [seconds]");
    let seconds: f64 = args.next().map_or(10.0, |s| s.parse().unwrap());
    let recorded: Vec<(i32, i32, i32, i32, Vec<i32>)> = match args.next() {
        Some(file) => {
            let bytes = std::fs::read(file).unwrap();
            let ints: Vec<i32> = bytes.chunks_exact(4).map(|c| i32::from_le_bytes(c.try_into().unwrap())).collect();
            let mut out = Vec::new();
            let mut i = 0;
            while i + 5 <= ints.len() {
                let len = ints[i + 4] as usize;
                out.push((ints[i], ints[i + 1], ints[i + 2], ints[i + 3], ints[i + 5..i + 5 + len].to_vec()));
                i += 5 + len;
            }
            println!("replaying {} recorded fills", out.len());
            out
        }
        None => Vec::new(),
    };
    let json: serde_json::Value = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
    let generator = Generator::from_json(&json).unwrap();
    let (min_y, height) = (-64, 384);
    let quart_height = height / 4;
    let per_chunk = 16 * quart_height as usize;
    let ids: Vec<u32> = (0..9 * per_chunk).map(|i| ((i / per_chunk) * 7 % 67) as u32).collect();

    // A real handle to this thread for the sampler.
    let mut me: HANDLE = std::ptr::null_mut();
    unsafe {
        DuplicateHandle(GetCurrentProcess(), GetCurrentThread(), GetCurrentProcess(), &mut me, 0, 0, DUPLICATE_SAME_ACCESS);
    }
    let target = me as usize;
    static FAILURES: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let stop = Arc::new(AtomicBool::new(false));
    let samples = Arc::new(Mutex::new(Vec::<u64>::with_capacity(100_000)));
    let sampler = {
        let (stop, samples) = (stop.clone(), samples.clone());
        std::thread::spawn(move || {
            let handle = target as HANDLE;
            while !stop.load(Ordering::Relaxed) {
                std::thread::sleep(Duration::from_micros(500));
                unsafe {
                    if SuspendThread(handle) == u32::MAX {
                        continue;
                    }
                    // GetThreadContext needs a 16-byte aligned CONTEXT.
                    #[repr(C, align(16))]
                    struct Aligned(CONTEXT);
                    let mut context = Aligned(std::mem::zeroed());
                    context.0.ContextFlags = CONTEXT_CONTROL_AMD64;
                    if GetThreadContext(handle, &mut context.0) != 0 {
                        samples.lock().unwrap().push(context.0.Rip);
                    } else if FAILURES.fetch_add(1, Ordering::Relaxed) == 0 {
                        eprintln!("GetThreadContext failed: {}", std::io::Error::last_os_error());
                    }
                    ResumeThread(handle);
                }
            }
        })
    };

    let start = Instant::now();
    let mut chunks = 0u64;
    'outer: loop {
        for (cx, cz, fill_min_y, fill_height, p) in &recorded {
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
            let params = FillParams { chunk_min_y: p[0], chunk_height: p[1], surface: (p[2] == 1).then_some(input) };
            std::hint::black_box(generator.fill(*cx, *cz, *fill_min_y, *fill_height, Default::default(), &params));
            chunks += 1;
            if start.elapsed().as_secs_f64() > seconds {
                break 'outer;
            }
        }
        if !recorded.is_empty() {
            continue;
        }
        for x in 0..10 {
            for z in 0..10 {
                let (cx, cz) = (x * 7 - 300, z * 5 + 200);
                let biomes = BiomeArea { chunk_x: cx, chunk_z: cz, min_quart_y: min_y >> 2, quart_height, ids: ids.clone() };
                let input = SurfaceInput {
                    biomes: &biomes,
                    gen: GenContext { min_gen_y: min_y, gen_depth: height, sea_level: generator.sea_level },
                    gen_min_y: min_y,
                    gen_max_y: min_y + height - 1,
                    default_block: generator.default_block,
                    plain_air: generator.air,
                    sea_level: generator.sea_level,
                    noise_volume: DensityVolume::new([16, height, 16], [cx << 4, min_y, cz << 4], [1, 1, 1]),
                };
                let params = FillParams { chunk_min_y: min_y, chunk_height: height, surface: Some(input) };
                std::hint::black_box(generator.fill(cx, cz, min_y, height, Default::default(), &params));
                chunks += 1;
                if start.elapsed().as_secs_f64() > seconds {
                    break 'outer;
                }
            }
        }
    }
    stop.store(true, Ordering::Relaxed);
    sampler.join().unwrap();
    unsafe {
        CloseHandle(me);
    }
    let elapsed = start.elapsed().as_secs_f64();
    let samples = samples.lock().unwrap();
    println!("{chunks} chunks in {elapsed:.1} s = {:.2} ms/chunk, {} samples", elapsed * 1e3 / chunks as f64, samples.len());

    // Resolve each distinct address once; attribute to the innermost (possibly inlined)
    // function and line, and to the outermost function of that address.
    let mut by_ip: HashMap<u64, u64> = HashMap::new();
    for &ip in samples.iter() {
        *by_ip.entry(ip).or_default() += 1;
    }
    let mut inner: HashMap<String, u64> = HashMap::new();
    let mut outer: HashMap<String, u64> = HashMap::new();
    let mut lines: HashMap<String, u64> = HashMap::new();
    for (&ip, &count) in &by_ip {
        let mut frames: Vec<(String, String)> = Vec::new();
        backtrace::resolve(ip as *mut std::ffi::c_void, |symbol| {
            let name = symbol.name().map_or("?".to_string(), |n| format!("{n:#}"));
            let line = match (symbol.filename(), symbol.lineno()) {
                (Some(f), Some(l)) => format!("{}:{l}", f.file_name().unwrap_or_default().to_string_lossy()),
                _ => String::new(),
            };
            frames.push((name, line));
        });
        let first = frames.first().cloned().unwrap_or(("?".into(), String::new()));
        let last = frames.last().cloned().unwrap_or(("?".into(), String::new()));
        *inner.entry(first.0.clone()).or_default() += count;
        *outer.entry(last.0).or_default() += count;
        *lines.entry(format!("{} {}", first.1, first.0)).or_default() += count;
    }
    let total = samples.len().max(1) as f64;
    if let Ok(filter) = std::env::var("PROFILE_FILE") {
        // Lines of one source file, sorted by line number.
        let mut v: Vec<_> = lines.iter().filter(|(k, _)| k.starts_with(&filter)).collect();
        v.sort_by_key(|(k, _)| k.split([' ', ':']).nth(1).and_then(|n| n.parse::<u32>().ok()).unwrap_or(0));
        println!("--- lines of {filter}");
        for (name, count) in v {
            if 100.0 * *count as f64 / total >= 0.2 {
                println!("{:6.2}% {}", 100.0 * *count as f64 / total, &name[..name.len().min(110)]);
            }
        }
    }
    for (title, map, n) in [("innermost function", &inner, 30), ("outermost (non-inlined) function", &outer, 25), ("innermost line", &lines, 30)] {
        println!("--- {title}");
        let mut v: Vec<_> = map.iter().collect();
        v.sort_by(|a, b| b.1.cmp(a.1));
        for (name, count) in v.into_iter().take(n) {
            println!("{:6.2}% {}", 100.0 * *count as f64 / total, &name[..name.len().min(150)]);
        }
    }
}
