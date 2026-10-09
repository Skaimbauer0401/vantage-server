use std::io::Read;

use crate::{Error, Result};

/// Chunk compression as stored in the region file (vanilla `RegionFileVersion`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Compression {
    Gzip = 1,
    Zlib = 2,
    None = 3,
    /// lz4-java `LZ4BlockOutputStream` framing, not the standard LZ4 frame format.
    Lz4 = 4,
}

impl Compression {
    pub fn from_id(id: u8) -> Result<Compression> {
        Ok(match id {
            1 => Compression::Gzip,
            2 => Compression::Zlib,
            3 => Compression::None,
            4 => Compression::Lz4,
            other => return Err(Error::UnknownCompression(other)),
        })
    }

    pub fn decompress(self, data: &[u8]) -> Result<Vec<u8>> {
        let mut out = Vec::with_capacity(data.len() * 4);
        match self {
            Compression::Gzip => {
                flate2::read::GzDecoder::new(data).read_to_end(&mut out)?;
            }
            Compression::Zlib => {
                flate2::read::ZlibDecoder::new(data).read_to_end(&mut out)?;
            }
            Compression::None => out.extend_from_slice(data),
            Compression::Lz4 => decompress_lz4_block_stream(data, &mut out)?,
        }
        Ok(out)
    }
}

const LZ4_MAGIC: &[u8; 8] = b"LZ4Block";
const LZ4_HEADER_LEN: usize = 8 + 1 + 4 + 4 + 4;
const LZ4_METHOD_RAW: u8 = 0x10;
const LZ4_METHOD_LZ4: u8 = 0x20;

/// Decodes lz4-java's block stream: repeated `LZ4Block` headers, each followed by a
/// raw or LZ4-compressed block, terminated by an empty block. Checksums are not verified.
fn decompress_lz4_block_stream(mut data: &[u8], out: &mut Vec<u8>) -> Result<()> {
    while !data.is_empty() {
        if data.len() < LZ4_HEADER_LEN || &data[..8] != LZ4_MAGIC {
            return Err(Error::Corrupt("bad LZ4Block header"));
        }
        let method = data[8] & 0xF0;
        let compressed_len = u32::from_le_bytes(data[9..13].try_into().unwrap()) as usize;
        let decompressed_len = u32::from_le_bytes(data[13..17].try_into().unwrap()) as usize;
        data = &data[LZ4_HEADER_LEN..];
        if decompressed_len == 0 {
            return Ok(());
        }
        let block = data.get(..compressed_len).ok_or(Error::Corrupt("truncated LZ4 block"))?;
        match method {
            LZ4_METHOD_RAW => out.extend_from_slice(block),
            LZ4_METHOD_LZ4 => {
                let start = out.len();
                out.resize(start + decompressed_len, 0);
                let n = lz4_flex::block::decompress_into(block, &mut out[start..])?;
                if n != decompressed_len {
                    return Err(Error::Corrupt("LZ4 block length mismatch"));
                }
            }
            _ => return Err(Error::Corrupt("unknown LZ4Block method")),
        }
        data = &data[compressed_len..];
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn zlib_round_trip() {
        let input = b"hello chunk hello chunk hello chunk".repeat(50);
        let mut enc = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::default());
        enc.write_all(&input).unwrap();
        let compressed = enc.finish().unwrap();
        assert_eq!(Compression::Zlib.decompress(&compressed).unwrap(), input);
    }

    #[test]
    fn lz4_block_stream() {
        let input = b"abcabcabcabcabcabcabcabc".repeat(100);
        let block = lz4_flex::block::compress(&input);
        let mut stream = Vec::new();
        for (method, payload, len) in [(LZ4_METHOD_LZ4, &block[..], input.len()), (LZ4_METHOD_RAW, &[][..], 0)] {
            stream.extend_from_slice(LZ4_MAGIC);
            stream.push(method);
            stream.extend_from_slice(&(payload.len() as u32).to_le_bytes());
            stream.extend_from_slice(&(len as u32).to_le_bytes());
            stream.extend_from_slice(&0u32.to_le_bytes());
            stream.extend_from_slice(payload);
        }
        assert_eq!(Compression::Lz4.decompress(&stream).unwrap(), input);
    }
}
