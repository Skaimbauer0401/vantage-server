use std::fs::File;
use std::path::{Path, PathBuf};

use crate::{Compression, Error, Result};

const SECTOR_BYTES: usize = 4096;
const HEADER_BYTES: usize = 2 * SECTOR_BYTES;
/// High bit of the compression byte: payload lives in an external `c.<x>.<z>.mcc` file.
const EXTERNAL_FLAG: u8 = 0x80;

/// Region coordinates (one region = 32x32 chunks).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct RegionPos {
    pub x: i32,
    pub z: i32,
}

impl RegionPos {
    /// Parses `r.<x>.<z>.mca`.
    pub fn from_file_name(name: &str) -> Option<RegionPos> {
        let mut parts = name.strip_prefix("r.")?.strip_suffix(".mca")?.split('.');
        let x = parts.next()?.parse().ok()?;
        let z = parts.next()?.parse().ok()?;
        parts.next().is_none().then_some(RegionPos { x, z })
    }
}

/// A region file opened for reading. All reads are positional, so one `RegionFile`
/// can be shared between threads.
///
/// The header is snapshotted at open for [`RegionFile::chunks`], but every chunk
/// read fetches its location entry from disk again: the server keeps writing to
/// the same file while mcrs reads it.
pub struct RegionFile {
    path: PathBuf,
    pos: RegionPos,
    file: File,
    header: Box<[u8; HEADER_BYTES]>,
}

impl RegionFile {
    pub fn open(path: impl AsRef<Path>) -> Result<RegionFile> {
        let path = path.as_ref().to_path_buf();
        let pos = path
            .file_name()
            .and_then(|n| RegionPos::from_file_name(&n.to_string_lossy()))
            .ok_or(Error::Corrupt("region file name is not r.<x>.<z>.mca"))?;
        let file = File::open(&path)?;
        let mut header = Box::new([0u8; HEADER_BYTES]);
        let len = file.metadata()?.len() as usize;
        // Vanilla treats a truncated header as an empty region.
        if len >= HEADER_BYTES {
            read_exact_at(&file, &mut header[..], 0)?;
        }
        Ok(RegionFile { path, pos, file, header })
    }

    pub fn pos(&self) -> RegionPos {
        self.pos
    }

    fn location(&self, local_x: usize, local_z: usize) -> (usize, usize) {
        let i = 4 * (local_x + local_z * 32);
        let entry = u32::from_be_bytes(self.header[i..i + 4].try_into().unwrap());
        ((entry >> 8) as usize, (entry & 0xFF) as usize)
    }

    /// Local coordinates (0..32) of all chunks present in this region.
    pub fn chunks(&self) -> impl Iterator<Item = (usize, usize)> + '_ {
        (0..32 * 32)
            .map(|i| (i % 32, i / 32))
            .filter(|&(x, z)| self.location(x, z).0 != 0)
    }

    /// Last save time of a chunk, seconds since the Unix epoch.
    pub fn timestamp(&self, local_x: usize, local_z: usize) -> u32 {
        let i = SECTOR_BYTES + 4 * (local_x + local_z * 32);
        u32::from_be_bytes(self.header[i..i + 4].try_into().unwrap())
    }

    fn live_location(&self, local_x: usize, local_z: usize) -> Result<(usize, usize)> {
        let mut entry = [0u8; 4];
        match read_exact_at(&self.file, &mut entry, 4 * (local_x + local_z * 32) as u64) {
            Ok(()) => {}
            // Shorter than the header: vanilla treats it as an empty region.
            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok((0, 0)),
            Err(e) => return Err(e.into()),
        }
        let entry = u32::from_be_bytes(entry);
        Ok(((entry >> 8) as usize, (entry & 0xFF) as usize))
    }

    /// Returns the compression and still-compressed payload, or `None` if absent.
    pub fn read_raw(&self, local_x: usize, local_z: usize) -> Result<Option<(Compression, Vec<u8>)>> {
        assert!(local_x < 32 && local_z < 32);
        let (sector, sectors) = self.live_location(local_x, local_z)?;
        if sector < 2 || sectors == 0 {
            return Ok(None);
        }
        let mut buf = vec![0u8; sectors * SECTOR_BYTES];
        read_exact_at(&self.file, &mut buf, (sector * SECTOR_BYTES) as u64)?;
        let length = u32::from_be_bytes(buf[0..4].try_into().unwrap()) as usize;
        if length == 0 || length > buf.len() - 4 {
            return Err(Error::Corrupt("chunk length outside its sectors"));
        }
        let kind = buf[4];
        let compression = Compression::from_id(kind & !EXTERNAL_FLAG)?;
        if kind & EXTERNAL_FLAG != 0 {
            let chunk_x = self.pos.x * 32 + local_x as i32;
            let chunk_z = self.pos.z * 32 + local_z as i32;
            let external = self.path.with_file_name(format!("c.{chunk_x}.{chunk_z}.mcc"));
            return Ok(Some((compression, std::fs::read(external)?)));
        }
        buf.truncate(4 + length);
        buf.drain(..5);
        Ok(Some((compression, buf)))
    }

    /// Returns the decompressed chunk NBT, or `None` if absent.
    pub fn read(&self, local_x: usize, local_z: usize) -> Result<Option<Vec<u8>>> {
        match self.read_raw(local_x, local_z)? {
            Some((compression, data)) => Ok(Some(compression.decompress(&data)?)),
            None => Ok(None),
        }
    }
}

#[cfg(windows)]
fn read_exact_at(file: &File, mut buf: &mut [u8], mut offset: u64) -> std::io::Result<()> {
    use std::os::windows::fs::FileExt;
    while !buf.is_empty() {
        match file.seek_read(buf, offset)? {
            0 => return Err(std::io::ErrorKind::UnexpectedEof.into()),
            n => {
                buf = &mut buf[n..];
                offset += n as u64;
            }
        }
    }
    Ok(())
}

#[cfg(unix)]
fn read_exact_at(file: &File, buf: &mut [u8], offset: u64) -> std::io::Result<()> {
    std::os::unix::fs::FileExt::read_exact_at(file, buf, offset)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_region_names() {
        assert_eq!(RegionPos::from_file_name("r.-3.12.mca"), Some(RegionPos { x: -3, z: 12 }));
        assert_eq!(RegionPos::from_file_name("r.1.2.mcc"), None);
        assert_eq!(RegionPos::from_file_name("r.1.mca"), None);
    }
}
