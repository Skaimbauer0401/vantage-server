//! Chunk loading entry points called from Java (`ChunkIo.java`) on worker threads.

use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, RwLock};

use mcrs_chunk::palette::{transform_chunk, PaletteRegistry};
use mcrs_chunk::RegionFile;

/// `read_chunk` results. Negative values make Java fall back to vanilla's reader.
const ABSENT: i32 = 0;
const FOUND: i32 = 1;
const ERR_NO_REGISTRY: i32 = -1;
const ERR_READ: i32 = -2;
const ERR_PANIC: i32 = -3;

/// `read_chunk` flag: keep the original palette for parity checking.
const FLAG_KEEP_ORIGINAL: i32 = 1;

/// Open region files are kept until this many are cached, then all are dropped.
const MAX_OPEN_REGIONS: usize = 512;

static REGISTRY: RwLock<Option<Arc<PaletteRegistry>>> = RwLock::new(None);
static REGIONS: RwLock<Option<HashMap<PathBuf, Arc<RegionFile>>>> = RwLock::new(None);

static CHUNKS_READ: AtomicU64 = AtomicU64::new(0);
static CHUNKS_ABSENT: AtomicU64 = AtomicU64::new(0);
static CHUNKS_FAILED: AtomicU64 = AtomicU64::new(0);
static CONTAINERS_RESOLVED: AtomicU64 = AtomicU64::new(0);
static CONTAINERS_SKIPPED: AtomicU64 = AtomicU64::new(0);

unsafe fn bytes<'a>(ptr: *const u8, len: usize) -> &'a [u8] {
    if len == 0 {
        &[]
    } else {
        std::slice::from_raw_parts(ptr, len)
    }
}

/// `void set_registry(int dataVersion, u8* blocks, size_t, u8* biomes, size_t)`.
/// Tables are `<id>\t<key>\n` lines, see [`PaletteRegistry`].
pub extern "C" fn set_registry(
    data_version: i32,
    blocks: *const u8,
    blocks_len: usize,
    biomes: *const u8,
    biomes_len: usize,
) {
    let blocks = String::from_utf8_lossy(unsafe { bytes(blocks, blocks_len) });
    let biomes = String::from_utf8_lossy(unsafe { bytes(biomes, biomes_len) });
    // For offline tools (e.g. `mcrs-chunk` examples) that need the server's ids.
    if let Some(dir) = std::env::var_os("MCRS_DUMP_REGISTRY") {
        let dir = Path::new(&dir);
        let _ = std::fs::write(dir.join("block_states.tsv"), blocks.as_bytes());
        let _ = std::fs::write(dir.join("biomes.tsv"), biomes.as_bytes());
        let _ = std::fs::write(dir.join("data_version.txt"), data_version.to_string());
    }
    let registry = PaletteRegistry {
        data_version,
        block_states: PaletteRegistry::parse_table(&blocks),
        biomes: PaletteRegistry::parse_table(&biomes),
    };
    tracing::info!(
        target: "mcrs::chunk_io",
        "registry: {} block states, {} biomes, data version {data_version}",
        registry.block_states.len(),
        registry.biomes.len()
    );
    *REGISTRY.write().unwrap() = Some(Arc::new(registry));
}

fn region(dir: &Path, chunk_x: i32, chunk_z: i32) -> std::io::Result<Option<Arc<RegionFile>>> {
    let path = dir.join(format!("r.{}.{}.mca", chunk_x >> 5, chunk_z >> 5));
    if let Some(region) = REGIONS.read().unwrap().as_ref().and_then(|m| m.get(&path)) {
        return Ok(Some(region.clone()));
    }
    // Absence is not cached: the server creates region files as it saves.
    let region = match RegionFile::open(&path) {
        Ok(r) => Arc::new(r),
        Err(mcrs_chunk::Error::Io(e)) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(std::io::Error::other(e)),
    };
    let mut regions = REGIONS.write().unwrap();
    let map = regions.get_or_insert_with(HashMap::new);
    if map.len() >= MAX_OPEN_REGIONS {
        map.clear();
    }
    Ok(Some(map.entry(path).or_insert(region).clone()))
}

/// `int read_chunk(u8* regionDir, size_t, int chunkX, int chunkZ, int flags, u8** out, size_t* outLen)`
///
/// Reads, decompresses and palette-transforms one chunk. On [`FOUND`], `*out` holds
/// uncompressed NBT that must be released with [`free_buffer`].
pub extern "C" fn read_chunk(
    dir: *const u8,
    dir_len: usize,
    chunk_x: i32,
    chunk_z: i32,
    flags: i32,
    out: *mut *mut u8,
    out_len: *mut usize,
) -> i32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let Some(registry) = REGISTRY.read().unwrap().clone() else {
            return ERR_NO_REGISTRY;
        };
        let dir = String::from_utf8_lossy(unsafe { bytes(dir, dir_len) }).into_owned();
        match read_and_transform(Path::new(&dir), chunk_x, chunk_z, flags, &registry) {
            Ok(Some(data)) => {
                let data = data.into_boxed_slice();
                unsafe {
                    *out_len = data.len();
                    *out = Box::into_raw(data) as *mut u8;
                }
                CHUNKS_READ.fetch_add(1, Ordering::Relaxed);
                FOUND
            }
            Ok(None) => {
                CHUNKS_ABSENT.fetch_add(1, Ordering::Relaxed);
                ABSENT
            }
            Err(e) => {
                CHUNKS_FAILED.fetch_add(1, Ordering::Relaxed);
                tracing::debug!(
                    target: "mcrs::chunk_io",
                    "chunk {chunk_x} {chunk_z} in {dir}: {e}; vanilla will read it"
                );
                ERR_READ
            }
        }
    }));
    result.unwrap_or(ERR_PANIC)
}

fn read_and_transform(
    dir: &Path,
    chunk_x: i32,
    chunk_z: i32,
    flags: i32,
    registry: &PaletteRegistry,
) -> mcrs_chunk::Result<Option<Vec<u8>>> {
    let Some(region) = region(dir, chunk_x, chunk_z)? else {
        return Ok(None);
    };
    let Some(nbt) = region.read((chunk_x & 31) as usize, (chunk_z & 31) as usize)? else {
        return Ok(None);
    };
    let (transformed, stats) = transform_chunk(&nbt, registry, flags & FLAG_KEEP_ORIGINAL != 0)?;
    CONTAINERS_RESOLVED.fetch_add(stats.containers_resolved as u64, Ordering::Relaxed);
    CONTAINERS_SKIPPED.fetch_add(stats.containers_skipped as u64, Ordering::Relaxed);
    Ok(Some(transformed.unwrap_or(nbt)))
}

/// `void free_buffer(u8* ptr, size_t len)` for buffers returned by [`read_chunk`].
pub extern "C" fn free_buffer(ptr: *mut u8, len: usize) {
    if !ptr.is_null() {
        drop(unsafe { Box::from_raw(std::ptr::slice_from_raw_parts_mut(ptr, len)) });
    }
}

/// Logs chunk I/O counters accumulated since the last call (no-op when idle).
pub fn report_stats() {
    let read = CHUNKS_READ.swap(0, Ordering::Relaxed);
    let absent = CHUNKS_ABSENT.swap(0, Ordering::Relaxed);
    let failed = CHUNKS_FAILED.swap(0, Ordering::Relaxed);
    let resolved = CONTAINERS_RESOLVED.swap(0, Ordering::Relaxed);
    let skipped = CONTAINERS_SKIPPED.swap(0, Ordering::Relaxed);
    if read + absent + failed > 0 {
        tracing::info!(
            target: "mcrs::chunk_io",
            "chunks read {read}, absent {absent}, fell back {failed} | palettes resolved {resolved}, left to vanilla {skipped}"
        );
    }
}
