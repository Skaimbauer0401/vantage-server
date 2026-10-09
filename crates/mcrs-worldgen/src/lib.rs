//! Bit-exact Rust port of Minecraft 26.3 world generation.
//!
//! Every module is checked against vanilla itself: `tools/oracle` runs vanilla's
//! classes and writes reference values that the tests in `tests/` compare against.

pub mod jmath;
pub mod random;
