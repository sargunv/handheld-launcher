//! The Master System and Game Gear header, the 16 bytes at $7FF0 that start
//! with `TMR SEGA`, or at $3FF0 or $1FF0 in a 16 or 8 KiB ROM. Its region code
//! says which of the two the game is for.
//!
//! - <https://www.smspower.org/Development/ROMHeader>

use std::io::{Read, Seek};

use crate::bytes::{array_at, le16};
use crate::code::numeric_code;
use crate::{Error, Result};

numeric_code! {
    /// The region code, the high nibble of $7FFF.
    Region(u8) {
        JAPAN_SMS = 0x3 => "Master System, Japan",
        EXPORT_SMS = 0x4 => "Master System, export",
        JAPAN_GG = 0x5 => "Game Gear, Japan",
        EXPORT_GG = 0x6 => "Game Gear, export",
        INTERNATIONAL_GG = 0x7 => "Game Gear, international",
    }
}

/// A Master System or Game Gear ROM's header.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Header {
    /// The product code, from its binary-coded decimal digits.
    pub product_code: String,
    /// The version, 0 to 15.
    pub version: u8,
    pub region: Region,
    /// The ROM size the checksum covers, in bytes, from the low nibble of
    /// $7FFF.
    pub rom_size: Option<u64>,
    pub checksum: u16,
}

/// Reads a Master System or Game Gear ROM's header.
pub fn read<R: Read + Seek + ?Sized>(reader: &mut R) -> Result<Header> {
    let mut short = None;
    let mut found = None;
    for offset in [0x7FF0, 0x3FF0, 0x1FF0] {
        match array_at::<16, _>(reader, offset) {
            Ok(h) if &h[..8] == b"TMR SEGA" => {
                found = Some(h);
                break;
            }
            Ok(_) => {}
            Err(e) => short = short.or(Some(e)),
        }
    }
    let h = match (found, short) {
        (Some(h), _) => h,
        // Too small for even the smallest header.
        (None, Some(e))
            if reader
                .seek(std::io::SeekFrom::End(0))
                .is_ok_and(|n| n < 0x2000) =>
        {
            return Err(Error::short("Master System")(e));
        }
        (None, _) => return Err(Error::NotRecognized("Master System")),
    };
    let extra = h[0x0E] >> 4;
    let product_code = if extra == 0 {
        format!("{:02X}{:02X}", h[0x0D], h[0x0C])
    } else {
        format!("{extra:X}{:02X}{:02X}", h[0x0D], h[0x0C])
    };
    Ok(Header {
        product_code,
        version: h[0x0E] & 0x0F,
        region: Region(h[0x0F] >> 4),
        rom_size: rom_size(h[0x0F] & 0x0F),
        checksum: le16(&h, 0x0A),
    })
}

fn rom_size(code: u8) -> Option<u64> {
    let kib = match code {
        0xA => 8,
        0xB => 16,
        0xC => 32,
        0xD => 48,
        0xE => 64,
        0xF => 128,
        0x0 => 256,
        0x1 => 512,
        0x2 => 1024,
        _ => return None,
    };
    Some(kib * 1024)
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;

    #[test]
    fn reads_a_header() {
        let mut rom = vec![0u8; 0x8000];
        rom[0x7FF0..].copy_from_slice(b"TMR SEGA\0\0\x34\x12\x26\x70\x21\x4C");
        let h = read(&mut Cursor::new(rom)).unwrap();
        // Product codes are little-endian BCD, with the high nibble of $7FFE
        // in front: 2, then $70, then $26.
        assert_eq!(h.product_code, "27026");
        assert_eq!(h.version, 1);
        assert_eq!(h.region, Region::EXPORT_SMS);
        assert_eq!(h.rom_size, Some(32 * 1024));
        assert_eq!(h.checksum, 0x1234);
    }

    #[test]
    fn reads_a_small_roms_header() {
        let mut rom = vec![0u8; 0x4000];
        rom[0x3FF0..].copy_from_slice(b"TMR SEGA\0\0\x34\x12\x26\x70\x21\x4B");
        let h = read(&mut Cursor::new(rom)).unwrap();
        assert_eq!(h.product_code, "27026");
        assert_eq!(h.rom_size, Some(16 * 1024));
    }

    #[test]
    fn refuses_other_files() {
        assert!(read(&mut Cursor::new(vec![0u8; 0x8000])).is_err());
    }
}
