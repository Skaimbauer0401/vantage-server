//! Java Edition NBT (big-endian) as a borrowed tree: arrays and strings point into
//! the input buffer, so parsing a chunk allocates only the tree nodes.
//!
//! Strings stay in Java's modified UTF-8. Block/biome identifiers are ASCII, where
//! that is identical to UTF-8; anything else simply won't match a registry key.

use crate::{Error, Result};

/// Vanilla's `NbtAccounter` default depth limit.
const MAX_DEPTH: usize = 512;

pub const TAG_END: u8 = 0;
pub const TAG_BYTE: u8 = 1;
pub const TAG_SHORT: u8 = 2;
pub const TAG_INT: u8 = 3;
pub const TAG_LONG: u8 = 4;
pub const TAG_FLOAT: u8 = 5;
pub const TAG_DOUBLE: u8 = 6;
pub const TAG_BYTE_ARRAY: u8 = 7;
pub const TAG_STRING: u8 = 8;
pub const TAG_LIST: u8 = 9;
pub const TAG_COMPOUND: u8 = 10;
pub const TAG_INT_ARRAY: u8 = 11;
pub const TAG_LONG_ARRAY: u8 = 12;

#[derive(Debug, Clone, PartialEq)]
pub enum Tag<'a> {
    Byte(i8),
    Short(i16),
    Int(i32),
    Long(i64),
    Float(f32),
    Double(f64),
    ByteArray(&'a [u8]),
    /// Modified UTF-8 bytes.
    String(&'a [u8]),
    List(List<'a>),
    Compound(Compound<'a>),
    /// Raw big-endian payload.
    IntArray(&'a [u8]),
    /// Raw big-endian payload.
    LongArray(&'a [u8]),
    /// An int array created in Rust (written big-endian).
    IntArrayOwned(Vec<i32>),
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct List<'a> {
    pub element_type: u8,
    pub items: Vec<Tag<'a>>,
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct Compound<'a> {
    pub entries: Vec<(&'a [u8], Tag<'a>)>,
}

impl<'a> Tag<'a> {
    pub fn type_id(&self) -> u8 {
        match self {
            Tag::Byte(_) => TAG_BYTE,
            Tag::Short(_) => TAG_SHORT,
            Tag::Int(_) => TAG_INT,
            Tag::Long(_) => TAG_LONG,
            Tag::Float(_) => TAG_FLOAT,
            Tag::Double(_) => TAG_DOUBLE,
            Tag::ByteArray(_) => TAG_BYTE_ARRAY,
            Tag::String(_) => TAG_STRING,
            Tag::List(_) => TAG_LIST,
            Tag::Compound(_) => TAG_COMPOUND,
            Tag::IntArray(_) | Tag::IntArrayOwned(_) => TAG_INT_ARRAY,
            Tag::LongArray(_) => TAG_LONG_ARRAY,
        }
    }

    pub fn as_compound(&self) -> Option<&Compound<'a>> {
        match self {
            Tag::Compound(c) => Some(c),
            _ => None,
        }
    }

    pub fn as_compound_mut(&mut self) -> Option<&mut Compound<'a>> {
        match self {
            Tag::Compound(c) => Some(c),
            _ => None,
        }
    }

    pub fn as_list(&self) -> Option<&List<'a>> {
        match self {
            Tag::List(l) => Some(l),
            _ => None,
        }
    }

    pub fn as_list_mut(&mut self) -> Option<&mut List<'a>> {
        match self {
            Tag::List(l) => Some(l),
            _ => None,
        }
    }

    pub fn as_str_bytes(&self) -> Option<&'a [u8]> {
        match self {
            Tag::String(s) => Some(s),
            _ => None,
        }
    }

    /// Value of any integral tag, like vanilla's `NumericTag.intValue()`.
    pub fn as_int(&self) -> Option<i32> {
        match *self {
            Tag::Byte(v) => Some(v as i32),
            Tag::Short(v) => Some(v as i32),
            Tag::Int(v) => Some(v),
            Tag::Long(v) => Some(v as i32),
            _ => None,
        }
    }
}

impl<'a> Compound<'a> {
    pub fn get(&self, key: &str) -> Option<&Tag<'a>> {
        self.entries.iter().find(|(k, _)| *k == key.as_bytes()).map(|(_, v)| v)
    }

    pub fn get_mut(&mut self, key: &str) -> Option<&mut Tag<'a>> {
        self.entries.iter_mut().find(|(k, _)| *k == key.as_bytes()).map(|(_, v)| v)
    }

    pub fn remove(&mut self, key: &str) -> Option<Tag<'a>> {
        let i = self.entries.iter().position(|(k, _)| *k == key.as_bytes())?;
        Some(self.entries.remove(i).1)
    }

    /// Inserts or replaces, like `CompoundTag.put`.
    pub fn insert(&mut self, key: &'a str, value: Tag<'a>) {
        match self.get_mut(key) {
            Some(slot) => *slot = value,
            None => self.entries.push((key.as_bytes(), value)),
        }
    }
}

struct Reader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        let end = self.pos.checked_add(n).filter(|&e| e <= self.data.len());
        let end = end.ok_or(Error::Corrupt("NBT truncated"))?;
        let slice = &self.data[self.pos..end];
        self.pos = end;
        Ok(slice)
    }

    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }

    fn i16(&mut self) -> Result<i16> {
        Ok(i16::from_be_bytes(self.take(2)?.try_into().unwrap()))
    }

    fn i32(&mut self) -> Result<i32> {
        Ok(i32::from_be_bytes(self.take(4)?.try_into().unwrap()))
    }

    fn i64(&mut self) -> Result<i64> {
        Ok(i64::from_be_bytes(self.take(8)?.try_into().unwrap()))
    }

    fn string(&mut self) -> Result<&'a [u8]> {
        let len = u16::from_be_bytes(self.take(2)?.try_into().unwrap()) as usize;
        self.take(len)
    }

    fn array(&mut self, element_size: usize) -> Result<&'a [u8]> {
        let len = self.i32()?;
        if len < 0 {
            return Err(Error::Corrupt("negative NBT array length"));
        }
        self.take(len as usize * element_size)
    }

    fn payload(&mut self, tag_type: u8, depth: usize) -> Result<Tag<'a>> {
        if depth > MAX_DEPTH {
            return Err(Error::Corrupt("NBT nested too deeply"));
        }
        Ok(match tag_type {
            TAG_BYTE => Tag::Byte(self.u8()? as i8),
            TAG_SHORT => Tag::Short(self.i16()?),
            TAG_INT => Tag::Int(self.i32()?),
            TAG_LONG => Tag::Long(self.i64()?),
            TAG_FLOAT => Tag::Float(f32::from_bits(self.i32()? as u32)),
            TAG_DOUBLE => Tag::Double(f64::from_bits(self.i64()? as u64)),
            TAG_BYTE_ARRAY => Tag::ByteArray(self.array(1)?),
            TAG_STRING => Tag::String(self.string()?),
            TAG_LIST => {
                let element_type = self.u8()?;
                let len = self.i32()?;
                if len < 0 {
                    return Err(Error::Corrupt("negative NBT list length"));
                }
                if element_type == TAG_END && len > 0 {
                    return Err(Error::Corrupt("non-empty NBT list of TAG_End"));
                }
                // Cap the preallocation: the length is untrusted.
                let mut items = Vec::with_capacity((len as usize).min(1024));
                for _ in 0..len {
                    items.push(self.payload(element_type, depth + 1)?);
                }
                Tag::List(List { element_type, items })
            }
            TAG_COMPOUND => {
                let mut entries = Vec::new();
                loop {
                    let t = self.u8()?;
                    if t == TAG_END {
                        break;
                    }
                    let name = self.string()?;
                    entries.push((name, self.payload(t, depth + 1)?));
                }
                Tag::Compound(Compound { entries })
            }
            TAG_INT_ARRAY => Tag::IntArray(self.array(4)?),
            TAG_LONG_ARRAY => Tag::LongArray(self.array(8)?),
            _ => return Err(Error::Corrupt("unknown NBT tag type")),
        })
    }
}

/// Parses a named root compound as written by `NbtIo.write`. Returns (name, root).
pub fn parse_root(data: &[u8]) -> Result<(&[u8], Compound<'_>)> {
    let mut r = Reader { data, pos: 0 };
    if r.u8()? != TAG_COMPOUND {
        return Err(Error::Corrupt("NBT root is not a compound"));
    }
    let name = r.string()?;
    let Tag::Compound(root) = r.payload(TAG_COMPOUND, 0)? else { unreachable!() };
    Ok((name, root))
}

/// Serializes a named root compound, inverse of [`parse_root`].
pub fn write_root(name: &[u8], root: &Compound<'_>, out: &mut Vec<u8>) {
    out.push(TAG_COMPOUND);
    write_string(name, out);
    write_compound(root, out);
}

fn write_string(s: &[u8], out: &mut Vec<u8>) {
    out.extend_from_slice(&(s.len() as u16).to_be_bytes());
    out.extend_from_slice(s);
}

fn write_compound(c: &Compound<'_>, out: &mut Vec<u8>) {
    for (name, value) in &c.entries {
        out.push(value.type_id());
        write_string(name, out);
        write_payload(value, out);
    }
    out.push(TAG_END);
}

fn write_payload(tag: &Tag<'_>, out: &mut Vec<u8>) {
    match tag {
        Tag::Byte(v) => out.push(*v as u8),
        Tag::Short(v) => out.extend_from_slice(&v.to_be_bytes()),
        Tag::Int(v) => out.extend_from_slice(&v.to_be_bytes()),
        Tag::Long(v) => out.extend_from_slice(&v.to_be_bytes()),
        Tag::Float(v) => out.extend_from_slice(&v.to_bits().to_be_bytes()),
        Tag::Double(v) => out.extend_from_slice(&v.to_bits().to_be_bytes()),
        Tag::ByteArray(b) => {
            out.extend_from_slice(&(b.len() as i32).to_be_bytes());
            out.extend_from_slice(b);
        }
        Tag::String(s) => write_string(s, out),
        Tag::List(l) => {
            out.push(l.element_type);
            out.extend_from_slice(&(l.items.len() as i32).to_be_bytes());
            for item in &l.items {
                write_payload(item, out);
            }
        }
        Tag::Compound(c) => write_compound(c, out),
        Tag::IntArray(raw) => {
            out.extend_from_slice(&((raw.len() / 4) as i32).to_be_bytes());
            out.extend_from_slice(raw);
        }
        Tag::LongArray(raw) => {
            out.extend_from_slice(&((raw.len() / 8) as i32).to_be_bytes());
            out.extend_from_slice(raw);
        }
        Tag::IntArrayOwned(values) => {
            out.extend_from_slice(&(values.len() as i32).to_be_bytes());
            for v in values {
                out.extend_from_slice(&v.to_be_bytes());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Vec<u8> {
        let mut root = Compound::default();
        root.insert("DataVersion", Tag::Int(4000));
        root.insert("name", Tag::String(b"minecraft:stone"));
        root.insert("longs", Tag::LongArray(&[0, 0, 0, 0, 0, 0, 0, 7]));
        root.insert("ints", Tag::IntArrayOwned(vec![1, -2]));
        root.insert("d", Tag::Double(1.5));
        let mut inner = Compound::default();
        inner.insert("b", Tag::Byte(-1));
        root.insert(
            "list",
            Tag::List(List { element_type: TAG_COMPOUND, items: vec![Tag::Compound(inner)] }),
        );
        root.insert("empty", Tag::List(List { element_type: TAG_END, items: vec![] }));
        let mut out = Vec::new();
        write_root(b"", &root, &mut out);
        out
    }

    #[test]
    fn round_trip_is_byte_identical() {
        let bytes = sample();
        let (name, root) = parse_root(&bytes).unwrap();
        assert_eq!(root.get("DataVersion").and_then(Tag::as_int), Some(4000));
        assert_eq!(root.get("name").and_then(Tag::as_str_bytes), Some(&b"minecraft:stone"[..]));
        let mut again = Vec::new();
        write_root(name, &root, &mut again);
        // Owned int arrays parse back as borrowed ones, which serialize identically.
        assert_eq!(again, bytes);
    }

    #[test]
    fn rejects_truncated_input() {
        let bytes = sample();
        for cut in [1, 5, bytes.len() / 2, bytes.len() - 1] {
            assert!(parse_root(&bytes[..cut]).is_err(), "cut at {cut}");
        }
    }
}
