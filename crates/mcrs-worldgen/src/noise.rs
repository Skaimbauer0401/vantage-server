//! `net.minecraft.world.level.levelgen.synth` (26.3), bit-exact.
//!
//! Noise values are `float` in 26.x; coordinates stay `double`. The scalar `get`
//! and the vectorized `add_to_volume` paths round differently in vanilla (dot
//! products are summed in a different order), so both are ported as written.

use std::sync::Arc;

use crate::jmath;
use crate::random::{ForkingRandom, PositionalRandomFactory, RandomSource};

/// `DensityVolume`: a regular grid of block positions, Y fastest, then X, then Z.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DensityVolume {
    pub size_x: i32,
    pub size_y: i32,
    pub size_z: i32,
    pub min_x: i32,
    pub min_y: i32,
    pub min_z: i32,
    pub step_x: i32,
    pub step_y: i32,
    pub step_z: i32,
}

impl DensityVolume {
    pub fn new(size: [i32; 3], min: [i32; 3], step: [i32; 3]) -> DensityVolume {
        assert!(size.iter().all(|&s| s > 0) && step.iter().all(|&s| s > 0));
        DensityVolume {
            size_x: size[0],
            size_y: size[1],
            size_z: size[2],
            min_x: min[0],
            min_y: min[1],
            min_z: min[2],
            step_x: step[0],
            step_y: step[1],
            step_z: step[2],
        }
    }

    pub fn block_x(&self, i: i32) -> i32 {
        self.min_x + i * self.step_x
    }

    pub fn block_y(&self, i: i32) -> i32 {
        self.min_y + i * self.step_y
    }

    pub fn block_z(&self, i: i32) -> i32 {
        self.min_z + i * self.step_z
    }

    pub fn size(&self) -> usize {
        (self.size_x * self.size_y * self.size_z) as usize
    }

    pub fn index_unchecked(&self, x: i32, y: i32, z: i32) -> usize {
        (y + (x + z * self.size_x) * self.size_y) as usize
    }

    pub fn max_block_x(&self) -> i32 {
        self.min_x + self.size_x * self.step_x - 1
    }

    pub fn max_block_y(&self) -> i32 {
        self.min_y + self.size_y * self.step_y - 1
    }

    pub fn max_block_z(&self) -> i32 {
        self.min_z + self.size_z * self.step_z - 1
    }

    /// `DensityVolume.indexOfBlock`: index of a block position, if it's a grid point.
    pub fn index_of_block(&self, x: i32, y: i32, z: i32) -> Option<usize> {
        let (rx, ry, rz) = (x - self.min_x, y - self.min_y, z - self.min_z);
        if self.step_x == 1 && self.step_y == 1 && self.step_z == 1 {
            if rx >= 0 && ry >= 0 && rz >= 0 && rx < self.size_x && ry < self.size_y && rz < self.size_z {
                return Some(self.index_unchecked(rx, ry, rz));
            }
        } else if rx >= 0
            && ry >= 0
            && rz >= 0
            && rx < self.size_x * self.step_x
            && ry < self.size_y * self.step_y
            && rz < self.size_z * self.step_z
            && rx.rem_euclid(self.step_x) == 0
            && ry.rem_euclid(self.step_y) == 0
            && rz.rem_euclid(self.step_z) == 0
        {
            return Some(self.index_unchecked(
                rx.div_euclid(self.step_x),
                ry.div_euclid(self.step_y),
                rz.div_euclid(self.step_z),
            ));
        }
        None
    }
}

/// `Noise`
pub trait Noise: Send + Sync {
    fn get2(&self, x: f64, y: f64) -> f32;
    fn get3(&self, x: f64, y: f64, z: f64) -> f32;

    /// Adds `amplitude * noise` for every point of `volume` to `buffer`.
    fn add_to_volume(&self, buffer: &mut [f32], volume: &DensityVolume, xz_scale: f64, y_scale: f64, amplitude: f32) {
        let mut index = 0;
        for iz in 0..volume.size_z {
            let z = volume.block_z(iz) as f64 * xz_scale;
            for ix in 0..volume.size_x {
                let x = volume.block_x(ix) as f64 * xz_scale;
                for iy in 0..volume.size_y {
                    let y = volume.block_y(iy) as f64 * y_scale;
                    buffer[index] += amplitude * self.get3(x, y, z);
                    index += 1;
                }
            }
        }
    }
}

const GRADIENT: [[i32; 3]; 16] = [
    [1, 1, 0],
    [-1, 1, 0],
    [1, -1, 0],
    [-1, -1, 0],
    [1, 0, 1],
    [-1, 0, 1],
    [1, 0, -1],
    [-1, 0, -1],
    [0, 1, 1],
    [0, -1, 1],
    [0, 1, -1],
    [0, -1, -1],
    [1, 1, 0],
    [0, -1, 1],
    [-1, 1, 0],
    [0, -1, -1],
];

#[inline]
fn grad_dot(hash: i32, x: f32, y: f32, z: f32) -> f32 {
    let g = GRADIENT[(hash & 15) as usize];
    g[0] as f32 * x + g[1] as f32 * y + g[2] as f32 * z
}

#[inline]
fn dot_xz(g: [i32; 3], x: f32, z: f32) -> f32 {
    g[0] as f32 * x + g[2] as f32 * z
}

/// `Mth.smoothstep(float)`
#[inline]
pub fn smoothstep(x: f32) -> f32 {
    x * x * x * (x * (x * 6.0 - 15.0) + 10.0)
}

/// `Mth.lerp(float, float, float)`
#[inline]
pub fn lerp(a: f32, p0: f32, p1: f32) -> f32 {
    p0 + a * (p1 - p0)
}

#[inline]
pub fn lerp2(a1: f32, a2: f32, x00: f32, x10: f32, x01: f32, x11: f32) -> f32 {
    lerp(a2, lerp(a1, x00, x10), lerp(a1, x01, x11))
}

#[allow(clippy::too_many_arguments)]
#[inline]
pub fn lerp3(
    a1: f32,
    a2: f32,
    a3: f32,
    x000: f32,
    x100: f32,
    x010: f32,
    x110: f32,
    x001: f32,
    x101: f32,
    x011: f32,
    x111: f32,
) -> f32 {
    lerp(a3, lerp2(a1, a2, x000, x100, x010, x110), lerp2(a1, a2, x001, x101, x011, x111))
}

/// `Mth.floor(double)`: Java's saturating `(int) Math.floor(v)`.
#[inline]
pub fn floor(v: f64) -> i32 {
    v.floor() as i32
}

/// `Math.nextDown(1.6777216E7)`
const HALF_ROUND_OFF: f64 = 16777215.999999998;
const ROUND_OFF: f64 = 3.3554432e7;

/// `GradientNoise.wrap`: keeps coordinates in a range where doubles stay precise.
#[inline]
pub fn wrap(x: f64) -> f64 {
    if (-HALF_ROUND_OFF..HALF_ROUND_OFF).contains(&x) {
        x
    } else {
        x - (x / ROUND_OFF + 0.5).floor() * ROUND_OFF
    }
}

/// `GradientNoise`: offsets and permutation table shared by Perlin and simplex noise.
#[derive(Debug, Clone)]
pub struct GradientBase {
    perms: [u8; 256],
    pub offset_x: f64,
    pub offset_y: f64,
    pub offset_z: f64,
}

impl GradientBase {
    /// From state exported by the server (permutation table and offsets).
    pub fn from_parts(perms: [u8; 256], offset_x: f64, offset_y: f64, offset_z: f64) -> GradientBase {
        GradientBase { perms, offset_x, offset_y, offset_z }
    }

    pub fn new(random: &mut impl RandomSource, offset_scale: f64) -> GradientBase {
        let offset_x = random.next_double() * offset_scale;
        let offset_y = random.next_double() * offset_scale;
        let offset_z = random.next_double() * offset_scale;
        let mut perms = [0u8; 256];
        for (i, p) in perms.iter_mut().enumerate() {
            *p = i as u8;
        }
        for i in 0..256 {
            let offset = random.next_int_bounded(256 - i as i32) as usize;
            perms.swap(i, offset + i);
        }
        GradientBase { perms, offset_x, offset_y, offset_z }
    }

    #[inline]
    fn permute(&self, x: i32) -> i32 {
        self.perms[(x & 0xFF) as usize] as i32
    }

    #[inline]
    fn permute_to_grad(&self, x: i32) -> [i32; 3] {
        GRADIENT[(self.permute(x) & 15) as usize]
    }
}

/// `PerlinNoise` (one octave of improved Perlin noise).
#[derive(Debug, Clone)]
pub struct PerlinNoise {
    pub base: GradientBase,
}

impl PerlinNoise {
    pub fn new(random: &mut impl RandomSource) -> PerlinNoise {
        PerlinNoise { base: GradientBase::new(random, 256.0) }
    }

    #[allow(clippy::too_many_arguments)]
    fn sample_and_lerp(&self, x: i32, y: i32, z: i32, rx: f32, ry: f32, rz: f32, original_ry: f32) -> f32 {
        let p = |v| self.base.permute(v);
        let x0 = p(x);
        let x1 = p(x + 1);
        let xy00 = p(x0 + y);
        let xy01 = p(x0 + y + 1);
        let xy10 = p(x1 + y);
        let xy11 = p(x1 + y + 1);
        let d000 = grad_dot(p(xy00 + z), rx, ry, rz);
        let d100 = grad_dot(p(xy10 + z), rx - 1.0, ry, rz);
        let d010 = grad_dot(p(xy01 + z), rx, ry - 1.0, rz);
        let d110 = grad_dot(p(xy11 + z), rx - 1.0, ry - 1.0, rz);
        let d001 = grad_dot(p(xy00 + z + 1), rx, ry, rz - 1.0);
        let d101 = grad_dot(p(xy10 + z + 1), rx - 1.0, ry, rz - 1.0);
        let d011 = grad_dot(p(xy01 + z + 1), rx, ry - 1.0, rz - 1.0);
        let d111 = grad_dot(p(xy11 + z + 1), rx - 1.0, ry - 1.0, rz - 1.0);
        lerp3(smoothstep(rx), smoothstep(original_ry), smoothstep(rz), d000, d100, d010, d110, d001, d101, d011, d111)
    }

    /// Shared body of the vectorized volume sampling; `fudge` maps
    /// (original y, relative y) to the relative y used for the y gradients.
    ///
    /// Same arithmetic per point as vanilla's loop (see `add_to_volume_reference`), but
    /// everything that depends only on y is computed once for all columns, and each run of
    /// points with the same `floor(y)` (fixed gradients) is a branch-free loop that the
    /// compiler vectorizes. Float operations are neither fused nor reordered, so every
    /// point is bit-identical.
    fn add_to_volume_impl(
        &self,
        buffer: &mut [f32],
        volume: &DensityVolume,
        xz_scale: f64,
        y_scale: f64,
        amplitude: f32,
        fudge: Option<f64>,
    ) {
        let b = &self.base;
        let ny = volume.size_y as usize;
        Y_SCRATCH.with_borrow_mut(|scratch| {
            let YScratch { ry, alpha, runs } = scratch;
            ry.clear();
            alpha.clear();
            runs.clear();
            let mut last_floor_y = i32::MIN;
            for iy in 0..volume.size_y {
                let original_y = volume.block_y(iy) as f64 * y_scale;
                let y = wrap(original_y) + b.offset_y;
                let floor_y = floor(y);
                let relative_y = y - floor_y as f64;
                alpha.push(smoothstep(relative_y as f32));
                ry.push(match fudge {
                    None => relative_y as f32,
                    Some(scale) => (relative_y - compute_fudge_y(scale, original_y, relative_y)) as f32,
                });
                if floor_y != last_floor_y || runs.is_empty() {
                    runs.push((iy as usize, floor_y));
                    last_floor_y = floor_y;
                }
            }

            let mut base_index = 0;
            for iz in 0..volume.size_z {
                let z = wrap(volume.block_z(iz) as f64 * xz_scale) + b.offset_z;
                let floor_z = floor(z);
                let rz = (z - floor_z as f64) as f32;
                let alpha_z = smoothstep(rz);
                for ix in 0..volume.size_x {
                    let x = wrap(volume.block_x(ix) as f64 * xz_scale) + b.offset_x;
                    let floor_x = floor(x);
                    let rx = (x - floor_x as f64) as f32;
                    let x0 = b.permute(floor_x);
                    let x1 = b.permute(floor_x + 1);
                    let alpha_x = smoothstep(rx);
                    for (r, &(start, floor_y)) in runs.iter().enumerate() {
                        let end = runs.get(r + 1).map_or(ny, |next| next.0);
                        let xy00 = b.permute(x0 + floor_y);
                        let xy01 = b.permute(x0 + floor_y + 1);
                        let xy10 = b.permute(x1 + floor_y);
                        let xy11 = b.permute(x1 + floor_y + 1);
                        let g = b.permute_to_grad(xy00 + floor_z);
                        let (d000xz, g000y) = (dot_xz(g, rx, rz), g[1] as f32);
                        let g = b.permute_to_grad(xy10 + floor_z);
                        let (d100xz, g100y) = (dot_xz(g, rx - 1.0, rz), g[1] as f32);
                        let g = b.permute_to_grad(xy01 + floor_z);
                        let (d010xz, g010y) = (dot_xz(g, rx, rz), g[1] as f32);
                        let g = b.permute_to_grad(xy11 + floor_z);
                        let (d110xz, g110y) = (dot_xz(g, rx - 1.0, rz), g[1] as f32);
                        let g = b.permute_to_grad(xy00 + floor_z + 1);
                        let (d001xz, g001y) = (dot_xz(g, rx, rz - 1.0), g[1] as f32);
                        let g = b.permute_to_grad(xy10 + floor_z + 1);
                        let (d101xz, g101y) = (dot_xz(g, rx - 1.0, rz - 1.0), g[1] as f32);
                        let g = b.permute_to_grad(xy01 + floor_z + 1);
                        let (d011xz, g011y) = (dot_xz(g, rx, rz - 1.0), g[1] as f32);
                        let g = b.permute_to_grad(xy11 + floor_z + 1);
                        let (d111xz, g111y) = (dot_xz(g, rx - 1.0, rz - 1.0), g[1] as f32);

                        let out = &mut buffer[base_index + start..base_index + end];
                        let ry = &ry[start..end];
                        let alpha = &alpha[start..end];
                        for k in 0..out.len() {
                            let ry = ry[k];
                            out[k] += amplitude
                                * lerp3(
                                    alpha_x,
                                    alpha[k],
                                    alpha_z,
                                    d000xz + g000y * ry,
                                    d100xz + g100y * ry,
                                    d010xz + g010y * (ry - 1.0),
                                    d110xz + g110y * (ry - 1.0),
                                    d001xz + g001y * ry,
                                    d101xz + g101y * ry,
                                    d011xz + g011y * (ry - 1.0),
                                    d111xz + g111y * (ry - 1.0),
                                );
                        }
                    }
                    base_index += ny;
                }
            }
        });
    }

    /// Shared body of the vectorized volume sampling; `fudge` maps
    /// (original y, relative y) to the relative y used for the y gradients.
    #[doc(hidden)]
    pub fn add_to_volume_reference(
        &self,
        buffer: &mut [f32],
        volume: &DensityVolume,
        xz_scale: f64,
        y_scale: f64,
        amplitude: f32,
        fudge: Option<f64>,
    ) {
        let b = &self.base;
        let (mut d000xz, mut d100xz, mut d010xz, mut d110xz) = (0f32, 0f32, 0f32, 0f32);
        let (mut d001xz, mut d101xz, mut d011xz, mut d111xz) = (0f32, 0f32, 0f32, 0f32);
        let (mut g000y, mut g100y, mut g010y, mut g110y) = (0f32, 0f32, 0f32, 0f32);
        let (mut g001y, mut g101y, mut g011y, mut g111y) = (0f32, 0f32, 0f32, 0f32);
        let mut index = 0;
        for iz in 0..volume.size_z {
            let z = wrap(volume.block_z(iz) as f64 * xz_scale) + b.offset_z;
            let floor_z = floor(z);
            let rz = (z - floor_z as f64) as f32;
            let alpha_z = smoothstep(rz);
            for ix in 0..volume.size_x {
                let x = wrap(volume.block_x(ix) as f64 * xz_scale) + b.offset_x;
                let floor_x = floor(x);
                let rx = (x - floor_x as f64) as f32;
                let x0 = b.permute(floor_x);
                let x1 = b.permute(floor_x + 1);
                let alpha_x = smoothstep(rx);
                let mut last_floor_y = i32::MIN;
                for iy in 0..volume.size_y {
                    let original_y = volume.block_y(iy) as f64 * y_scale;
                    let y = wrap(original_y) + b.offset_y;
                    let floor_y = floor(y);
                    let relative_y = y - floor_y as f64;
                    let alpha_y = smoothstep(relative_y as f32);
                    if last_floor_y != floor_y {
                        let xy00 = b.permute(x0 + floor_y);
                        let xy01 = b.permute(x0 + floor_y + 1);
                        let xy10 = b.permute(x1 + floor_y);
                        let xy11 = b.permute(x1 + floor_y + 1);
                        let g = b.permute_to_grad(xy00 + floor_z);
                        d000xz = dot_xz(g, rx, rz);
                        g000y = g[1] as f32;
                        let g = b.permute_to_grad(xy10 + floor_z);
                        d100xz = dot_xz(g, rx - 1.0, rz);
                        g100y = g[1] as f32;
                        let g = b.permute_to_grad(xy01 + floor_z);
                        d010xz = dot_xz(g, rx, rz);
                        g010y = g[1] as f32;
                        let g = b.permute_to_grad(xy11 + floor_z);
                        d110xz = dot_xz(g, rx - 1.0, rz);
                        g110y = g[1] as f32;
                        let g = b.permute_to_grad(xy00 + floor_z + 1);
                        d001xz = dot_xz(g, rx, rz - 1.0);
                        g001y = g[1] as f32;
                        let g = b.permute_to_grad(xy10 + floor_z + 1);
                        d101xz = dot_xz(g, rx - 1.0, rz - 1.0);
                        g101y = g[1] as f32;
                        let g = b.permute_to_grad(xy01 + floor_z + 1);
                        d011xz = dot_xz(g, rx, rz - 1.0);
                        g011y = g[1] as f32;
                        let g = b.permute_to_grad(xy11 + floor_z + 1);
                        d111xz = dot_xz(g, rx - 1.0, rz - 1.0);
                        g111y = g[1] as f32;
                        last_floor_y = floor_y;
                    }
                    let ry = match fudge {
                        None => relative_y as f32,
                        Some(scale) => (relative_y - compute_fudge_y(scale, original_y, relative_y)) as f32,
                    };
                    buffer[index] += amplitude
                        * lerp3(
                            alpha_x,
                            alpha_y,
                            alpha_z,
                            d000xz + g000y * ry,
                            d100xz + g100y * ry,
                            d010xz + g010y * (ry - 1.0),
                            d110xz + g110y * (ry - 1.0),
                            d001xz + g001y * ry,
                            d101xz + g101y * ry,
                            d011xz + g011y * (ry - 1.0),
                            d111xz + g111y * (ry - 1.0),
                        );
                    index += 1;
                }
            }
        }
    }
}

impl Noise for PerlinNoise {
    fn get2(&self, x: f64, y: f64) -> f32 {
        self.get3(wrap(x), 0.0, wrap(y))
    }

    fn get3(&self, x: f64, y: f64, z: f64) -> f32 {
        let b = &self.base;
        let x = wrap(x) + b.offset_x;
        let y = wrap(y) + b.offset_y;
        let z = wrap(z) + b.offset_z;
        let (fx, fy, fz) = (floor(x), floor(y), floor(z));
        let rx = (x - fx as f64) as f32;
        let ry = (y - fy as f64) as f32;
        let rz = (z - fz as f64) as f32;
        self.sample_and_lerp(fx, fy, fz, rx, ry, rz, ry)
    }

    fn add_to_volume(&self, buffer: &mut [f32], volume: &DensityVolume, xz_scale: f64, y_scale: f64, amplitude: f32) {
        self.add_to_volume_impl(buffer, volume, xz_scale, y_scale, amplitude, None);
    }
}

/// Per-y values of one volume sampling, reused between calls on a thread.
#[derive(Default)]
struct YScratch {
    ry: Vec<f32>,
    alpha: Vec<f32>,
    /// (first y index, floor(y)) of each run of equal floor(y).
    runs: Vec<(usize, i32)>,
}

thread_local! {
    static Y_SCRATCH: std::cell::RefCell<YScratch> = std::cell::RefCell::new(YScratch::default());
}

/// `1.0E-7F` widened to double.
const SHIFT_UP_EPSILON: f64 = 1.0e-7_f32 as f64;

fn compute_fudge_y(fudge_y_scale: f64, original_y: f64, relative_y: f64) -> f64 {
    let limit = if original_y >= 0.0 && original_y < relative_y { original_y } else { relative_y };
    floor(limit / fudge_y_scale + SHIFT_UP_EPSILON) as f64 * fudge_y_scale
}

/// `SmearedPerlinNoise`: legacy Perlin noise with a quantized y offset.
#[derive(Debug, Clone)]
pub struct SmearedPerlinNoise {
    pub perlin: PerlinNoise,
    pub fudge_y_scale: f64,
}

impl SmearedPerlinNoise {
    pub fn new(random: &mut impl RandomSource, fudge_y_scale: f64) -> SmearedPerlinNoise {
        SmearedPerlinNoise { perlin: PerlinNoise::new(random), fudge_y_scale }
    }
}

impl Noise for SmearedPerlinNoise {
    fn get2(&self, x: f64, y: f64) -> f32 {
        self.get3(wrap(x), 0.0, wrap(y))
    }

    fn get3(&self, ox: f64, oy: f64, oz: f64) -> f32 {
        let b = &self.perlin.base;
        let x = wrap(ox) + b.offset_x;
        let y = wrap(oy) + b.offset_y;
        let z = wrap(oz) + b.offset_z;
        let (fx, fy, fz) = (floor(x), floor(y), floor(z));
        let rx = (x - fx as f64) as f32;
        let relative_y = y - fy as f64;
        let rz = (z - fz as f64) as f32;
        let fudged = (relative_y - compute_fudge_y(self.fudge_y_scale, oy, relative_y)) as f32;
        self.perlin.sample_and_lerp(fx, fy, fz, rx, fudged, rz, relative_y as f32)
    }

    fn add_to_volume(&self, buffer: &mut [f32], volume: &DensityVolume, xz_scale: f64, y_scale: f64, amplitude: f32) {
        self.perlin.add_to_volume_impl(buffer, volume, xz_scale, y_scale, amplitude, Some(self.fudge_y_scale));
    }
}

/// `SimplexNoise`
#[derive(Debug, Clone)]
pub struct SimplexNoise {
    pub base: GradientBase,
}

impl SimplexNoise {
    pub fn new(random: &mut impl RandomSource) -> SimplexNoise {
        SimplexNoise { base: GradientBase::new(random, 256.0) }
    }

    pub fn new_discard_offset(random: &mut impl RandomSource, discard: bool) -> SimplexNoise {
        SimplexNoise { base: GradientBase::new(random, if discard { 0.0 } else { 256.0 }) }
    }

    fn corner(index: i32, x: f64, y: f64, z: f64, base: f64) -> f64 {
        let mut t0 = base - x * x - y * y - z * z;
        if t0 < 0.0 {
            0.0
        } else {
            t0 *= t0;
            let g = GRADIENT[index as usize];
            t0 * t0 * (g[0] as f64 * x + g[1] as f64 * y + g[2] as f64 * z)
        }
    }
}

fn sqrt3() -> f64 {
    3.0f64.sqrt()
}

impl Noise for SimplexNoise {
    fn get2(&self, xin: f64, yin: f64) -> f32 {
        let f2 = 0.5 * (sqrt3() - 1.0);
        let g2 = (3.0 - sqrt3()) / 6.0;
        let b = &self.base;
        let xin = xin + b.offset_x;
        let yin = yin + b.offset_y;
        let s = (xin + yin) * f2;
        let i = floor(xin + s);
        let j = floor(yin + s);
        let t = (i + j) as f64 * g2;
        let x0 = xin - (i as f64 - t);
        let y0 = yin - (j as f64 - t);
        let (i1, j1) = if x0 > y0 { (1, 0) } else { (0, 1) };
        let x1 = x0 - i1 as f64 + g2;
        let y1 = y0 - j1 as f64 + g2;
        let x2 = x0 - 1.0 + 2.0 * g2;
        let y2 = y0 - 1.0 + 2.0 * g2;
        let ii = i & 0xFF;
        let jj = j & 0xFF;
        let gi0 = b.permute(ii + b.permute(jj)) % 12;
        let gi1 = b.permute(ii + i1 + b.permute(jj + j1)) % 12;
        let gi2 = b.permute(ii + 1 + b.permute(jj + 1)) % 12;
        let n0 = Self::corner(gi0, x0, y0, 0.0, 0.5);
        let n1 = Self::corner(gi1, x1, y1, 0.0, 0.5);
        let n2 = Self::corner(gi2, x2, y2, 0.0, 0.5);
        (70.0 * (n0 + n1 + n2)) as f32
    }

    fn get3(&self, xin: f64, yin: f64, zin: f64) -> f32 {
        let b = &self.base;
        let xin = xin + b.offset_x;
        let yin = yin + b.offset_y;
        let zin = zin + b.offset_z;
        let s = (xin + yin + zin) * 0.3333333333333333;
        let i = floor(xin + s);
        let j = floor(yin + s);
        let k = floor(zin + s);
        let t = (i + j + k) as f64 * 0.16666666666666666;
        let x0 = xin - (i as f64 - t);
        let y0 = yin - (j as f64 - t);
        let z0 = zin - (k as f64 - t);
        let (i1, j1, k1, i2, j2, k2) = if x0 >= y0 {
            if y0 >= z0 {
                (1, 0, 0, 1, 1, 0)
            } else if x0 >= z0 {
                (1, 0, 0, 1, 0, 1)
            } else {
                (0, 0, 1, 1, 0, 1)
            }
        } else if y0 < z0 {
            (0, 0, 1, 0, 1, 1)
        } else if x0 < z0 {
            (0, 1, 0, 0, 1, 1)
        } else {
            (0, 1, 0, 1, 1, 0)
        };
        let x1 = x0 - i1 as f64 + 0.16666666666666666;
        let y1 = y0 - j1 as f64 + 0.16666666666666666;
        let z1 = z0 - k1 as f64 + 0.16666666666666666;
        let x2 = x0 - i2 as f64 + 0.3333333333333333;
        let y2 = y0 - j2 as f64 + 0.3333333333333333;
        let z2 = z0 - k2 as f64 + 0.3333333333333333;
        let x3 = x0 - 1.0 + 0.5;
        let y3 = y0 - 1.0 + 0.5;
        let z3 = z0 - 1.0 + 0.5;
        let ii = i & 0xFF;
        let jj = j & 0xFF;
        let kk = k & 0xFF;
        let p = |v| b.permute(v);
        let gi0 = p(ii + p(jj + p(kk))) % 12;
        let gi1 = p(ii + i1 + p(jj + j1 + p(kk + k1))) % 12;
        let gi2 = p(ii + i2 + p(jj + j2 + p(kk + k2))) % 12;
        let gi3 = p(ii + 1 + p(jj + 1 + p(kk + 1))) % 12;
        let n0 = Self::corner(gi0, x0, y0, z0, 0.6);
        let n1 = Self::corner(gi1, x1, y1, z1, 0.6);
        let n2 = Self::corner(gi2, x2, y2, z2, 0.6);
        let n3 = Self::corner(gi3, x3, y3, z3, 0.6);
        (32.0 * (n0 + n1 + n2 + n3)) as f32
    }
}

/// One layer of a [`NoiseStack`].
pub struct Layer {
    pub noise: Arc<dyn Noise>,
    pub frequency: f64,
    pub amplitude: f32,
}

/// `NoiseStack`: sum of scaled noise layers (octaves).
#[derive(Default)]
pub struct NoiseStack {
    pub layers: Vec<Layer>,
}

impl NoiseStack {
    pub fn add(&mut self, noise: impl Noise + 'static, frequency: f64, amplitude: f32) {
        self.layers.push(Layer { noise: Arc::new(noise), frequency, amplitude });
    }
}

impl Noise for NoiseStack {
    fn get2(&self, x: f64, y: f64) -> f32 {
        let mut value = 0.0f32;
        for l in &self.layers {
            value += l.amplitude * l.noise.get2(x * l.frequency, y * l.frequency);
        }
        value
    }

    fn get3(&self, x: f64, y: f64, z: f64) -> f32 {
        let mut value = 0.0f32;
        for l in &self.layers {
            value += l.amplitude * l.noise.get3(x * l.frequency, y * l.frequency, z * l.frequency);
        }
        value
    }

    fn add_to_volume(&self, buffer: &mut [f32], volume: &DensityVolume, xz_scale: f64, y_scale: f64, amplitude: f32) {
        for l in &self.layers {
            l.noise.add_to_volume(buffer, volume, xz_scale * l.frequency, y_scale * l.frequency, amplitude * l.amplitude);
        }
    }
}

/// `DoubleStream.sum()`: Kahan-compensated, exactly as the JDK computes it.
pub fn java_double_sum(values: impl IntoIterator<Item = f64>) -> f64 {
    let (mut sum, mut compensation, mut simple) = (0.0f64, 0.0f64, 0.0f64);
    for value in values {
        let tmp = value - compensation;
        let velvel = sum + tmp;
        compensation = (velvel - sum) - tmp;
        sum = velvel;
        simple += value;
    }
    let tmp = sum - compensation;
    if tmp.is_nan() && simple.is_infinite() {
        simple
    } else {
        tmp
    }
}

/// `NormalNoise.Normalization`
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Normalization {
    Disabled,
    Enabled,
    Legacy,
}

/// `NormalNoise.Parameters`
#[derive(Debug, Clone, PartialEq)]
pub struct NormalNoiseParameters {
    pub base_amplitude: f64,
    pub base_octave: i32,
    pub octave_count: i32,
    pub normalize: Normalization,
    pub amplitude_modifiers: Vec<f64>,
}

#[derive(Debug, Clone, Copy)]
struct OctaveInfo {
    octave_index: i32,
    frequency: f64,
    amplitude: f64,
}

/// `NormalNoise`: a recipe for a normalized pair of Perlin stacks.
#[derive(Debug, Clone)]
pub struct NormalNoise {
    pub parameters: NormalNoiseParameters,
    octaves: Vec<OctaveInfo>,
    normalization_factor: f64,
}

const STANDARD_DEVIATION: f64 = 0.2702247831245211;
const TARGET_DEVIATION: f64 = 0.3333333333333333;
const INPUT_FACTOR: f64 = 1.0181268882175227;

fn amplitude_modifier(modifiers: &[f64], index: i32) -> f64 {
    if modifiers.is_empty() {
        1.0
    } else {
        modifiers[index as usize]
    }
}

fn build_octaves(base_octave: i32, base_amplitude: f64, octave_count: i32, normalize: bool, modifiers: &[f64]) -> Vec<OctaveInfo> {
    let mut frequency = jmath::pow(2.0, base_octave as f64);
    let mut amplitude = base_amplitude;
    if normalize {
        amplitude *= jmath::pow(0.5, -(octave_count - 1) as f64) / (jmath::pow(0.5, -octave_count as f64) - 1.0);
    }
    let mut octaves = Vec::with_capacity(octave_count as usize);
    for i in 0..octave_count {
        let modifier = amplitude_modifier(modifiers, i);
        if modifier != 0.0 {
            octaves.push(OctaveInfo { octave_index: base_octave + i, frequency, amplitude: amplitude * modifier });
        }
        frequency *= 2.0;
        amplitude *= 0.5;
    }
    octaves
}

fn compute_normalization_factor(target_amplitude: f64, octaves: &[OctaveInfo]) -> f64 {
    let mut variance = 0.0;
    for o in octaves {
        let d = STANDARD_DEVIATION * o.amplitude.abs();
        variance += d * d;
    }
    let input_deviation = variance.sqrt();
    if input_deviation == 0.0 {
        return 0.0;
    }
    let input_sum_deviation = input_deviation * 2.0f64.sqrt();
    target_amplitude * TARGET_DEVIATION / input_sum_deviation
}

fn parity_normalization_factor(base_amplitude: f64, octave_count: i32, modifiers: &[f64]) -> f64 {
    let (mut min_octave, mut max_octave) = (i32::MAX, i32::MIN);
    for i in 0..octave_count {
        if amplitude_modifier(modifiers, i) != 0.0 {
            min_octave = min_octave.min(i);
            max_octave = max_octave.max(i);
        }
    }
    let span = max_octave - min_octave;
    base_amplitude * 0.5 * TARGET_DEVIATION / (0.1 * (1.0 + 1.0 / (span + 1) as f64))
}

impl NormalNoise {
    pub fn new(parameters: NormalNoiseParameters) -> NormalNoise {
        let p = &parameters;
        let octaves = build_octaves(
            p.base_octave,
            p.base_amplitude,
            p.octave_count,
            p.normalize != Normalization::Disabled,
            &p.amplitude_modifiers,
        );
        let target_amplitude = java_double_sum(octaves.iter().map(|o| o.amplitude.abs()));
        let mut normalization_factor = compute_normalization_factor(target_amplitude, &octaves);
        if p.normalize == Normalization::Legacy && normalization_factor != 0.0 {
            normalization_factor =
                parity_normalization_factor(p.base_amplitude, p.octave_count, &p.amplitude_modifiers);
        }
        NormalNoise { parameters, octaves, normalization_factor }
    }

    /// `NormalNoise.createParity(firstOctave, amplitudes)`
    pub fn create_parity(first_octave: i32, amplitudes: &[f64]) -> NormalNoise {
        assert!(!amplitudes.is_empty(), "Need at least 1 amplitude");
        let count = amplitudes.len() as i32;
        let octaves = build_octaves(first_octave, 1.0, count, true, amplitudes);
        let target = java_double_sum(octaves.iter().map(|o| o.amplitude.abs()));
        let new_factor = compute_normalization_factor(target, &octaves);
        let base_amplitude = if new_factor == 0.0 {
            1.0
        } else {
            parity_normalization_factor(1.0, count, amplitudes) / new_factor
        };
        let modifiers = if amplitudes.iter().all(|&a| a == 1.0) { Vec::new() } else { amplitudes.to_vec() };
        NormalNoise::new(NormalNoiseParameters {
            base_amplitude,
            base_octave: first_octave,
            octave_count: count,
            normalize: Normalization::Enabled,
            amplitude_modifiers: modifiers,
        })
    }

    /// `NormalNoise.create(random)`: two Perlin stacks, the second slightly rescaled.
    pub fn create(&self, random: &mut impl ForkingRandom) -> NoiseStack {
        let first = random.fork_positional_factory();
        let second = random.fork_positional_factory();
        let mut stack = NoiseStack::default();
        for octave in &self.octaves {
            let seed = format!("octave_{}", octave.octave_index);
            let value_factor = (self.normalization_factor * octave.amplitude) as f32;
            stack.add(PerlinNoise::new(&mut first.from_hash_of(&seed)), octave.frequency, value_factor);
            stack.add(PerlinNoise::new(&mut second.from_hash_of(&seed)), octave.frequency * INPUT_FACTOR, value_factor);
        }
        stack
    }
}

/// `BlendedNoise.createFbm`: legacy smeared octaves for the base terrain noise.
pub fn create_blended_fbm(random: &mut impl RandomSource, first_octave: i32, smear_scale_y: f64, mut value_factor: f64) -> NoiseStack {
    assert!(first_octave <= 0, "firstOctave>0");
    let octaves = -first_octave + 1;
    let mut factor = 1.0;
    value_factor /= jmath::pow(2.0, octaves as f64) - 1.0;
    let mut stack = NoiseStack::default();
    for _ in (0..octaves).rev() {
        stack.add(SmearedPerlinNoise::new(random, smear_scale_y * factor), factor, value_factor as f32);
        factor /= 2.0;
        value_factor *= 2.0;
    }
    stack
}
