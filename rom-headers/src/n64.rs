//! The Nintendo 64's ROM header, the first 64 bytes of the cartridge, in any
//! of the three byte orders dumps come in.
//!
//! - <https://n64brew.dev/wiki/ROM_Header>

use std::io::{Read, Seek};

use crate::bytes::{array_at, ascii, be32, be64};
use crate::code::char_code;
use crate::{Error, Result};

/// The byte order of a dump, told by where the header's first byte, $80, is.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ByteOrder {
    /// Big-endian, as the cartridge is: `.z64`.
    BigEndian,
    /// Each pair of bytes swapped: `.v64`.
    ByteSwapped,
    /// Each 32-bit word reversed: `.n64`.
    LittleEndian,
}

impl core::fmt::Display for ByteOrder {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str(match self {
            Self::BigEndian => "Big-endian (z64)",
            Self::ByteSwapped => "Byte-swapped (v64)",
            Self::LittleEndian => "Little-endian (n64)",
        })
    }
}

char_code! {
    /// The media category, the game code's first character.
    Category {
        GAME_PAK = b'N' => "Game Pak",
        DISK = b'D' => "64DD disk",
        EXPANDABLE_GAME_PAK = b'C' => "Expandable Game Pak",
        EXPANDABLE_DISK = b'E' => "Expandable 64DD disk",
        ALECK64 = b'Z' => "Aleck64",
    }
}

char_code! {
    /// The destination, the game code's last character.
    Destination {
        ALL = b'A' => "All regions",
        BRAZIL = b'B' => "Brazil",
        CHINA = b'C' => "China",
        GERMANY = b'D' => "Germany",
        NORTH_AMERICA = b'E' => "North America",
        FRANCE = b'F' => "France",
        GATEWAY_NTSC = b'G' => "Gateway 64 (NTSC)",
        NETHERLANDS = b'H' => "Netherlands",
        ITALY = b'I' => "Italy",
        JAPAN = b'J' => "Japan",
        KOREA = b'K' => "Korea",
        GATEWAY_PAL = b'L' => "Gateway 64 (PAL)",
        CANADA = b'N' => "Canada",
        EUROPE = b'P' => "Europe",
        SPAIN = b'S' => "Spain",
        AUSTRALIA = b'U' => "Australia",
        SCANDINAVIA = b'W' => "Scandinavia",
        EUROPE_X = b'X' => "Europe",
        EUROPE_Y = b'Y' => "Europe",
        EUROPE_Z = b'Z' => "Europe",
    }
}

/// A Nintendo 64 ROM's header.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Header {
    pub byte_order: ByteOrder,
    /// The PI BSD DOM1 configuration, 24 bits.
    pub pi_config: u32,
    pub clock_rate: u32,
    pub boot_address: u32,
    pub libultra_version: u32,
    /// The 64-bit check code over the start of the ROM.
    pub check_code: u64,
    /// Up to 20 characters.
    pub title: String,
    /// The four-character game code: category, unique code and destination.
    pub game_code: String,
    pub category: Category,
    pub destination: Destination,
    /// The ROM version: 0 for the first release.
    pub version: u8,
}

/// Reads an N64 ROM's header in any byte order.
pub fn read<R: Read + Seek + ?Sized>(reader: &mut R) -> Result<Header> {
    let mut h: [u8; 64] = array_at(reader, 0).map_err(Error::short("N64"))?;
    let byte_order = match h {
        [0x80, ..] => ByteOrder::BigEndian,
        [_, 0x80, ..] => ByteOrder::ByteSwapped,
        [_, _, _, 0x80, ..] => ByteOrder::LittleEndian,
        _ => return Err(Error::NotRecognized("N64")),
    };
    match byte_order {
        ByteOrder::BigEndian => {}
        ByteOrder::ByteSwapped => h
            .as_chunks_mut::<2>()
            .0
            .iter_mut()
            .for_each(|c| c.reverse()),
        ByteOrder::LittleEndian => h
            .as_chunks_mut::<4>()
            .0
            .iter_mut()
            .for_each(|c| c.reverse()),
    }
    Ok(Header {
        byte_order,
        pi_config: be32(&h, 0) & 0x00FF_FFFF,
        clock_rate: be32(&h, 0x04),
        boot_address: be32(&h, 0x08),
        libultra_version: be32(&h, 0x0C),
        check_code: be64(&h, 0x10),
        title: ascii(&h[0x20..0x34]),
        game_code: ascii(&h[0x3B..0x3F]),
        category: Category(h[0x3B]),
        destination: Destination(h[0x3E]),
        version: h[0x3F],
    })
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;

    fn z64() -> Vec<u8> {
        let mut h = vec![0u8; 64];
        h[..4].copy_from_slice(&[0x80, 0x37, 0x12, 0x40]);
        h[0x08..0x0C].copy_from_slice(&0x8000_0400u32.to_be_bytes());
        h[0x10..0x18].copy_from_slice(&0x0123_4567_89AB_CDEFu64.to_be_bytes());
        h[0x20..0x34].copy_from_slice(b"SUPER MARIO 64      ");
        h[0x3B..0x40].copy_from_slice(b"NSME\x01");
        h
    }

    #[test]
    fn reads_every_byte_order() {
        let swapped: Vec<u8> = z64().chunks(2).flat_map(|c| [c[1], c[0]]).collect();
        let little: Vec<u8> = z64()
            .chunks(4)
            .flat_map(|c| [c[3], c[2], c[1], c[0]])
            .collect();
        for (bytes, order) in [
            (z64(), ByteOrder::BigEndian),
            (swapped, ByteOrder::ByteSwapped),
            (little, ByteOrder::LittleEndian),
        ] {
            let h = read(&mut Cursor::new(bytes)).unwrap();
            assert_eq!(h.byte_order, order);
            assert_eq!(h.title, "SUPER MARIO 64");
            assert_eq!(h.game_code, "NSME");
            assert_eq!(h.category, Category::GAME_PAK);
            assert_eq!(h.destination, Destination::NORTH_AMERICA);
            assert_eq!(h.version, 1);
            assert_eq!(h.boot_address, 0x8000_0400);
            assert_eq!(h.check_code, 0x0123_4567_89AB_CDEF);
            assert_eq!(h.pi_config, 0x37_1240);
        }
    }

    #[test]
    fn refuses_other_files() {
        assert!(read(&mut Cursor::new(vec![0u8; 64])).is_err());
        assert!(read(&mut Cursor::new(vec![0x80u8; 10])).is_err());
    }
}
