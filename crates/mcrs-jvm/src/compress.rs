//! zlib compression with libdeflate (about 3.5x the speed of java.util.zip.Deflater at the
//! same level and ratio), for network packets and region files. The output is ordinary zlib
//! data, which every reader decompresses the same way; only the exact bytes may differ from
//! Java's.

use std::cell::RefCell;

use libdeflater::{CompressionLvl, Compressor};

thread_local! {
    /// One compressor per thread and level (they keep internal buffers).
    static COMPRESSORS: RefCell<Vec<(i32, Compressor)>> = const { RefCell::new(Vec::new()) };
}

/// `size_t zlib_bound(size_t len)`: the largest output `zlib_compress` can produce.
pub extern "C" fn zlib_bound(len: usize) -> usize {
    // zlib_compress_bound depends only on the length; any level gives the same bound.
    COMPRESSORS.with(|c| with_compressor(&mut c.borrow_mut(), 6, |compressor| compressor.zlib_compress_bound(len)))
}

/// `long zlib_compress(int level, u8* in, size_t in_len, u8* out, size_t out_cap)`: zlib
/// stream of `in` into `out`; bytes written, or -1 if it didn't fit.
pub extern "C" fn zlib_compress(level: i32, input: *const u8, input_len: usize, out: *mut u8, out_cap: usize) -> i64 {
    let input = if input_len == 0 { &[][..] } else { unsafe { std::slice::from_raw_parts(input, input_len) } };
    let out = unsafe { std::slice::from_raw_parts_mut(out, out_cap) };
    COMPRESSORS.with(|c| {
        with_compressor(&mut c.borrow_mut(), level, |compressor| match compressor.zlib_compress(input, out) {
            Ok(n) => n as i64,
            Err(_) => -1,
        })
    })
}

fn with_compressor<T>(compressors: &mut Vec<(i32, Compressor)>, level: i32, f: impl FnOnce(&mut Compressor) -> T) -> T {
    // java.util.zip.Deflater's default level is 6; libdeflate supports 0..=12.
    let level = if (0..=12).contains(&level) { level } else { 6 };
    let index = match compressors.iter().position(|(l, _)| *l == level) {
        Some(i) => i,
        None => {
            compressors.push((level, Compressor::new(CompressionLvl::new(level).unwrap_or_default())));
            compressors.len() - 1
        }
    };
    f(&mut compressors[index].1)
}
