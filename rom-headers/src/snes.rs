//! The Super NES's internal header, which sits where the cartridge maps it:
//! $7FC0 for LoROM, $FFC0 for HiROM and $40FFC0 for ExHiROM, after a 512-byte
//! copier header when a dump has one. The reader takes the first place whose
//! checksum and complement add up and whose map mode and title look like a
//! header's.
//!
//! - <https://snes.nesdev.org/wiki/ROM_header>
//! - <https://sneslab.net/wiki/SNES_ROM_Header>

use std::io::{Read, Seek};

use crate::bytes::{jis_x0201, le16, read_at, stream_len};
use crate::code::numeric_code;
use crate::{Error, Result};

/// Where the header was found, which is how the cartridge maps its ROM.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Layout {
    LoRom,
    HiRom,
    ExHiRom,
}

impl Layout {
    fn offset(self) -> u64 {
        match self {
            Self::LoRom => 0x7FC0,
            Self::HiRom => 0xFFC0,
            Self::ExHiRom => 0x40_FFC0,
        }
    }
}

impl core::fmt::Display for Layout {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str(match self {
            Self::LoRom => "LoROM",
            Self::HiRom => "HiROM",
            Self::ExHiRom => "ExHiROM",
        })
    }
}

numeric_code! {
    /// The map mode at $FFD5: the memory map and ROM speed.
    MapMode(u8) {
        LOROM = 0x20 => "LoROM",
        HIROM = 0x21 => "HiROM",
        SDD1 = 0x22 => "LoROM (S-DD1)",
        SA1 = 0x23 => "LoROM (SA-1)",
        EXHIROM = 0x25 => "ExHiROM",
        FAST_LOROM = 0x30 => "LoROM, FastROM",
        FAST_HIROM = 0x31 => "HiROM, FastROM",
        FAST_SDD1 = 0x32 => "LoROM (S-DD1), FastROM",
        FAST_SA1 = 0x33 => "LoROM (SA-1), FastROM",
        FAST_EXHIROM = 0x35 => "ExHiROM, FastROM",
        SPC7110 = 0x3A => "HiROM (SPC7110), FastROM",
    }
}

numeric_code! {
    /// The destination code at $FFD9: where the cartridge was sold.
    Destination(u8) {
        JAPAN = 0x00 => "Japan",
        NORTH_AMERICA = 0x01 => "North America",
        EUROPE = 0x02 => "Europe",
        SCANDINAVIA = 0x03 => "Scandinavia",
        FRANCE = 0x06 => "France",
        NETHERLANDS = 0x07 => "Netherlands",
        SPAIN = 0x08 => "Spain",
        GERMANY = 0x09 => "Germany",
        ITALY = 0x0A => "Italy",
        CHINA = 0x0B => "China",
        KOREA = 0x0D => "Korea",
        INTERNATIONAL = 0x0E => "International",
        CANADA = 0x0F => "Canada",
        BRAZIL = 0x10 => "Brazil",
        AUSTRALIA = 0x11 => "Australia",
    }
}

/// The extended header at $FFB0, which cartridges have when their old maker
/// code is $33.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Extended {
    /// The two-character maker code.
    pub maker_code: String,
    /// The four-character game code.
    pub game_code: String,
    /// Expansion RAM, in bytes.
    pub expansion_ram_size: u64,
    pub special_version: u8,
    pub cartridge_subtype: u8,
}

/// A Super NES ROM's header.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Header {
    pub layout: Layout,
    /// Whether the dump starts with a 512-byte copier header.
    pub copier_header: bool,
    /// Up to 21 characters of JIS X 0201: ASCII and half-width katakana.
    pub title: String,
    pub map_mode: MapMode,
    /// The chipset byte at $FFD6.
    pub cartridge_type: u8,
    /// ROM, in bytes.
    pub rom_size: u64,
    /// Save RAM, in bytes.
    pub ram_size: u64,
    pub destination: Destination,
    /// The old maker code at $FFDA. $33 means the extended header has it.
    pub old_maker_code: u8,
    /// The mask ROM version at $FFDB: 0 for the first release.
    pub version: u8,
    pub checksum_complement: u16,
    pub checksum: u16,
    /// The extended header, when [`Header::old_maker_code`] is $33.
    pub extended: Option<Extended>,
}

impl Header {
    /// The maker code: the extended header's, or the old one in hex.
    pub fn maker_code(&self) -> String {
        match &self.extended {
            Some(extended) => extended.maker_code.clone(),
            None => format!("{:02X}", self.old_maker_code),
        }
    }
}

/// Reads a Super NES ROM's header.
pub fn read<R: Read + Seek + ?Sized>(reader: &mut R) -> Result<Header> {
    let size = stream_len(reader)?;
    let copier_header = size % 1024 == 512;
    let base = if copier_header { 512 } else { 0 };
    for layout in [Layout::LoRom, Layout::HiRom, Layout::ExHiRom] {
        let offset = base + layout.offset();
        if offset + 32 > size {
            continue;
        }
        let mut h = [0u8; 48];
        read_at(reader, offset - 16, &mut h)?;
        let header = parse(&h, layout, copier_header);
        if plausible(&header, size) {
            return Ok(header);
        }
    }
    Err(Error::NotRecognized("Super NES"))
}

/// Parses the standard header at `h[16..]`, after the extended one.
fn parse(h: &[u8; 48], layout: Layout, copier_header: bool) -> Header {
    let s = &h[16..];
    let size = |exp: u8| {
        if exp > 0 && exp < 16 {
            1024u64 << exp
        } else {
            0
        }
    };
    let old_maker_code = s[0x1A];
    Header {
        layout,
        copier_header,
        title: jis_x0201(&s[..21]),
        map_mode: MapMode(s[0x15]),
        cartridge_type: s[0x16],
        rom_size: size(s[0x17]),
        ram_size: size(s[0x18]),
        destination: Destination(s[0x19]),
        old_maker_code,
        version: s[0x1B],
        checksum_complement: le16(s, 0x1C),
        checksum: le16(s, 0x1E),
        extended: (old_maker_code == 0x33).then(|| Extended {
            maker_code: jis_x0201(&h[0..2]),
            game_code: jis_x0201(&h[2..6]),
            expansion_ram_size: size(h[13]),
            special_version: h[14],
            cartridge_subtype: h[15],
        }),
    }
}

/// Whether what's at a header's place looks like a header.
fn plausible(h: &Header, file_size: u64) -> bool {
    if h.checksum.wrapping_add(h.checksum_complement) != 0xFFFF {
        return false;
    }
    if !(0x20..=0x3F).contains(&h.map_mode.0) && !contra_iii(h) {
        return false;
    }
    // Printable JIS X 0201: ASCII, or the half-width katakana of a Japanese
    // title.
    let printable = |c: &char| (' '..='~').contains(c) || ('\u{FF61}'..='\u{FF9F}').contains(c);
    if h.title.chars().filter(printable).count() < 2 {
        return false;
    }
    // Some headers overstate the size, but not past twice the file.
    h.rom_size == 0 || h.rom_size <= file_size * 2
}

/// Contra III (USA)'s title is 22 characters, and its last spills into the
/// map mode, as the cartridge was made.
fn contra_iii(h: &Header) -> bool {
    h.title == "CONTRA3 THE ALIEN WAR"
        && h.map_mode.0 == b'S'
        && h.checksum == 0x0C3C
        && h.checksum_complement == 0xF3C3
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;

    /// A ROM of `size` bytes with a header at `offset`.
    fn rom(size: usize, offset: usize, map_mode: u8) -> Vec<u8> {
        let mut rom = vec![0u8; size];
        let ext = &mut rom[offset - 16..offset];
        ext[..6].copy_from_slice(b"01ABCE");
        ext[13] = 3;
        let h = &mut rom[offset..offset + 32];
        h[..21].copy_from_slice(b"TEST GAME \xB1\xB2         ");
        h[0x15] = map_mode;
        h[0x16] = 0x02;
        h[0x17] = 0x05;
        h[0x18] = 0x03;
        h[0x19] = 0x01;
        h[0x1A] = 0x33;
        h[0x1B] = 0x01;
        h[0x1C..0x20].copy_from_slice(&[0x34, 0x12, 0xCB, 0xED]);
        rom
    }

    #[test]
    fn reads_lorom() {
        let h = read(&mut Cursor::new(rom(0x8000, 0x7FC0, 0x20))).unwrap();
        assert_eq!(h.layout, Layout::LoRom);
        assert!(!h.copier_header);
        assert_eq!(h.title, "TEST GAME \u{FF71}\u{FF72}");
        assert_eq!(h.map_mode, MapMode::LOROM);
        assert_eq!(h.rom_size, 32 * 1024);
        assert_eq!(h.ram_size, 8 * 1024);
        assert_eq!(h.destination, Destination::NORTH_AMERICA);
        assert_eq!(h.version, 1);
        assert_eq!(h.checksum, 0xEDCB);
        let extended = h.extended.unwrap();
        assert_eq!(extended.maker_code, "01");
        assert_eq!(extended.game_code, "ABCE");
        assert_eq!(extended.expansion_ram_size, 8 * 1024);
    }

    #[test]
    fn reads_a_title_all_in_katakana() {
        let mut file = rom(0x8000, 0x7FC0, 0x20);
        file[0x7FC0..0x7FC0 + 21].copy_from_slice(&[0xB1; 21]);
        let h = read(&mut Cursor::new(file)).unwrap();
        assert_eq!(h.title, "\u{FF71}".repeat(21));
    }

    #[test]
    fn reads_hirom_after_a_copier_header() {
        let mut file = vec![0u8; 512];
        file.extend(rom(0x10000, 0xFFC0, 0x31));
        let h = read(&mut Cursor::new(file)).unwrap();
        assert_eq!(h.layout, Layout::HiRom);
        assert!(h.copier_header);
        assert_eq!(h.map_mode, MapMode::FAST_HIROM);
    }

    #[test]
    fn refuses_a_header_whose_checksums_disagree() {
        let mut file = rom(0x8000, 0x7FC0, 0x20);
        file[0x7FC0 + 0x1E] ^= 1;
        assert!(matches!(
            read(&mut Cursor::new(file)),
            Err(Error::NotRecognized(_))
        ));
    }
}
