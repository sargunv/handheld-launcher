//! The certificate in an Xbox executable (XBE), read from a bare XBE or from
//! the `default.xbe` of a disc image: an XISO, which holds only the game's
//! XDVDFS file system, or a full disc image, which holds it after the video
//! partition.
//!
//! - <https://xboxdevwiki.net/Xbe>
//! - <https://xboxdevwiki.net/XDVDFS>

use std::io::{Read, Seek};

use crate::bytes::{array_at, le16, le32, prefix_at, utf16le};
use crate::{Error, Result};

/// Where a full disc image's game partition starts.
const GAME_PARTITION: u64 = 0x1830_0000;

/// What holds the executable.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Container {
    Xbe,
    /// A disc image of the game partition alone.
    Xiso,
    /// A whole disc's image, video partition and all.
    Disc,
}

impl core::fmt::Display for Container {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str(match self {
            Self::Xbe => "XBE",
            Self::Xiso => "XISO",
            Self::Disc => "Disc image",
        })
    }
}

/// An Xbox executable's certificate.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Certificate {
    pub container: Container,
    /// The title id: the publisher's two letters in its high half, the game's
    /// number in its low half.
    pub title_id: u32,
    /// Up to 40 characters.
    pub title: String,
    /// When the certificate was made: seconds since 2000-01-01, though some
    /// tools wrote Unix time.
    pub timestamp: u32,
    /// Alternate title ids, whose saves the game may also read.
    pub alternate_title_ids: Vec<u32>,
    /// The media the game may run from, as flags.
    pub allowed_media: u32,
    /// The regions, as flags: 1 North America, 2 Japan, 4 the rest of the
    /// world, and $80000000 manufacturing.
    pub regions: u32,
    pub ratings: u32,
    /// The disc number, from 0.
    pub disc_number: u32,
    /// The certificate version: 0 for the first release.
    pub version: u32,
}

impl Certificate {
    /// The publisher code from the title id, such as `MS`.
    pub fn publisher_code(&self) -> String {
        crate::bytes::code(&self.title_id.to_be_bytes()[..2])
    }

    /// The title id as its publisher code and game number, such as
    /// `MS-001`.
    pub fn product_code(&self) -> String {
        format!("{}-{:03}", self.publisher_code(), self.title_id & 0xFFFF)
    }

    /// The names of the regions in [`Certificate::regions`].
    pub fn region_names(&self) -> Vec<&'static str> {
        [
            (1, "North America"),
            (2, "Japan"),
            (4, "Rest of the world"),
            (0x8000_0000, "Manufacturing"),
        ]
        .into_iter()
        .filter_map(|(bit, name)| (self.regions & bit != 0).then_some(name))
        .collect()
    }
}

/// Reads the certificate of an XBE, or of a disc image's `default.xbe`.
pub fn read<R: Read + Seek + ?Sized>(reader: &mut R) -> Result<Certificate> {
    let magic: [u8; 4] = array_at(reader, 0).map_err(Error::short("Xbox"))?;
    if &magic == b"XBEH" {
        return read_xbe(reader, 0, Container::Xbe);
    }
    for (base, container) in [(0, Container::Xiso), (GAME_PARTITION, Container::Disc)] {
        let Ok(volume) = array_at::<0x20, _>(reader, base + 0x10000) else {
            continue;
        };
        if &volume[..20] != b"MICROSOFT*XBOX*MEDIA" {
            continue;
        }
        let root = u64::from(le32(&volume, 0x14));
        let root_size = u64::from(le32(&volume, 0x18)).min(1 << 20);
        let listing = prefix_at(reader, base + root * 2048, root_size as usize)?;
        let sector = find(&listing, 0, b"default.xbe", 0)
            .ok_or(Error::NotFound("default.xbe".to_owned()))?;
        return read_xbe(reader, base + sector * 2048, container);
    }
    Err(Error::NotRecognized("Xbox"))
}

/// The sector of the file `name` in an XDVDFS directory, whose entries are a
/// binary tree ordered by name, ignoring case.
fn find(listing: &[u8], at: usize, name: &[u8], depth: u8) -> Option<u64> {
    let entry = listing.get(at..at + 14)?;
    let length = entry[13] as usize;
    let found = listing.get(at + 14..at + 14 + length)?;
    // A tree deeper than this loops.
    if length == 0 || depth > 64 {
        return None;
    }
    let order = name.to_ascii_lowercase().cmp(&found.to_ascii_lowercase());
    let next = match order {
        std::cmp::Ordering::Equal => return Some(u64::from(le32(entry, 4))),
        std::cmp::Ordering::Less => le16(entry, 0),
        std::cmp::Ordering::Greater => le16(entry, 2),
    };
    if next == 0 || next == 0xFFFF {
        return None;
    }
    find(listing, usize::from(next) * 4, name, depth + 1)
}

fn read_xbe<R: Read + Seek + ?Sized>(
    reader: &mut R,
    offset: u64,
    container: Container,
) -> Result<Certificate> {
    let h: [u8; 0x178] = array_at(reader, offset).map_err(Error::short("XBE"))?;
    if &h[..4] != b"XBEH" {
        return Err(Error::NotRecognized("XBE"));
    }
    let base = le32(&h, 0x104);
    let certificate = le32(&h, 0x118)
        .checked_sub(base)
        .ok_or(Error::NotRecognized("XBE"))?;
    let c: [u8; 0xB0] =
        array_at(reader, offset + u64::from(certificate)).map_err(Error::short("XBE"))?;
    Ok(Certificate {
        container,
        title_id: le32(&c, 0x08),
        title: utf16le(&c[0x0C..0x5C]),
        timestamp: le32(&c, 0x04),
        alternate_title_ids: (0..16)
            .map(|i| le32(&c, 0x5C + i * 4))
            .filter(|&id| id != 0)
            .collect(),
        allowed_media: le32(&c, 0x9C),
        regions: le32(&c, 0xA0),
        ratings: le32(&c, 0xA4),
        disc_number: le32(&c, 0xA8),
        version: le32(&c, 0xAC),
    })
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;

    /// An XBE whose certificate follows its header.
    fn xbe(title: &str, title_id: u32) -> Vec<u8> {
        let mut x = vec![0u8; 0x178 + 0x1D0];
        x[..4].copy_from_slice(b"XBEH");
        x[0x104..0x108].copy_from_slice(&0x10000u32.to_le_bytes());
        x[0x118..0x11C].copy_from_slice(&(0x10000u32 + 0x178).to_le_bytes());
        let c = &mut x[0x178..];
        c[0x08..0x0C].copy_from_slice(&title_id.to_le_bytes());
        for (i, unit) in title.encode_utf16().enumerate() {
            c[0x0C + i * 2..0x0E + i * 2].copy_from_slice(&unit.to_le_bytes());
        }
        c[0x5C..0x60].copy_from_slice(&0x4D53_0002u32.to_le_bytes());
        c[0x9C..0xA0].copy_from_slice(&0x12u32.to_le_bytes());
        c[0xA0..0xA4].copy_from_slice(&0x7u32.to_le_bytes());
        c[0xAC..0xB0].copy_from_slice(&2u32.to_le_bytes());
        x
    }

    #[test]
    fn reads_an_xbe() {
        let c = read(&mut Cursor::new(xbe("Test Game", 0x4D53_0001))).unwrap();
        assert_eq!(c.container, Container::Xbe);
        assert_eq!(c.title, "Test Game");
        assert_eq!(c.title_id, 0x4D53_0001);
        assert_eq!(c.publisher_code(), "MS");
        assert_eq!(c.product_code(), "MS-001");
        assert_eq!(c.alternate_title_ids, [0x4D53_0002]);
        assert_eq!(c.allowed_media, 0x12);
        assert_eq!(
            c.region_names(),
            ["North America", "Japan", "Rest of the world"]
        );
        assert_eq!(c.version, 2);
    }

    /// An XISO whose root directory holds `names`, as a tree leaning right,
    /// with `default.xbe` among them pointing at an XBE.
    fn xiso(names: &[&str]) -> Vec<u8> {
        let mut image = vec![0u8; 0x10000 + 0x800];
        image[0x10000..0x10014].copy_from_slice(b"MICROSOFT*XBOX*MEDIA");
        image[0x10014..0x10018].copy_from_slice(&40u32.to_le_bytes());
        image[0x10018..0x1001C].copy_from_slice(&0x800u32.to_le_bytes());
        image.resize(40 * 2048, 0);
        let mut listing = Vec::new();
        let mut sorted = names.to_vec();
        sorted.sort_by_key(|n| n.to_ascii_lowercase());
        for (i, name) in sorted.iter().enumerate() {
            let next = if i + 1 < sorted.len() {
                ((listing.len() + 14 + name.len()).div_ceil(4)) as u16
            } else {
                0
            };
            listing.extend_from_slice(&0u16.to_le_bytes());
            listing.extend_from_slice(&next.to_le_bytes());
            listing.extend_from_slice(&41u32.to_le_bytes());
            listing.extend_from_slice(&0x400u32.to_le_bytes());
            listing.push(0);
            listing.push(name.len() as u8);
            listing.extend_from_slice(name.as_bytes());
            listing.resize(listing.len().div_ceil(4) * 4, 0xFF);
        }
        listing.resize(2048, 0xFF);
        image.extend(listing);
        image.extend(xbe("Xromwell", 0x4D53_0001));
        image
    }

    #[test]
    fn reads_an_xiso() {
        let c = read(&mut Cursor::new(xiso(&["a.txt", "default.xbe", "z.bin"]))).unwrap();
        assert_eq!(c.container, Container::Xiso);
        assert_eq!(c.title, "Xromwell");
        assert!(matches!(
            read(&mut Cursor::new(xiso(&["a.txt"]))),
            Err(Error::NotFound(_))
        ));
    }

    #[test]
    fn refuses_other_files() {
        assert!(read(&mut Cursor::new(vec![0u8; 0x11000])).is_err());
    }
}
