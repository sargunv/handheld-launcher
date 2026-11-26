//! `PARAM.SFO`, the key-value file Sony's later consoles describe a game with:
//! on a PSP disc at `PSP_GAME/PARAM.SFO`, and first in a PBP (`EBOOT.PBP`),
//! as the PSP's digital games and PlayStation games converted for it keep
//! them.
//!
//! - <https://www.psdevwiki.com/psp/Param.sfo>
//! - <https://www.psdevwiki.com/psp/PBP>

use std::io::{Read, Seek};

use crate::bytes::{array_at, le16, le32, vec_at};
use crate::{Error, Result};

/// The most of a PARAM.SFO it reads.
pub(crate) const MAX_SIZE: u64 = 1 << 16;

/// A value in a PARAM.SFO.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Value {
    Text(String),
    Integer(u32),
    /// A value in a format it doesn't know.
    Bytes(Vec<u8>),
}

impl core::fmt::Display for Value {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            Self::Text(text) => f.write_str(text),
            Self::Integer(n) => write!(f, "{n}"),
            Self::Bytes(bytes) => bytes.iter().try_for_each(|b| write!(f, "{b:02X}")),
        }
    }
}

/// A PARAM.SFO's entries, in the order it lists them.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ParamSfo {
    pub entries: Vec<(String, Value)>,
}

impl ParamSfo {
    /// The value of `key`.
    pub fn get(&self, key: &str) -> Option<&Value> {
        self.entries.iter().find(|(k, _)| k == key).map(|(_, v)| v)
    }

    /// The text value of `key`.
    pub fn text(&self, key: &str) -> Option<&str> {
        match self.get(key)? {
            Value::Text(text) => Some(text),
            _ => None,
        }
    }

    /// The integer value of `key`.
    pub fn integer(&self, key: &str) -> Option<u32> {
        match self.get(key)? {
            Value::Integer(n) => Some(*n),
            _ => None,
        }
    }

    /// `TITLE`.
    pub fn title(&self) -> Option<&str> {
        self.text("TITLE")
    }

    /// `DISC_ID`, such as `ULUS10041`, or a digital game's `TITLE_ID`.
    pub fn disc_id(&self) -> Option<&str> {
        self.text("DISC_ID").or_else(|| self.text("TITLE_ID"))
    }

    /// `CATEGORY`, such as `UG` for a UMD game or `ME` for a PlayStation
    /// game.
    pub fn category(&self) -> Option<&str> {
        self.text("CATEGORY")
    }

    /// `DISC_VERSION`, such as `1.00`.
    pub fn disc_version(&self) -> Option<&str> {
        self.text("DISC_VERSION")
    }

    /// `APP_VER`, the version of a digital game or its update.
    pub fn app_version(&self) -> Option<&str> {
        self.text("APP_VER")
    }

    /// `PSP_SYSTEM_VER`: the system software the game needs, such as `6.20`.
    pub fn system_version(&self) -> Option<&str> {
        self.text("PSP_SYSTEM_VER")
    }

    /// `REGION`, a bit field.
    pub fn region(&self) -> Option<u32> {
        self.integer("REGION")
    }
}

/// Parses a PARAM.SFO.
pub fn parse(sfo: &[u8]) -> Result<ParamSfo> {
    let invalid = || Error::NotRecognized("PARAM.SFO");
    if sfo.len() < 20 || &sfo[..4] != b"\0PSF" {
        return Err(invalid());
    }
    let keys = le32(sfo, 8) as usize;
    let data = le32(sfo, 12) as usize;
    let count = le32(sfo, 16) as usize;
    let mut entries = Vec::new();
    for i in 0..count {
        let entry = sfo.get(20 + i * 16..20 + i * 16 + 16).ok_or_else(invalid)?;
        let key_start = keys + usize::from(le16(entry, 0));
        let format = le16(entry, 2);
        let length = le32(entry, 4) as usize;
        let value_start = data + le32(entry, 12) as usize;
        let key = sfo.get(key_start..).ok_or_else(invalid)?;
        let key = &key[..key.iter().position(|&c| c == 0).ok_or_else(invalid)?];
        let value = sfo
            .get(value_start..value_start + length)
            .ok_or_else(invalid)?;
        let value = match format {
            // UTF-8, with or without its NUL.
            0x0004 | 0x0204 => {
                let text = &value[..value.iter().position(|&c| c == 0).unwrap_or(value.len())];
                Value::Text(String::from_utf8_lossy(text).trim().to_owned())
            }
            0x0404 if length >= 4 => Value::Integer(le32(value, 0)),
            _ => Value::Bytes(value.to_vec()),
        };
        entries.push((String::from_utf8_lossy(key).into_owned(), value));
    }
    Ok(ParamSfo { entries })
}

/// Reads the PARAM.SFO that starts a PBP, if `reader` holds one; None when
/// it holds something else.
pub(crate) fn read_pbp<R: Read + Seek + ?Sized>(reader: &mut R) -> Result<Option<ParamSfo>> {
    let Ok(h) = array_at::<0x10, _>(reader, 0) else {
        return Ok(None);
    };
    if &h[..4] != b"\0PBP" {
        return Ok(None);
    }
    let start = u64::from(le32(&h, 8));
    let end = u64::from(le32(&h, 12));
    let size = end
        .checked_sub(start)
        .filter(|&n| n <= MAX_SIZE)
        .ok_or(Error::NotRecognized("PBP"))?;
    parse(&vec_at(reader, start, size as usize).map_err(Error::short("PBP"))?).map(Some)
}

#[cfg(test)]
pub(crate) mod tests {
    use std::io::Cursor;

    use super::*;

    /// A PARAM.SFO of text and integer entries.
    pub(crate) fn sfo(text: &[(&str, &str)], integers: &[(&str, u32)]) -> Vec<u8> {
        let count = text.len() + integers.len();
        let mut keys = Vec::new();
        let mut data = Vec::new();
        let mut index = Vec::new();
        let mut add = |key: &str, format: u16, value: Vec<u8>, max: u32| {
            index.extend_from_slice(&(keys.len() as u16).to_le_bytes());
            index.extend_from_slice(&format.to_le_bytes());
            index.extend_from_slice(&(value.len() as u32).to_le_bytes());
            index.extend_from_slice(&max.to_le_bytes());
            index.extend_from_slice(&(data.len() as u32).to_le_bytes());
            keys.extend_from_slice(key.as_bytes());
            keys.push(0);
            data.extend(value);
        };
        for (key, value) in text {
            let mut bytes = value.as_bytes().to_vec();
            bytes.push(0);
            add(key, 0x0204, bytes, 0);
        }
        for (key, value) in integers {
            add(key, 0x0404, value.to_le_bytes().to_vec(), 4);
        }
        keys.resize(keys.len().div_ceil(4) * 4, 0);
        let key_table = 20 + index.len();
        let data_table = key_table + keys.len();
        let mut file = b"\0PSF\x01\x01\0\0".to_vec();
        file.extend_from_slice(&(key_table as u32).to_le_bytes());
        file.extend_from_slice(&(data_table as u32).to_le_bytes());
        file.extend_from_slice(&(count as u32).to_le_bytes());
        file.extend(index);
        file.extend(keys);
        file.extend(data);
        file
    }

    #[test]
    fn parses_entries() {
        let sfo = parse(&sfo(
            &[
                ("CATEGORY", "UG"),
                ("DISC_ID", "ULUS10041"),
                ("TITLE", "Lumines"),
            ],
            &[("PARENTAL_LEVEL", 3), ("REGION", 0x8000)],
        ))
        .unwrap();
        assert_eq!(sfo.title(), Some("Lumines"));
        assert_eq!(sfo.disc_id(), Some("ULUS10041"));
        assert_eq!(sfo.category(), Some("UG"));
        assert_eq!(sfo.region(), Some(0x8000));
        assert_eq!(sfo.integer("PARENTAL_LEVEL"), Some(3));
        assert_eq!(sfo.get("NOPE"), None);
    }

    #[test]
    fn reads_a_pbp() {
        let param = sfo(
            &[("DISC_ID", "SLUS00594"), ("TITLE", "Metal Gear Solid")],
            &[],
        );
        let mut pbp = b"\0PBP\0\0\x01\0".to_vec();
        pbp.extend_from_slice(&0x28u32.to_le_bytes());
        pbp.extend_from_slice(&(0x28 + param.len() as u32).to_le_bytes());
        pbp.resize(0x28, 0);
        pbp.extend(param);
        let sfo = read_pbp(&mut Cursor::new(pbp)).unwrap().unwrap();
        assert_eq!(sfo.disc_id(), Some("SLUS00594"));
        assert_eq!(read_pbp(&mut Cursor::new(vec![0u8; 64])).unwrap(), None);
    }

    #[test]
    fn refuses_a_broken_file() {
        let mut broken = sfo(&[("TITLE", "Lumines")], &[]);
        broken.truncate(30);
        assert!(parse(&broken).is_err());
        assert!(parse(b"nope").is_err());
    }
}
