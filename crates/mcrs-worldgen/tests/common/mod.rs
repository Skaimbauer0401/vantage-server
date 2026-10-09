//! Compares Rust output with reference values written by `tools/oracle` (vanilla).

use std::fmt::Display;
use std::path::PathBuf;

pub struct Oracle {
    expected: Vec<String>,
    actual: Vec<String>,
    suite: String,
}

impl Oracle {
    /// Loads `target/oracle/<suite>.txt`, or returns `None` (test skipped) if the
    /// oracle hasn't been run yet.
    pub fn load(suite: &str) -> Option<Oracle> {
        let dir = std::env::var_os("MCRS_ORACLE_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../target/oracle"));
        let path = dir.join(format!("{suite}.txt"));
        let Ok(text) = std::fs::read_to_string(&path) else {
            eprintln!("skipping: {} missing, run tools/oracle/run.sh", path.display());
            return None;
        };
        Some(Oracle {
            expected: text.lines().map(str::to_owned).collect(),
            actual: Vec::new(),
            suite: suite.to_owned(),
        })
    }

    pub fn line(&mut self, key: impl Display, value: impl Display) {
        self.actual.push(format!("{key} {value}"));
    }

    pub fn d(&mut self, key: impl Display, value: f64) {
        self.line(key, value.to_bits() as i64);
    }

    pub fn f(&mut self, key: impl Display, value: f32) {
        self.line(key, value.to_bits() as i32);
    }

    /// Panics at the first differing line.
    pub fn check(self) {
        for (i, (e, a)) in self.expected.iter().zip(&self.actual).enumerate() {
            assert_eq!(a, e, "{}: first mismatch at line {}", self.suite, i + 1);
        }
        assert_eq!(self.actual.len(), self.expected.len(), "{}: line count differs", self.suite);
    }
}
