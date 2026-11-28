//! The Nintendo DS and DSi cartridge header, the first 512 bytes.
//!
//! - <https://problemkaputt.de/gbatek-ds-cartridge-header.htm>

use std::io::{Read, Seek};

use crate::bytes::{array_at, ascii, le16};
use crate::code::{char_code, numeric_code};
use crate::{Error, Result};

/// The CRC-16 the DS uses.
const CRC16: crc::Crc<u16> = crc::Crc::<u16>::new(&crc::CRC_16_MODBUS);

numeric_code! {
    /// The unit code at $012: which systems the game runs on.
    UnitCode(u8) {
        NDS = 0x00 => "Nintendo DS",
        NDS_DSI = 0x02 => "Nintendo DS and DSi",
        DSI = 0x03 => "Nintendo DSi only",
    }
}

numeric_code! {
    /// The region lock at $01D.
    Region(u8) {
        NORMAL = 0x00 => "Normal",
        KOREA = 0x40 => "Korea",
        CHINA = 0x80 => "China",
    }
}

char_code! {
    /// The category, the game code's first character.
    Category {
        NDS_A = b'A' => "Nintendo DS",
        NDS_B = b'B' => "Nintendo DS",
        NDS_C = b'C' => "Nintendo DS",
        DSI_ONLY = b'D' => "Nintendo DSi only",
        DSIWARE_SYSTEM = b'H' => "DSiWare (system utility)",
        INFRARED = b'I' => "Nintendo DS with infrared",
        DSIWARE = b'K' => "DSiWare",
        DEMO = b'N' => "Nintendo DS demo",
        NDS_T = b'T' => "Nintendo DS",
        EXTRA_HARDWARE = b'U' => "Nintendo DS with extra hardware",
        DSI_ENHANCED = b'V' => "Nintendo DSi enhanced",
        NDS_Y = b'Y' => "Nintendo DS",
    }
}

char_code! {
    /// The destination, the game code's last character.
    Destination {
        ASIA = b'A' => "Asia",
        CHINA = b'C' => "China",
        GERMANY = b'D' => "Germany",
        USA = b'E' => "USA",
        FRANCE = b'F' => "France",
        NETHERLANDS = b'H' => "Netherlands",
        ITALY = b'I' => "Italy",
        JAPAN = b'J' => "Japan",
        KOREA = b'K' => "Korea",
        USA_L = b'L' => "USA",
        SWEDEN = b'M' => "Sweden",
        NORWAY = b'N' => "Norway",
        INTERNATIONAL = b'O' => "International",
        EUROPE = b'P' => "Europe",
        DENMARK = b'Q' => "Denmark",
        RUSSIA = b'R' => "Russia",
        SPAIN = b'S' => "Spain",
        USA_AUSTRALIA = b'T' => "USA and Australia",
        AUSTRALIA = b'U' => "Australia",
        EUROPE_AUSTRALIA = b'V' => "Europe and Australia",
        EUROPE_W = b'W' => "Europe",
        EUROPE_X = b'X' => "Europe",
        EUROPE_Y = b'Y' => "Europe",
        EUROPE_Z = b'Z' => "Europe",
    }
}

/// A Nintendo DS ROM's header.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Header {
    /// Up to 12 characters.
    pub title: String,
    /// The four-character game code: category, unique code and destination.
    pub game_code: String,
    pub category: Category,
    pub destination: Destination,
    /// The two-character maker code.
    pub maker_code: String,
    pub unit_code: UnitCode,
    /// The chip's capacity, in bytes: 128 KiB shifted left by the code at
    /// $014.
    pub capacity: Option<u64>,
    pub region: Region,
    /// The ROM version: 0 for the first release.
    pub version: u8,
    /// The CRC-16 of the header's first $15E bytes.
    pub header_crc: u16,
    /// Whether [`Header::header_crc`] matches the header.
    pub header_crc_valid: bool,
}

/// Reads a Nintendo DS ROM's header.
pub fn read<R: Read + Seek + ?Sized>(reader: &mut R) -> Result<Header> {
    let h: [u8; 0x200] = array_at(reader, 0).map_err(Error::short("Nintendo DS"))?;
    let header_crc = le16(&h, 0x15E);
    Ok(Header {
        title: ascii(&h[..12]),
        game_code: ascii(&h[0x0C..0x10]),
        category: Category(h[0x0C]),
        destination: Destination(h[0x0F]),
        maker_code: ascii(&h[0x10..0x12]),
        unit_code: UnitCode(h[0x12]),
        capacity: (h[0x14] <= 12).then(|| (128 * 1024) << h[0x14]),
        region: Region(h[0x1D]),
        version: h[0x1E],
        header_crc,
        header_crc_valid: CRC16.checksum(&h[..0x15E]) == header_crc,
    })
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;

    #[test]
    fn reads_a_header() {
        let mut rom = vec![0u8; 0x200];
        rom[..12].copy_from_slice(b"ADVANCEWARS2");
        rom[0x0C..0x12].copy_from_slice(b"AWRE01");
        rom[0x14] = 0x09;
        rom[0x1E] = 0x01;
        let crc = CRC16.checksum(&rom[..0x15E]);
        rom[0x15E..0x160].copy_from_slice(&crc.to_le_bytes());
        let h = read(&mut Cursor::new(&rom)).unwrap();
        assert_eq!(h.title, "ADVANCEWARS2");
        assert_eq!(h.game_code, "AWRE");
        assert_eq!(h.category, Category::NDS_A);
        assert_eq!(h.destination, Destination::USA);
        assert_eq!(h.maker_code, "01");
        assert_eq!(h.unit_code, UnitCode::NDS);
        assert_eq!(h.capacity, Some(64 * 1024 * 1024));
        assert_eq!(h.version, 1);
        assert!(h.header_crc_valid);
    }
}
