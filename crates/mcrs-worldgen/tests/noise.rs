mod common;

use common::Oracle;
use mcrs_worldgen::noise::*;
use mcrs_worldgen::random::{LegacyRandom, XoroshiroRandom};

/// Same formula as `Oracle.point`.
fn point(i: i32) -> [f64; 3] {
    let mut x = (i * 7919 % 2003 - 1001) as f64 * 0.37 + i as f64 * 0.0013;
    let y = (i * 104729 % 389 - 64) as f64 * 0.91;
    let mut z = (i * 7907 % 1999 - 999) as f64 * 0.53 - i as f64 * 0.0007;
    if i % 10 == 0 {
        x *= 99991.0;
        z *= 49999.0;
    }
    [x, y, z]
}

const POINTS: i32 = 200;

fn sample(o: &mut Oracle, p: &str, noise: &dyn Noise) {
    for i in 0..POINTS {
        let [x, y, z] = point(i);
        o.f(format!("{p}.get3[{i}]"), noise.get3(x, y, z));
        o.f(format!("{p}.get2[{i}]"), noise.get2(x, z));
    }
    let volume = DensityVolume::new([4, 6, 3], [-17, -64, 33], [2, 8, 4]);
    let mut buffer = vec![0f32; volume.size()];
    noise.add_to_volume(&mut buffer, &volume, 0.03, 0.07, 0.75);
    noise.add_to_volume(&mut buffer, &volume, 1.7, 0.4, -1.25);
    for (i, v) in buffer.iter().enumerate() {
        o.f(format!("{p}.volume[{i}]"), *v);
    }
}

#[test]
fn noise_matches_vanilla() {
    let Some(mut o) = Oracle::load("noise") else { return };
    for seed in [0i64, 42, -123456789] {
        let s = format!("[{seed}]");
        sample(&mut o, &format!("perlin{s}"), &PerlinNoise::new(&mut XoroshiroRandom::new(seed)));
        sample(&mut o, &format!("perlinLegacy{s}"), &PerlinNoise::new(&mut LegacyRandom::new(seed)));
        sample(&mut o, &format!("smeared{s}"), &SmearedPerlinNoise::new(&mut XoroshiroRandom::new(seed), 4.2));
        sample(&mut o, &format!("simplex{s}"), &SimplexNoise::new(&mut XoroshiroRandom::new(seed)));
        let normal = NormalNoise::new(NormalNoiseParameters {
            base_amplitude: 1.0,
            base_octave: -7,
            octave_count: 3,
            normalize: Normalization::Enabled,
            amplitude_modifiers: vec![],
        });
        sample(&mut o, &format!("normal{s}"), &normal.create(&mut XoroshiroRandom::new(seed)));
        sample(&mut o, &format!("normalLegacyRandom{s}"), &normal.create(&mut LegacyRandom::new(seed)));
        let parity = NormalNoise::create_parity(-9, &[1.0, 0.0, 3.0, 1.5]);
        sample(&mut o, &format!("parity{s}"), &parity.create(&mut XoroshiroRandom::new(seed)));
        let legacy = NormalNoise::new(NormalNoiseParameters {
            base_amplitude: 1.0,
            base_octave: -4,
            octave_count: 4,
            normalize: Normalization::Legacy,
            amplitude_modifiers: vec![1.0, 0.5, 1.0, 1.0],
        });
        sample(&mut o, &format!("legacyNorm{s}"), &legacy.create(&mut XoroshiroRandom::new(seed)));
        let fbm = create_blended_fbm(&mut XoroshiroRandom::new(seed), -15, 684.412 * 0.125 * 8.0, 0.99998474_f32 as f64);
        sample(&mut o, &format!("fbm{s}"), &fbm);
    }
    o.check();
}
