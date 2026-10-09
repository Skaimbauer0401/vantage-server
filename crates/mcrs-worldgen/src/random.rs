//! Minecraft's random sources (`net.minecraft.world.level.levelgen`), bit-exact.
//!
//! Quirks kept on purpose:
//! - `nextDouble` multiplies by `1.110223E-16F`, a *float* literal, not 2^-53.
//! - `Mth.getSeed` multiplies `x` in 32-bit int arithmetic before widening.

use md5::{Digest, Md5};

/// `1.110223E-16F` widened to double.
const DOUBLE_UNIT: f64 = 1.110223e-16_f32 as f64;
const FLOAT_UNIT: f32 = 5.960_464_5e-8;

/// `RandomSource`.
pub trait RandomSource {
    fn next_int(&mut self) -> i32;
    fn next_int_bounded(&mut self, bound: i32) -> i32;
    fn next_long(&mut self) -> i64;
    fn next_boolean(&mut self) -> bool;
    fn next_float(&mut self) -> f32;
    fn next_double(&mut self) -> f64;
    fn next_gaussian(&mut self) -> f64;
    fn consume_count(&mut self, rounds: i32) {
        for _ in 0..rounds {
            self.next_int();
        }
    }

    /// `nextIntBetweenInclusive`
    fn next_int_between_inclusive(&mut self, min: i32, max_inclusive: i32) -> i32 {
        self.next_int_bounded(max_inclusive - min + 1) + min
    }

    /// `nextInt(origin, bound)`
    fn next_int_range(&mut self, origin: i32, bound: i32) -> i32 {
        assert!(origin < bound, "bound - origin is non positive");
        origin + self.next_int_bounded(bound - origin)
    }

    fn triangle(&mut self, mean: f64, spread: f64) -> f64 {
        mean + spread * (self.next_double() - self.next_double())
    }
}

/// `MarsagliaPolarGaussian`
#[derive(Debug, Clone, Default)]
struct Gaussian {
    next_next: f64,
    have_next_next: bool,
}

impl Gaussian {
    fn next(&mut self, source: &mut impl RandomSource) -> f64 {
        if self.have_next_next {
            self.have_next_next = false;
            return self.next_next;
        }
        let (x, y, radius_squared) = loop {
            let x = 2.0 * source.next_double() - 1.0;
            let y = 2.0 * source.next_double() - 1.0;
            let r = x * x + y * y;
            if !(r >= 1.0 || r == 0.0) {
                break (x, y, r);
            }
        };
        let multiplier = (-2.0 * crate::jmath::log(radius_squared) / radius_squared).sqrt();
        self.next_next = y * multiplier;
        self.have_next_next = true;
        x * multiplier
    }
}

/// `LegacyRandomSource`: java.util.Random's LCG.
#[derive(Debug, Clone)]
pub struct LegacyRandom {
    seed: i64,
    gaussian: Gaussian,
}

const LCG_MULTIPLIER: i64 = 0x5DEECE66D;
const LCG_MASK: i64 = (1 << 48) - 1;

impl LegacyRandom {
    pub fn new(seed: i64) -> LegacyRandom {
        LegacyRandom { seed: (seed ^ LCG_MULTIPLIER) & LCG_MASK, gaussian: Gaussian::default() }
    }

    pub fn set_seed(&mut self, seed: i64) {
        *self = LegacyRandom::new(seed);
    }

    /// `BitRandomSource.next(bits)`
    pub fn next(&mut self, bits: u32) -> i32 {
        self.seed = self.seed.wrapping_mul(LCG_MULTIPLIER).wrapping_add(11) & LCG_MASK;
        (self.seed >> (48 - bits)) as i32
    }

    pub fn fork(&mut self) -> LegacyRandom {
        LegacyRandom::new(self.next_long())
    }

    pub fn fork_positional(&mut self) -> LegacyPositionalFactory {
        LegacyPositionalFactory { seed: self.next_long() }
    }
}

impl RandomSource for LegacyRandom {
    fn next_int(&mut self) -> i32 {
        self.next(32)
    }

    fn next_int_bounded(&mut self, bound: i32) -> i32 {
        assert!(bound > 0, "Bound must be positive");
        if bound & (bound - 1) == 0 {
            return ((bound as i64 * self.next(31) as i64) >> 31) as i32;
        }
        loop {
            let sample = self.next(31);
            let modulo = sample % bound;
            if sample.wrapping_sub(modulo).wrapping_add(bound - 1) >= 0 {
                return modulo;
            }
        }
    }

    fn next_long(&mut self) -> i64 {
        let upper = self.next(32) as i64;
        let lower = self.next(32) as i64;
        (upper << 32).wrapping_add(lower)
    }

    fn next_boolean(&mut self) -> bool {
        self.next(1) != 0
    }

    fn next_float(&mut self) -> f32 {
        self.next(24) as f32 * FLOAT_UNIT
    }

    fn next_double(&mut self) -> f64 {
        let upper = self.next(26) as i64;
        let lower = self.next(27) as i64;
        ((upper << 27) + lower) as f64 * DOUBLE_UNIT
    }

    fn next_gaussian(&mut self) -> f64 {
        let mut gaussian = std::mem::take(&mut self.gaussian);
        let value = gaussian.next(self);
        self.gaussian = gaussian;
        value
    }
}

/// `LegacyRandomSource.LegacyPositionalRandomFactory`
#[derive(Debug, Clone, Copy)]
pub struct LegacyPositionalFactory {
    pub seed: i64,
}

impl LegacyPositionalFactory {
    pub fn at(&self, x: i32, y: i32, z: i32) -> LegacyRandom {
        LegacyRandom::new(get_seed(x, y, z) ^ self.seed)
    }

    pub fn from_hash_of(&self, name: &str) -> LegacyRandom {
        LegacyRandom::new(java_string_hash(name) as i64 ^ self.seed)
    }

    pub fn from_seed(&self, seed: i64) -> LegacyRandom {
        LegacyRandom::new(seed)
    }
}

/// `Xoroshiro128PlusPlus`
#[derive(Debug, Clone, Copy)]
pub struct Xoroshiro128PlusPlus {
    lo: i64,
    hi: i64,
}

pub const GOLDEN_RATIO_64: i64 = -7046029254386353131;
pub const SILVER_RATIO_64: i64 = 7640891576956012809;

impl Xoroshiro128PlusPlus {
    pub fn new(lo: i64, hi: i64) -> Xoroshiro128PlusPlus {
        if lo | hi == 0 {
            Xoroshiro128PlusPlus { lo: GOLDEN_RATIO_64, hi: SILVER_RATIO_64 }
        } else {
            Xoroshiro128PlusPlus { lo, hi }
        }
    }

    pub fn next_long(&mut self) -> i64 {
        let s0 = self.lo;
        let mut s1 = self.hi;
        let result = s0.wrapping_add(s1).rotate_left(17).wrapping_add(s0);
        s1 ^= s0;
        self.lo = s0.rotate_left(49) ^ s1 ^ (s1 << 21);
        self.hi = s1.rotate_left(28);
        result
    }
}

/// `RandomSupport.mixStafford13`
pub fn mix_stafford13(mut z: i64) -> i64 {
    z = (z ^ ((z as u64) >> 30) as i64).wrapping_mul(-4658895280553007687);
    z = (z ^ ((z as u64) >> 27) as i64).wrapping_mul(-7723592293110705685);
    z ^ ((z as u64) >> 31) as i64
}

/// `RandomSupport.Seed128bit`
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Seed128 {
    pub lo: i64,
    pub hi: i64,
}

impl Seed128 {
    /// `upgradeSeedTo128bitUnmixed`
    pub fn upgrade_unmixed(legacy: i64) -> Seed128 {
        let lo = legacy ^ SILVER_RATIO_64;
        Seed128 { lo, hi: lo.wrapping_add(GOLDEN_RATIO_64) }
    }

    /// `upgradeSeedTo128bit`
    pub fn upgrade(legacy: i64) -> Seed128 {
        let s = Seed128::upgrade_unmixed(legacy);
        Seed128 { lo: mix_stafford13(s.lo), hi: mix_stafford13(s.hi) }
    }

    /// `seedFromHashOf`: MD5 of the UTF-8 string, big-endian halves.
    pub fn from_hash_of(input: &str) -> Seed128 {
        let hash = Md5::digest(input.as_bytes());
        Seed128 {
            lo: i64::from_be_bytes(hash[0..8].try_into().unwrap()),
            hi: i64::from_be_bytes(hash[8..16].try_into().unwrap()),
        }
    }

    pub fn xor(self, lo: i64, hi: i64) -> Seed128 {
        Seed128 { lo: self.lo ^ lo, hi: self.hi ^ hi }
    }
}

/// `XoroshiroRandomSource`
#[derive(Debug, Clone)]
pub struct XoroshiroRandom {
    rng: Xoroshiro128PlusPlus,
    gaussian: Gaussian,
}

impl XoroshiroRandom {
    pub fn new(seed: i64) -> XoroshiroRandom {
        XoroshiroRandom::from_seed128(Seed128::upgrade(seed))
    }

    pub fn from_seed128(seed: Seed128) -> XoroshiroRandom {
        XoroshiroRandom::from_parts(seed.lo, seed.hi)
    }

    pub fn from_parts(lo: i64, hi: i64) -> XoroshiroRandom {
        XoroshiroRandom { rng: Xoroshiro128PlusPlus::new(lo, hi), gaussian: Gaussian::default() }
    }

    pub fn set_seed(&mut self, seed: i64) {
        *self = XoroshiroRandom::new(seed);
    }

    pub fn fork(&mut self) -> XoroshiroRandom {
        let lo = self.rng.next_long();
        let hi = self.rng.next_long();
        XoroshiroRandom::from_parts(lo, hi)
    }

    pub fn fork_positional(&mut self) -> XoroshiroPositionalFactory {
        let lo = self.rng.next_long();
        let hi = self.rng.next_long();
        XoroshiroPositionalFactory { lo, hi }
    }

    fn next_bits(&mut self, bits: u32) -> i64 {
        ((self.rng.next_long() as u64) >> (64 - bits)) as i64
    }
}

impl RandomSource for XoroshiroRandom {
    fn next_int(&mut self) -> i32 {
        self.rng.next_long() as i32
    }

    fn next_int_bounded(&mut self, bound: i32) -> i32 {
        assert!(bound > 0, "Bound must be positive");
        let bound64 = bound as i64;
        let mut random_bits = self.next_int() as u32 as i64;
        let mut multiplied = random_bits.wrapping_mul(bound64);
        let mut fractional = multiplied & 0xFFFF_FFFF;
        if fractional < bound64 {
            let unbiased_start = ((!bound).wrapping_add(1) as u32 % bound as u32) as i64;
            while fractional < unbiased_start {
                random_bits = self.next_int() as u32 as i64;
                multiplied = random_bits.wrapping_mul(bound64);
                fractional = multiplied & 0xFFFF_FFFF;
            }
        }
        (multiplied >> 32) as i32
    }

    fn next_long(&mut self) -> i64 {
        self.rng.next_long()
    }

    fn next_boolean(&mut self) -> bool {
        self.rng.next_long() & 1 != 0
    }

    fn next_float(&mut self) -> f32 {
        self.next_bits(24) as f32 * FLOAT_UNIT
    }

    fn next_double(&mut self) -> f64 {
        self.next_bits(53) as f64 * DOUBLE_UNIT
    }

    fn next_gaussian(&mut self) -> f64 {
        let mut gaussian = std::mem::take(&mut self.gaussian);
        let value = gaussian.next(self);
        self.gaussian = gaussian;
        value
    }

    fn consume_count(&mut self, rounds: i32) {
        for _ in 0..rounds {
            self.rng.next_long();
        }
    }
}

/// `XoroshiroRandomSource.XoroshiroPositionalRandomFactory`
#[derive(Debug, Clone, Copy)]
pub struct XoroshiroPositionalFactory {
    pub lo: i64,
    pub hi: i64,
}

impl XoroshiroPositionalFactory {
    pub fn at(&self, x: i32, y: i32, z: i32) -> XoroshiroRandom {
        XoroshiroRandom::from_parts(get_seed(x, y, z) ^ self.lo, self.hi)
    }

    pub fn from_hash_of(&self, name: &str) -> XoroshiroRandom {
        XoroshiroRandom::from_seed128(Seed128::from_hash_of(name).xor(self.lo, self.hi))
    }

    pub fn from_seed(&self, seed: i64) -> XoroshiroRandom {
        XoroshiroRandom::from_parts(seed ^ self.lo, seed ^ self.hi)
    }
}

/// `Mth.getSeed(x, y, z)`; `x * 3129871` overflows as int, like Java.
pub fn get_seed(x: i32, y: i32, z: i32) -> i64 {
    let mut seed = x.wrapping_mul(3129871) as i64 ^ (z as i64).wrapping_mul(116129781) ^ y as i64;
    seed = seed.wrapping_mul(seed).wrapping_mul(42317861).wrapping_add(seed.wrapping_mul(11));
    seed >> 16
}

/// `String.hashCode()` over UTF-16 code units.
pub fn java_string_hash(s: &str) -> i32 {
    s.encode_utf16().fold(0i32, |h, c| h.wrapping_mul(31).wrapping_add(c as i32))
}

/// `PositionalRandomFactory` (vanilla names: `fromHashOf`, `fromSeed`).
#[allow(clippy::wrong_self_convention)]
pub trait PositionalRandomFactory {
    type Random: RandomSource;
    fn at(&self, x: i32, y: i32, z: i32) -> Self::Random;
    fn from_hash_of(&self, name: &str) -> Self::Random;
    fn from_seed(&self, seed: i64) -> Self::Random;
}

/// Random sources that can fork (`fork`, `forkPositional`).
pub trait ForkingRandom: RandomSource + Sized {
    type Positional: PositionalRandomFactory;
    fn fork_random(&mut self) -> Self;
    fn fork_positional_factory(&mut self) -> Self::Positional;
}

impl PositionalRandomFactory for LegacyPositionalFactory {
    type Random = LegacyRandom;
    fn at(&self, x: i32, y: i32, z: i32) -> LegacyRandom {
        LegacyPositionalFactory::at(self, x, y, z)
    }
    fn from_hash_of(&self, name: &str) -> LegacyRandom {
        LegacyPositionalFactory::from_hash_of(self, name)
    }
    fn from_seed(&self, seed: i64) -> LegacyRandom {
        LegacyPositionalFactory::from_seed(self, seed)
    }
}

impl PositionalRandomFactory for XoroshiroPositionalFactory {
    type Random = XoroshiroRandom;
    fn at(&self, x: i32, y: i32, z: i32) -> XoroshiroRandom {
        XoroshiroPositionalFactory::at(self, x, y, z)
    }
    fn from_hash_of(&self, name: &str) -> XoroshiroRandom {
        XoroshiroPositionalFactory::from_hash_of(self, name)
    }
    fn from_seed(&self, seed: i64) -> XoroshiroRandom {
        XoroshiroPositionalFactory::from_seed(self, seed)
    }
}

impl ForkingRandom for LegacyRandom {
    type Positional = LegacyPositionalFactory;
    fn fork_random(&mut self) -> LegacyRandom {
        self.fork()
    }
    fn fork_positional_factory(&mut self) -> LegacyPositionalFactory {
        self.fork_positional()
    }
}

impl ForkingRandom for XoroshiroRandom {
    type Positional = XoroshiroPositionalFactory;
    fn fork_random(&mut self) -> XoroshiroRandom {
        self.fork()
    }
    fn fork_positional_factory(&mut self) -> XoroshiroPositionalFactory {
        self.fork_positional()
    }
}
