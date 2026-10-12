//! Native side of the Rust <-> Java bridge.
//!
//! Java gets the address of [`BRIDGE`] through the `mcrs.bridge` system property
//! and binds each function pointer with Panama FFM downcalls (see
//! `bridge-mod/.../NativeBridge.java`). The layout is ABI: append new functions
//! at the end and bump [`ABI_VERSION`] when changing anything else.

use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

pub const MAGIC: u64 = u64::from_le_bytes(*b"MCRSBRDG");
pub const ABI_VERSION: u32 = 8;

#[repr(C)]
pub struct BridgeTable {
    pub magic: u64,
    pub abi_version: u32,
    pub fn_count: u32,
    /// `void log(int level, const u8* utf8, size_t len)`; level 0=trace .. 4=error
    pub log: extern "C" fn(i32, *const u8, usize),
    /// `void on_tick(long nanos)`: duration of one `MinecraftServer.tickServer` call
    pub on_tick: extern "C" fn(i64),
    /// See [`crate::chunk_io::set_registry`].
    pub set_registry: extern "C" fn(i32, *const u8, usize, *const u8, usize, *const u8, usize),
    /// See [`crate::chunk_io::read_chunk`].
    pub read_chunk: extern "C" fn(*const u8, usize, i32, i32, i32, *mut *mut u8, *mut usize) -> i32,
    /// See [`crate::chunk_io::free_buffer`].
    pub free_buffer: extern "C" fn(*mut u8, usize),
    /// See [`crate::worldgen::wg_load`].
    pub wg_load: extern "C" fn(*const u8, usize) -> i64,
    /// See [`crate::worldgen::wg_fill`].
    pub wg_fill: extern "C" fn(i64, i32, i32, i32, i32, *const i32, usize, *const i32, usize, *mut *mut u8, *mut usize) -> i32,
    /// See [`crate::worldgen::wg_biomes`].
    pub wg_biomes: extern "C" fn(i64, i32, i32, i32, i32, *mut i32, usize) -> i32,
    /// See [`crate::compress::zlib_compress`].
    pub zlib_compress: extern "C" fn(i32, *const u8, usize, *mut u8, usize) -> i64,
    /// See [`crate::compress::zlib_bound`].
    pub zlib_bound: extern "C" fn(usize) -> usize,
}

pub static BRIDGE: BridgeTable = BridgeTable {
    magic: MAGIC,
    abi_version: ABI_VERSION,
    fn_count: 10,
    log: bridge_log,
    on_tick: bridge_on_tick,
    set_registry: crate::chunk_io::set_registry,
    read_chunk: crate::chunk_io::read_chunk,
    free_buffer: crate::chunk_io::free_buffer,
    wg_load: crate::worldgen::wg_load,
    wg_fill: crate::worldgen::wg_fill,
    wg_biomes: crate::worldgen::wg_biomes,
    zlib_compress: crate::compress::zlib_compress,
    zlib_bound: crate::compress::zlib_bound,
};

/// Address passed to Java as `-Dmcrs.bridge=<address>`.
pub fn table_address() -> usize {
    &BRIDGE as *const BridgeTable as usize
}

extern "C" fn bridge_log(level: i32, ptr: *const u8, len: usize) {
    let bytes = unsafe { std::slice::from_raw_parts(ptr, len) };
    let msg = String::from_utf8_lossy(bytes);
    match level {
        0 => tracing::trace!(target: "mcrs::java", "{msg}"),
        1 => tracing::debug!(target: "mcrs::java", "{msg}"),
        2 => tracing::info!(target: "mcrs::java", "{msg}"),
        3 => tracing::warn!(target: "mcrs::java", "{msg}"),
        _ => tracing::error!(target: "mcrs::java", "{msg}"),
    }
}

static TICK_COUNT: AtomicU64 = AtomicU64::new(0);
static TICK_NANOS_SUM: AtomicU64 = AtomicU64::new(0);
static TICK_NANOS_MAX: AtomicU64 = AtomicU64::new(0);

extern "C" fn bridge_on_tick(nanos: i64) {
    let nanos = nanos.max(0) as u64;
    TICK_COUNT.fetch_add(1, Ordering::Relaxed);
    TICK_NANOS_SUM.fetch_add(nanos, Ordering::Relaxed);
    TICK_NANOS_MAX.fetch_max(nanos, Ordering::Relaxed);
}

/// Logs MSPT/TPS every `interval` on a background thread.
pub fn spawn_tick_reporter(interval: Duration) {
    std::thread::Builder::new()
        .name("mcrs-tick-reporter".into())
        .spawn(move || loop {
            std::thread::sleep(interval);
            let count = TICK_COUNT.swap(0, Ordering::Relaxed);
            let sum = TICK_NANOS_SUM.swap(0, Ordering::Relaxed);
            let max = TICK_NANOS_MAX.swap(0, Ordering::Relaxed);
            if count == 0 {
                continue;
            }
            let avg_ms = sum as f64 / count as f64 / 1e6;
            let tps = count as f64 / interval.as_secs_f64();
            tracing::info!(
                target: "mcrs::ticks",
                "TPS {tps:.2} | MSPT avg {avg_ms:.2} max {:.2} | {count} ticks",
                max as f64 / 1e6
            );
            crate::chunk_io::report_stats();
            crate::worldgen::report_stats();
        })
        .expect("spawn tick reporter");
}
