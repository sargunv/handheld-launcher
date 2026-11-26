//! The Game Boy and Game Boy Color cartridge header, at $0100.
//!
//! Early cartridges give the title all 16 bytes from $0134. Later ones end it
//! at 11 and follow it with a manufacturer code and the CGB flag; the reader
//! takes a title area holding anything but ASCII to be the later layout.
//!
//! - <https://gbdev.io/pandocs/The_Cartridge_Header.html>

use std::io::{Read, Seek};

use crate::bytes::{array_at, ascii, be16, code};
use crate::code::numeric_code;
use crate::{Error, Result};

numeric_code! {
    /// The CGB flag at $0143.
    CgbFlag(u8) {
        NONE = 0x00 => "Game Boy",
        SUPPORTED = 0x80 => "Game Boy and Game Boy Color",
        REQUIRED = 0xC0 => "Game Boy Color only",
    }
}

numeric_code! {
    /// The destination code at $014A.
    Destination(u8) {
        JAPAN = 0x00 => "Japan",
        OVERSEAS = 0x01 => "Overseas",
    }
}

numeric_code! {
    /// The cartridge type at $0147: its mapper and what else it holds.
    CartridgeType(u8) {
        ROM_ONLY = 0x00 => "ROM only",
        MBC1 = 0x01 => "MBC1",
        MBC1_RAM = 0x02 => "MBC1+RAM",
        MBC1_RAM_BATTERY = 0x03 => "MBC1+RAM+battery",
        MBC2 = 0x05 => "MBC2",
        MBC2_BATTERY = 0x06 => "MBC2+battery",
        ROM_RAM = 0x08 => "ROM+RAM",
        ROM_RAM_BATTERY = 0x09 => "ROM+RAM+battery",
        MMM01 = 0x0B => "MMM01",
        MMM01_RAM = 0x0C => "MMM01+RAM",
        MMM01_RAM_BATTERY = 0x0D => "MMM01+RAM+battery",
        MBC3_TIMER_BATTERY = 0x0F => "MBC3+timer+battery",
        MBC3_TIMER_RAM_BATTERY = 0x10 => "MBC3+timer+RAM+battery",
        MBC3 = 0x11 => "MBC3",
        MBC3_RAM = 0x12 => "MBC3+RAM",
        MBC3_RAM_BATTERY = 0x13 => "MBC3+RAM+battery",
        MBC5 = 0x19 => "MBC5",
        MBC5_RAM = 0x1A => "MBC5+RAM",
        MBC5_RAM_BATTERY = 0x1B => "MBC5+RAM+battery",
        MBC5_RUMBLE = 0x1C => "MBC5+rumble",
        MBC5_RUMBLE_RAM = 0x1D => "MBC5+rumble+RAM",
        MBC5_RUMBLE_RAM_BATTERY = 0x1E => "MBC5+rumble+RAM+battery",
        MBC6 = 0x20 => "MBC6",
        MBC7 = 0x22 => "MBC7+sensor+rumble+RAM+battery",
        POCKET_CAMERA = 0xFC => "Pocket Camera",
        BANDAI_TAMA5 = 0xFD => "Bandai TAMA5",
        HUC3 = 0xFE => "HuC3",
        HUC1_RAM_BATTERY = 0xFF => "HuC1+RAM+battery",
    }
}

/// Who published the game: the old one-byte licensee code at $014B, or when
/// that's $33, the new two-character one at $0144.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Licensee {
    Old(u8),
    New([u8; 2]),
}

impl core::fmt::Display for Licensee {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            Self::Old(old) => write!(f, "{old:02X}"),
            Self::New(new) => f.write_str(&code(new)),
        }
    }
}

/// A Game Boy or Game Boy Color ROM's header.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Header {
    /// Up to 16 characters in early cartridges, 11 in later ones.
    pub title: String,
    /// The four-character manufacturer code of later cartridges. Some put the
    /// end of a long title here instead.
    pub manufacturer_code: Option<String>,
    pub cgb: CgbFlag,
    /// Whether the game supports the Super Game Boy: $03 at $0146.
    pub sgb: bool,
    pub cartridge_type: CartridgeType,
    /// ROM, in bytes, from the code at $0148.
    pub rom_size: Option<u64>,
    /// Save RAM, in bytes, from the code at $0149.
    pub ram_size: Option<u64>,
    pub destination: Destination,
    pub licensee: Licensee,
    /// The mask ROM version: 0 for the first release.
    pub version: u8,
    pub header_checksum: u8,
    /// Whether [`Header::header_checksum`] matches the header, as the boot ROM
    /// checks before it starts the game.
    pub header_checksum_valid: bool,
    /// The checksum over the whole ROM, which no hardware checks.
    pub global_checksum: u16,
}

/// Reads a Game Boy or Game Boy Color ROM's header.
pub fn read<R: Read + Seek + ?Sized>(reader: &mut R) -> Result<Header> {
    // $0100 to $014F.
    let h: [u8; 0x50] = array_at(reader, 0x100).map_err(Error::short("Game Boy"))?;
    let at = |offset: usize| h[offset - 0x100];
    let title_area = &h[0x34..0x44];
    let early = title_area
        .iter()
        .all(|&c| c == 0 || (0x20..=0x7E).contains(&c));
    let (title, manufacturer_code, cgb) = if early {
        (ascii(title_area), None, CgbFlag::NONE)
    } else {
        let manufacturer = ascii(&h[0x3F..0x43]);
        (
            ascii(&h[0x34..0x3F]),
            (!manufacturer.is_empty()).then_some(manufacturer),
            CgbFlag(at(0x143)),
        )
    };
    let checksum = h[0x34..0x4D]
        .iter()
        .fold(0u8, |x, &c| x.wrapping_sub(c).wrapping_sub(1));
    let old_licensee = at(0x14B);
    Ok(Header {
        title,
        manufacturer_code,
        cgb,
        sgb: at(0x146) == 0x03,
        cartridge_type: CartridgeType(at(0x147)),
        rom_size: rom_size(at(0x148)),
        ram_size: ram_size(at(0x149)),
        destination: Destination(at(0x14A)),
        licensee: if old_licensee == 0x33 {
            Licensee::New([at(0x144), at(0x145)])
        } else {
            Licensee::Old(old_licensee)
        },
        version: at(0x14C),
        header_checksum: at(0x14D),
        header_checksum_valid: checksum == at(0x14D),
        global_checksum: be16(&h, 0x4E),
    })
}

fn rom_size(code: u8) -> Option<u64> {
    (code <= 0x08).then(|| (32 * 1024) << code)
}

fn ram_size(code: u8) -> Option<u64> {
    Some(match code {
        0x00 => 0,
        0x01 => 2 * 1024,
        0x02 => 8 * 1024,
        0x03 => 32 * 1024,
        0x04 => 128 * 1024,
        0x05 => 64 * 1024,
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;

    #[test]
    fn reads_a_later_header() {
        let mut rom = vec![0u8; 0x150];
        rom[0x134..0x13F].copy_from_slice(b"POKEMON\0\0\0\0");
        rom[0x13F..0x143].copy_from_slice(b"AAUE");
        rom[0x143] = 0xC0;
        rom[0x144..0x146].copy_from_slice(b"01");
        rom[0x146] = 0x03;
        rom[0x147] = 0x1B;
        rom[0x148] = 0x06;
        rom[0x149] = 0x03;
        rom[0x14A] = 0x01;
        rom[0x14B] = 0x33;
        rom[0x14C] = 0x01;
        rom[0x14D] = rom[0x134..0x14D]
            .iter()
            .fold(0u8, |x, &c| x.wrapping_sub(c).wrapping_sub(1));
        let h = read(&mut Cursor::new(&rom)).unwrap();
        assert_eq!(h.title, "POKEMON");
        assert_eq!(h.manufacturer_code.as_deref(), Some("AAUE"));
        assert_eq!(h.cgb, CgbFlag::REQUIRED);
        assert!(h.sgb);
        assert_eq!(h.cartridge_type, CartridgeType::MBC5_RAM_BATTERY);
        assert_eq!(h.rom_size, Some(2 * 1024 * 1024));
        assert_eq!(h.ram_size, Some(32 * 1024));
        assert_eq!(h.destination, Destination::OVERSEAS);
        assert_eq!(h.licensee.to_string(), "01");
        assert_eq!(h.version, 1);
        assert!(h.header_checksum_valid);

        rom[0x14D] ^= 1;
        assert!(!read(&mut Cursor::new(&rom)).unwrap().header_checksum_valid);
    }

    #[test]
    fn refuses_a_short_file() {
        assert!(read(&mut Cursor::new(vec![0u8; 0x140])).is_err());
    }
}
