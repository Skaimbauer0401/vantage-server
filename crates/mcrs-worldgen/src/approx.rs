//! Approximate generation for the performance edition: terrain shaped like vanilla's, but
//! not block for block (like Pumpkin). Off by default; set before building a generator.
//!
//! Level 0 is exact. Higher levels drop noise octaves whose share of a noise stack's total
//! amplitude is small (`octave_threshold`), so fewer octaves are sampled per point.

use std::sync::atomic::{AtomicU8, Ordering};

static LEVEL: AtomicU8 = AtomicU8::new(0);

pub fn set_level(level: u8) {
    LEVEL.store(level, Ordering::Relaxed);
}

thread_local! {
    static OVERRIDE: std::cell::Cell<Option<u8>> = const { std::cell::Cell::new(None) };
}

pub fn level() -> u8 {
    OVERRIDE.with(|o| o.get()).unwrap_or_else(|| LEVEL.load(Ordering::Relaxed))
}

/// Runs `f` with this thread's level set to `level` (e.g. 0 to build an exact copy).
pub fn with_level<T>(level: u8, f: impl FnOnce() -> T) -> T {
    let before = OVERRIDE.with(|o| o.replace(Some(level)));
    let result = f();
    OVERRIDE.with(|o| o.set(before));
    result
}

/// Octaves (stack layers) below this share of the stack's summed amplitude are dropped.
pub fn octave_threshold() -> f32 {
    if level() >= 1 {
        0.02
    } else {
        0.0
    }
}

/// Factors for the interpolation cell size (vertical, horizontal): coarser cells sample
/// the density functions at fewer points.
pub fn cell_scale() -> (i32, i32) {
    match level() {
        0 | 1 => (1, 1),
        2 => (2, 1),
        _ => (2, 2),
    }
}
