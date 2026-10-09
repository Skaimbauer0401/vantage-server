//! The hoisted volume sampling must equal the straightforward loop bit for bit.

use mcrs_worldgen::noise::{DensityVolume, Noise, PerlinNoise, SmearedPerlinNoise};
use mcrs_worldgen::random::{RandomSource, XoroshiroRandom};

fn volumes(random: &mut XoroshiroRandom) -> Vec<DensityVolume> {
    let mut out = vec![
        // Interpolation corners and a full chunk column, as the fill uses them.
        DensityVolume::new([5, 49, 5], [-4800, -64, 3200], [4, 8, 4]),
        DensityVolume::new([16, 384, 16], [160, -64, -320], [1, 1, 1]),
        DensityVolume::new([1, 1, 1], [0, 0, 0], [1, 1, 1]),
    ];
    for _ in 0..40 {
        let size = [1 + random.next_int_bounded(6), 1 + random.next_int_bounded(60), 1 + random.next_int_bounded(6)];
        // Include coordinates far out, where `wrap` changes them.
        let far = if random.next_int_bounded(3) == 0 { 30_000_000 } else { 5_000 };
        let min = [random.next_int_bounded(2 * far) - far, random.next_int_bounded(400) - 64, random.next_int_bounded(2 * far) - far];
        let step = [1 + random.next_int_bounded(4), 1 + random.next_int_bounded(8), 1 + random.next_int_bounded(4)];
        out.push(DensityVolume::new(size, min, step));
    }
    out
}

fn bits(values: &[f32]) -> Vec<u32> {
    values.iter().map(|v| v.to_bits()).collect()
}

#[test]
fn perlin_volume_matches_reference() {
    let mut random = XoroshiroRandom::new(1234);
    for _ in 0..20 {
        let noise = PerlinNoise::new(&mut random);
        let smeared = SmearedPerlinNoise { perlin: noise.clone(), fudge_y_scale: [0.5, 1.0, 2.0, 0.125][random.next_int_bounded(4) as usize] };
        for volume in volumes(&mut random) {
            let scales = [(1.0, 1.0), (0.25, 0.125), (684.412 / 128.0, 684.412 / 64.0), (0.0016, 0.0), (171.103, 85.5515)];
            for (xz, y) in scales {
                let initial: Vec<f32> = (0..volume.size()).map(|i| (i % 7) as f32 * 0.375).collect();
                let amplitude = 0.73;

                let mut fast = initial.clone();
                noise.add_to_volume(&mut fast, &volume, xz, y, amplitude);
                let mut reference = initial.clone();
                noise.add_to_volume_reference(&mut reference, &volume, xz, y, amplitude, None);
                assert_eq!(bits(&fast), bits(&reference), "perlin {volume:?} {xz} {y}");

                let mut fast = initial.clone();
                smeared.add_to_volume(&mut fast, &volume, xz, y, amplitude);
                let mut reference = initial.clone();
                smeared.perlin.add_to_volume_reference(&mut reference, &volume, xz, y, amplitude, Some(smeared.fudge_y_scale));
                assert_eq!(bits(&fast), bits(&reference), "smeared {volume:?} {xz} {y}");
            }
        }
    }
}
