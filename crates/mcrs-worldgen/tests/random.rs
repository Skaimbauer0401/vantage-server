mod common;

use common::Oracle;
use mcrs_worldgen::random::*;

const SEEDS: [i64; 7] = [0, 1, -1, 12345, i64::MIN, 0x5DEECE66D, 8682522807148012];
const BOUNDS: [i32; 9] = [1, 2, 3, 7, 16, 100, 1 << 30, 1_000_000_007, i32::MAX];

/// Mirrors `Oracle.exercise`; the fork calls differ per source type.
fn exercise<R: RandomSource>(
    o: &mut Oracle,
    p: &str,
    r: &mut R,
    fork: impl Fn(&mut R) -> i64,
    positional: impl Fn(&mut R) -> [i64; 4],
) {
    for _ in 0..4 {
        o.line(format!("{p}nextInt"), r.next_int());
    }
    for b in BOUNDS {
        o.line(format!("{p}nextInt({b})"), r.next_int_bounded(b));
    }
    for _ in 0..3 {
        o.line(format!("{p}nextLong"), r.next_long());
    }
    for _ in 0..3 {
        o.f(format!("{p}nextFloat"), r.next_float());
    }
    for _ in 0..3 {
        o.d(format!("{p}nextDouble"), r.next_double());
    }
    for _ in 0..5 {
        o.d(format!("{p}nextGaussian"), r.next_gaussian());
    }
    for _ in 0..3 {
        o.line(format!("{p}nextBoolean"), r.next_boolean() as i32);
    }
    o.line(format!("{p}between(-5,5)"), r.next_int_between_inclusive(-5, 5));
    r.consume_count(3);
    o.line(format!("{p}afterConsume"), r.next_int());
    o.line(format!("{p}fork"), fork(r));
    let [at, far, hash, seed] = positional(r);
    o.line(format!("{p}at(1,2,3)"), at);
    o.line(format!("{p}at(-30000000,-64,29999999)"), far);
    o.line(format!("{p}fromHashOf"), hash);
    o.line(format!("{p}fromSeed"), seed);
}

#[test]
fn random_matches_vanilla() {
    let Some(mut o) = Oracle::load("random") else { return };
    for seed in SEEDS {
        exercise(
            &mut o,
            &format!("legacy[{seed}]."),
            &mut LegacyRandom::new(seed),
            |r| r.fork().next_long(),
            |r| {
                let f = r.fork_positional();
                [
                    f.at(1, 2, 3).next_long(),
                    f.at(-30_000_000, -64, 29_999_999).next_long(),
                    f.from_hash_of("minecraft:test").next_long(),
                    f.from_seed(42).next_long(),
                ]
            },
        );
        exercise(
            &mut o,
            &format!("xoroshiro[{seed}]."),
            &mut XoroshiroRandom::new(seed),
            |r| r.fork().next_long(),
            |r| {
                let f = r.fork_positional();
                [
                    f.at(1, 2, 3).next_long(),
                    f.at(-30_000_000, -64, 29_999_999).next_long(),
                    f.from_hash_of("minecraft:test").next_long(),
                    f.from_seed(42).next_long(),
                ]
            },
        );
        o.line(format!("mix[{seed}]"), mix_stafford13(seed));
    }
    for (x, y, z) in [(0, 0, 0), (1, 2, 3), (-1, -64, -1), (30_000_000, 320, -30_000_000), (123456, 7, -987654)] {
        o.line(format!("getSeed({x},{y},{z})"), get_seed(x, y, z));
    }
    for s in ["", "minecraft:overworld", "octave_-7", "ünïcödé"] {
        let seed = Seed128::from_hash_of(s);
        o.line(format!("md5lo[{s}]"), seed.lo);
        o.line(format!("md5hi[{s}]"), seed.hi);
    }
    o.check();
}
