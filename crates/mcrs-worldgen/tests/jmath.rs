use std::path::PathBuf;

use mcrs_worldgen::jmath;

/// Every line of the oracle's `math.txt`: `<fn> <input bits...> <output bits>`.
#[test]
fn fdlibm_matches_vanilla() {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../target/oracle/math.txt");
    let Ok(text) = std::fs::read_to_string(&path) else {
        eprintln!("skipping: {} missing, run tools/oracle/run.sh", path.display());
        return;
    };
    let bits = |s: &str| f64::from_bits(s.parse::<i64>().unwrap() as u64);
    let mut failures = Vec::new();
    let mut checked = 0;
    for line in text.lines() {
        let parts: Vec<&str> = line.split(' ').collect();
        let (actual, expected) = match parts[0] {
            "log" => (jmath::log(bits(parts[1])), parts[2]),
            "exp" => (jmath::exp(bits(parts[1])), parts[2]),
            "sin" => (jmath::sin(bits(parts[1])), parts[2]),
            "cos" => (jmath::cos(bits(parts[1])), parts[2]),
            "pow" => (jmath::pow(bits(parts[1]), bits(parts[2])), parts[3]),
            other => panic!("unknown function {other}"),
        };
        checked += 1;
        let expected = bits(expected);
        // NaN payloads aren't specified; any NaN matches any NaN.
        if actual.to_bits() != expected.to_bits() && !(actual.is_nan() && expected.is_nan()) {
            failures.push(format!("{line} -> got {} ({actual:e}), want {expected:e}", actual.to_bits() as i64));
        }
    }
    assert!(failures.is_empty(), "{} of {checked} differ, first: {:#?}", failures.len(), &failures[..failures.len().min(10)]);
}
