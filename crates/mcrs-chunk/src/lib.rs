//! Anvil region files (`r.<x>.<z>.mca`) and chunk compression, byte-compatible
//! with vanilla's `RegionFile` / `RegionFileVersion`.

pub mod compression;
pub mod nbt;
pub mod palette;
pub mod region;

pub use compression::Compression;
pub use region::{RegionFile, RegionPos};

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("unknown chunk compression type {0}")]
    UnknownCompression(u8),
    #[error("corrupt chunk: {0}")]
    Corrupt(&'static str),
    #[error("LZ4 decompression failed: {0}")]
    Lz4(#[from] lz4_flex::block::DecompressError),
}

pub type Result<T, E = Error> = std::result::Result<T, E>;
