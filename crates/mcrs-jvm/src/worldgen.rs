//! Terrain filling entry points called from Java (`Worldgen.java`).
//!
//! `wg_fill` returns a little-endian buffer (layout in `write_fill`) that Java reads
//! with a `MemorySegment` and releases with `free_buffer`.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, RwLock};
use std::time::Instant;

use mcrs_worldgen::density::{Beard, Rigid, TerrainAdjustment};
use mcrs_worldgen::fill::{FillOut, FillParams, Generator};
use mcrs_worldgen::noise::DensityVolume;
use mcrs_worldgen::surface::{BiomeArea, GenContext, SurfaceInput};

static GENERATORS: RwLock<Vec<Arc<Generator>>> = RwLock::new(Vec::new());

static CHUNKS: AtomicU64 = AtomicU64::new(0);
static NANOS: AtomicU64 = AtomicU64::new(0);
static STRUCTURE_CHUNKS: AtomicU64 = AtomicU64::new(0);

/// `long wg_load(u8* json, size_t len)`: 0 if the export can't be handled.
pub extern "C" fn wg_load(json: *const u8, len: usize) -> i64 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let bytes = unsafe { std::slice::from_raw_parts(json, len) };
        let value: serde_json::Value = match serde_json::from_slice(bytes) {
            Ok(v) => v,
            Err(e) => {
                tracing::warn!(target: "mcrs::worldgen", "bad export: {e}");
                return 0;
            }
        };
        match Generator::from_json(&value) {
            Ok(generator) => {
                let mut generators = GENERATORS.write().unwrap();
                generators.push(Arc::new(generator));
                tracing::info!(
                    target: "mcrs::worldgen",
                    "terrain generator loaded: {} samplers, {} noises",
                    generators.last().unwrap().graph.nodes.len(),
                    generators.last().unwrap().graph.noises.len()
                );
                generators.len() as i64
            }
            Err(e) => {
                tracing::warn!(target: "mcrs::worldgen", "terrain stays on vanilla: {e}");
                0
            }
        }
    }));
    result.unwrap_or(0)
}

/// Decodes the Java side's beardifier ints: `hasBox, box[6], pieceCount,
/// pieces[8 each: min xyz, max xyz, adjustment, groundLevelDelta], junctionCount, junctions[3 each]`.
fn parse_beard(ints: &[i32]) -> Option<Beard> {
    let mut it = ints.iter().copied();
    let mut next = || it.next();
    let mut beard = Beard::default();
    let has_box = next()?;
    let b = [next()?, next()?, next()?, next()?, next()?, next()?];
    if has_box == 1 {
        beard.affected = Some(([b[0], b[1], b[2]], [b[3], b[4], b[5]]));
    }
    for _ in 0..next()? {
        let min = [next()?, next()?, next()?];
        let max = [next()?, next()?, next()?];
        let adjustment = match next()? {
            0 => TerrainAdjustment::None,
            1 => TerrainAdjustment::Bury,
            2 => TerrainAdjustment::BeardThin,
            3 => TerrainAdjustment::BeardBox,
            4 => TerrainAdjustment::Encapsulate,
            _ => return None,
        };
        beard.pieces.push(Rigid { min, max, adjustment, ground_level_delta: next()? });
    }
    for _ in 0..next()? {
        beard.junctions.push([next()?, next()?, next()?]);
    }
    Some(beard)
}

/// `int wg_fill(long handle, int chunkX, int chunkZ, int minY, int height,
///     i32* beard, size_t beardLen, i32* params, size_t paramsLen, u8** out, size_t* outLen)`
///
/// `params`: chunkMinY, chunkHeight, doSurface, genMinY, genMaxY, minGenY, genDepth,
/// seaLevel, defaultBlock, plainAir, biomeMinQuartY, biomeQuartHeight, then the noise
/// biomes of the 3x3 chunks (`[chunk][quartY][quartZ][quartX]`).
#[allow(clippy::too_many_arguments)]
pub extern "C" fn wg_fill(
    handle: i64,
    chunk_x: i32,
    chunk_z: i32,
    min_y: i32,
    height: i32,
    beard: *const i32,
    beard_len: usize,
    params: *const i32,
    params_len: usize,
    out: *mut *mut u8,
    out_len: *mut usize,
) -> i32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let generator = {
            let generators = GENERATORS.read().unwrap();
            match generators.get((handle - 1) as usize) {
                Some(g) => g.clone(),
                None => return -1,
            }
        };
        let beard_ints = if beard_len == 0 { &[][..] } else { unsafe { std::slice::from_raw_parts(beard, beard_len) } };
        let Some(beard) = parse_beard(beard_ints) else {
            return -3;
        };
        if beard.affected.is_some() {
            STRUCTURE_CHUNKS.fetch_add(1, Ordering::Relaxed);
        }
        let p = if params_len == 0 { &[][..] } else { unsafe { std::slice::from_raw_parts(params, params_len) } };
        if p.len() < 12 {
            return -4;
        }
        let biomes = BiomeArea { chunk_x, chunk_z, min_quart_y: p[10], quart_height: p[11], ids: p[12..].iter().map(|v| *v as u32).collect() };
        let surface = (p[2] == 1 && biomes.ids.len() == 9 * 16 * p[11] as usize).then(|| SurfaceInput {
            biomes: &biomes,
            gen: GenContext { min_gen_y: p[5], gen_depth: p[6], sea_level: p[7] },
            gen_min_y: p[3],
            gen_max_y: p[4],
            default_block: p[8] as u32,
            plain_air: p[9] as u32,
            sea_level: p[7],
            noise_volume: DensityVolume::new([16, height, 16], [chunk_x << 4, min_y, chunk_z << 4], [1, 1, 1]),
        });
        if handle == 1 && beard.affected.is_none() {
            dump_fill(chunk_x, chunk_z, min_y, height, p);
        }
        let fill_params = FillParams { chunk_min_y: p[0], chunk_height: p[1], surface };
        let start = Instant::now();
        let filled = generator.fill(chunk_x, chunk_z, min_y, height, beard, &fill_params);
        NANOS.fetch_add(start.elapsed().as_nanos() as u64, Ordering::Relaxed);
        CHUNKS.fetch_add(1, Ordering::Relaxed);
        let bytes = write_fill(&filled).into_boxed_slice();
        mcrs_worldgen::density::recycle(filled.cache.into_iter().map(|c| c.buffer));
        unsafe {
            *out_len = bytes.len();
            *out = Box::into_raw(bytes) as *mut u8;
        }
        1
    }));
    match result {
        Ok(code) => code,
        Err(_) => {
            tracing::error!(target: "mcrs::worldgen", "panic filling chunk {chunk_x} {chunk_z}; vanilla will fill it");
            -2
        }
    }
}

struct Writer(Vec<u8>);

impl Writer {
    fn i32(&mut self, v: i32) {
        self.0.extend_from_slice(&v.to_le_bytes());
    }
    fn i64(&mut self, v: i64) {
        self.0.extend_from_slice(&v.to_le_bytes());
    }
    fn f32(&mut self, v: f32) {
        self.0.extend_from_slice(&v.to_bits().to_le_bytes());
    }
}

/// Layout (little-endian):
/// ```text
/// i32 surfaceDone, i32 sections (all chunk sections); per section: i32 paletteLen, i32[paletteLen] ids,
///     if paletteLen > 0: i32[4] counts, i32 longCount, i64[longCount]
/// i32[256] oceanFloorY (MIN = none), i32[256] oceanFloorState,
/// i32[256] worldSurfaceY, i32[256] worldSurfaceState
/// i32 postCount, i32[3 * postCount]
/// i32 cells; per cell: i32 hasVolume, if 1: i32[9] volume, i32 len, f32[len];
///     i64 valueKey, f32 value
/// i32 hasAquifer; if 1: i32 n, n * (i32 has, i32 level, i32 fluid);
///     i32 n, n * (i32 has, i32 x, i32 y, i32 z); i32 n, n * (i32 x, i32 z, i32 level);
///     i32 shouldSchedule
/// ```
fn write_fill(f: &FillOut) -> Vec<u8> {
    let mut w = Writer(Vec::with_capacity(64 * 1024));
    w.i32(f.surface_done as i32);
    w.i32(f.sections.len() as i32);
    for s in &f.sections {
        w.i32(s.palette.len() as i32);
        for id in &s.palette {
            w.i32(*id as i32);
        }
        if !s.palette.is_empty() {
            for c in s.counts {
                w.i32(c);
            }
            w.i32(s.data.len() as i32);
            for l in &s.data {
                w.i64(*l as i64);
            }
        }
    }
    for map in [&f.ocean_floor, &f.world_surface] {
        for column in map.iter() {
            w.i32(column.map_or(i32::MIN, |c| c.0));
        }
        for column in map.iter() {
            w.i32(column.map_or(0, |c| c.1 as i32));
        }
    }
    w.i32(f.post_processing.len() as i32);
    for (x, y, z) in &f.post_processing {
        w.i32(*x);
        w.i32(*y);
        w.i32(*z);
    }
    w.i32(f.cache.len() as i32);
    for cell in &f.cache {
        match cell.volume {
            Some(v) => {
                w.i32(1);
                for p in [v.size_x, v.size_y, v.size_z, v.min_x, v.min_y, v.min_z, v.step_x, v.step_y, v.step_z] {
                    w.i32(p);
                }
                w.i32(cell.buffer.len() as i32);
                for value in &cell.buffer {
                    w.f32(*value);
                }
            }
            None => w.i32(0),
        }
        w.i64(cell.value_key);
        w.f32(cell.value);
    }
    match &f.aquifer {
        Some(a) => {
            w.i32(1);
            w.i32(a.status.len() as i32);
            for s in &a.status {
                match s {
                    Some(s) => {
                        w.i32(1);
                        w.i32(s.level);
                        w.i32(s.fluid as i32);
                    }
                    None => {
                        w.i32(0);
                        w.i32(0);
                        w.i32(0);
                    }
                }
            }
            w.i32(a.location.len() as i32);
            for l in &a.location {
                match l {
                    Some((x, y, z)) => {
                        w.i32(1);
                        w.i32(*x);
                        w.i32(*y);
                        w.i32(*z);
                    }
                    None => {
                        w.i32(0);
                        w.i32(0);
                        w.i32(0);
                        w.i32(0);
                    }
                }
            }
            w.i32(a.surface_levels.len() as i32);
            for ((x, z), level) in &a.surface_levels {
                w.i32(*x);
                w.i32(*z);
                w.i32(*level);
            }
            w.i32(a.should_schedule as i32);
        }
        None => w.i32(0),
    }
    w.0
}

/// Logs terrain fill counters accumulated since the last call.
pub fn report_stats() {
    let chunks = CHUNKS.swap(0, Ordering::Relaxed);
    let nanos = NANOS.swap(0, Ordering::Relaxed);
    let structures = STRUCTURE_CHUNKS.swap(0, Ordering::Relaxed);
    if chunks > 0 {
        tracing::info!(
            target: "mcrs::worldgen",
            "filled {chunks} chunks in Rust ({structures} with structure terrain adaptation), {:.2} ms each (per thread)",
            nanos as f64 / chunks as f64 / 1e6
        );
        let p = mcrs_worldgen::fill::Generator::take_phase_nanos();
        let ms = |v: u64| v as f64 / chunks as f64 / 1e6;
        tracing::info!(
            target: "mcrs::worldgen",
            "fill phases ms/chunk: aquifer setup {:.2}, density {:.2}, blocks+aquifer {:.2}, surface {:.2}, output {:.2}",
            ms(p[0]), ms(p[1]), ms(p[2]), ms(p[3]), ms(p[4])
        );
    }
}

/// `MCRS_DUMP_FILLS=<file>`: append the first 400 overworld fill requests without
/// structures (chunk x, z, min y, height, params length, params; i32 little endian) for
/// offline profiling with `examples/fill_profile.rs`.
fn dump_fill(chunk_x: i32, chunk_z: i32, min_y: i32, height: i32, params: &[i32]) {
    use std::io::Write;
    use std::sync::{Mutex, OnceLock};
    static DUMP: OnceLock<Option<Mutex<(std::fs::File, u32)>>> = OnceLock::new();
    let dump = DUMP.get_or_init(|| {
        let path = std::env::var_os("MCRS_DUMP_FILLS")?;
        Some(Mutex::new((std::fs::File::create(path).ok()?, 0)))
    });
    let Some(dump) = dump else { return };
    let mut guard = dump.lock().unwrap();
    if guard.1 >= 400 {
        return;
    }
    guard.1 += 1;
    let mut bytes = Vec::with_capacity((5 + params.len()) * 4);
    for v in [chunk_x, chunk_z, min_y, height, params.len() as i32].iter().chain(params) {
        bytes.extend_from_slice(&v.to_le_bytes());
    }
    let _ = guard.0.write_all(&bytes);
}
