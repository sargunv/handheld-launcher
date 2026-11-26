//! The NES's iNES and NES 2.0 headers: the 16 bytes a dumped cartridge starts
//! with, which say how its board is wired. They carry no title or serial.
//!
//! - <https://www.nesdev.org/wiki/INES>
//! - <https://www.nesdev.org/wiki/NES_2.0>

use std::io::{Read, Seek};

use crate::bytes::array_at;
use crate::code::numeric_code;
use crate::{Error, Result};

const MAGIC: &[u8; 4] = b"NES\x1A";

/// Which header a ROM carries.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Format {
    /// iNES, the original header.
    INes,
    /// NES 2.0, which extends iNES.
    Nes20,
}

impl core::fmt::Display for Format {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str(match self {
            Self::INes => "iNES",
            Self::Nes20 => "NES 2.0",
        })
    }
}

/// How the nametables are mirrored, from flags 6.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Mirroring {
    /// Vertical arrangement: horizontal mirroring.
    Horizontal,
    /// Horizontal arrangement: vertical mirroring.
    Vertical,
    /// The cartridge has its own VRAM for four screens.
    FourScreen,
}

impl core::fmt::Display for Mirroring {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str(match self {
            Self::Horizontal => "Horizontal",
            Self::Vertical => "Vertical",
            Self::FourScreen => "Four-screen",
        })
    }
}

numeric_code! {
    /// The console the ROM is for, from flags 7.
    ConsoleType(u8) {
        NES = 0 => "NES/Famicom",
        VS_SYSTEM = 1 => "Vs. System",
        PLAYCHOICE_10 = 2 => "PlayChoice-10",
        /// NES 2.0's extended console type says which.
        EXTENDED = 3 => "Extended",
    }
}

numeric_code! {
    /// The CPU and PPU timing. iNES has only NTSC and PAL.
    Timing(u8) {
        NTSC = 0 => "NTSC",
        PAL = 1 => "PAL",
        MULTIPLE = 2 => "Multiple-region",
        DENDY = 3 => "Dendy",
    }
}

numeric_code! {
    /// NES 2.0's extended console type, for [`ConsoleType::EXTENDED`].
    ExtendedConsoleType(u8) {
        REGULAR = 0x00 => "NES/Famicom/Dendy",
        VS_SYSTEM = 0x01 => "Vs. System",
        PLAYCHOICE_10 = 0x02 => "PlayChoice-10",
        DECIMAL_FAMICLONE = 0x03 => "Famiclone with decimal mode",
        EPSM = 0x04 => "NES/Famicom with EPSM",
        VT01 = 0x05 => "V.R. Technology VT01",
        VT02 = 0x06 => "V.R. Technology VT02",
        VT03 = 0x07 => "V.R. Technology VT03",
        VT09 = 0x08 => "V.R. Technology VT09",
        VT32 = 0x09 => "V.R. Technology VT32",
        VT369 = 0x0A => "V.R. Technology VT369",
        UM6578 = 0x0B => "UMC UM6578",
        FAMICOM_NETWORK_SYSTEM = 0x0C => "Famicom Network System",
    }
}

/// The Vs. System's PPU and hardware, from NES 2.0's byte 13.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct VsSystem {
    /// The PPU type, 0 to 15.
    pub ppu: u8,
    /// The hardware type, 0 to 15.
    pub hardware: u8,
}

/// An NES ROM's header.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Header {
    pub format: Format,
    /// PRG ROM, in bytes.
    pub prg_rom_size: u64,
    /// CHR ROM, in bytes. Zero when the board has CHR RAM instead.
    pub chr_rom_size: u64,
    /// The mapper number: up to 255 in iNES, 4095 in NES 2.0.
    pub mapper: u16,
    /// NES 2.0's submapper.
    pub submapper: Option<u8>,
    pub mirroring: Mirroring,
    /// Whether the board keeps its memory with a battery, or other
    /// non-volatile memory.
    pub battery: bool,
    /// Whether a 512-byte trainer comes before the PRG ROM.
    pub trainer: bool,
    pub console_type: ConsoleType,
    pub timing: Timing,
    /// Volatile PRG RAM, in bytes. iNES gives it in byte 8, where 0 means
    /// 8 KiB; few ROMs set it.
    pub prg_ram_size: u64,
    /// NES 2.0's non-volatile PRG RAM, in bytes.
    pub prg_nvram_size: Option<u64>,
    /// NES 2.0's volatile CHR RAM, in bytes.
    pub chr_ram_size: Option<u64>,
    /// NES 2.0's non-volatile CHR RAM, in bytes.
    pub chr_nvram_size: Option<u64>,
    /// NES 2.0's Vs. System hardware, for [`ConsoleType::VS_SYSTEM`].
    pub vs_system: Option<VsSystem>,
    /// NES 2.0's extended console type, for [`ConsoleType::EXTENDED`].
    pub extended_console_type: Option<ExtendedConsoleType>,
    /// NES 2.0's count of miscellaneous ROMs.
    pub misc_roms: Option<u8>,
    /// NES 2.0's default expansion device.
    pub expansion_device: Option<u8>,
}

/// Reads the header at the start of an NES ROM.
pub fn read<R: Read + Seek + ?Sized>(reader: &mut R) -> Result<Header> {
    let h: [u8; 16] = array_at(reader, 0).map_err(Error::short("NES"))?;
    if &h[..4] != MAGIC {
        return Err(Error::NotRecognized("NES"));
    }
    Ok(parse(&h))
}

fn parse(h: &[u8; 16]) -> Header {
    let flags6 = h[6];
    let flags7 = h[7];
    let mirroring = if flags6 & 0x08 != 0 {
        Mirroring::FourScreen
    } else if flags6 & 0x01 != 0 {
        Mirroring::Vertical
    } else {
        Mirroring::Horizontal
    };
    let console_type = ConsoleType(flags7 & 0x03);
    let mapper = u16::from(flags6 >> 4) | u16::from(flags7 & 0xF0);
    let common = Header {
        format: Format::INes,
        prg_rom_size: u64::from(h[4]) * 16 * 1024,
        chr_rom_size: u64::from(h[5]) * 8 * 1024,
        mapper,
        submapper: None,
        mirroring,
        battery: flags6 & 0x02 != 0,
        trainer: flags6 & 0x04 != 0,
        console_type,
        timing: if h[9] & 0x01 != 0 {
            Timing::PAL
        } else {
            Timing::NTSC
        },
        prg_ram_size: u64::from(h[8].max(1)) * 8 * 1024,
        prg_nvram_size: None,
        chr_ram_size: None,
        chr_nvram_size: None,
        vs_system: None,
        extended_console_type: None,
        misc_roms: None,
        expansion_device: None,
    };
    if flags7 & 0x0C != 0x08 {
        return common;
    }
    let ram = |shift: u8| if shift == 0 { 0 } else { 64u64 << shift };
    Header {
        format: Format::Nes20,
        prg_rom_size: rom_size(h[4], h[9] & 0x0F, 16 * 1024),
        chr_rom_size: rom_size(h[5], h[9] >> 4, 8 * 1024),
        mapper: mapper | (u16::from(h[8] & 0x0F) << 8),
        submapper: Some(h[8] >> 4),
        timing: Timing(h[12] & 0x03),
        prg_ram_size: ram(h[10] & 0x0F),
        prg_nvram_size: Some(ram(h[10] >> 4)),
        chr_ram_size: Some(ram(h[11] & 0x0F)),
        chr_nvram_size: Some(ram(h[11] >> 4)),
        vs_system: (console_type == ConsoleType::VS_SYSTEM).then_some(VsSystem {
            ppu: h[13] & 0x0F,
            hardware: h[13] >> 4,
        }),
        extended_console_type: (console_type == ConsoleType::EXTENDED)
            .then_some(ExtendedConsoleType(h[13] & 0x0F)),
        misc_roms: Some(h[14] & 0x03),
        expansion_device: Some(h[15] & 0x3F),
        ..common
    }
}

/// A ROM size in NES 2.0: a count of `unit`s, or with an MSB nibble of $F, an
/// exponent and multiplier.
fn rom_size(lsb: u8, msb: u8, unit: u64) -> u64 {
    if msb < 0x0F {
        (u64::from(msb) << 8 | u64::from(lsb)) * unit
    } else {
        (1u64 << (lsb >> 2)) * (u64::from(lsb & 3) * 2 + 1)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn header(bytes: [u8; 16]) -> Header {
        read(&mut std::io::Cursor::new(bytes)).unwrap()
    }

    #[test]
    fn reads_ines() {
        let h = header(*b"NES\x1A\x02\x01\x13\x10\x00\x01\0\0\0\0\0\0");
        assert_eq!(h.format, Format::INes);
        assert_eq!(h.prg_rom_size, 32 * 1024);
        assert_eq!(h.chr_rom_size, 8 * 1024);
        assert_eq!(h.mapper, 0x11);
        assert_eq!(h.mirroring, Mirroring::Vertical);
        assert!(h.battery);
        assert_eq!(h.timing, Timing::PAL);
        assert_eq!(h.prg_ram_size, 8 * 1024);
        assert_eq!(h.submapper, None);
    }

    #[test]
    fn reads_nes_2_0() {
        let h = header(*b"NES\x1A\x02\x01\x18\x09\x51\x00\x70\x07\x03\x02\x01\x05");
        assert_eq!(h.format, Format::Nes20);
        assert_eq!(h.mapper, 0x101);
        assert_eq!(h.submapper, Some(5));
        assert_eq!(h.mirroring, Mirroring::FourScreen);
        assert_eq!(h.prg_ram_size, 0);
        assert_eq!(h.prg_nvram_size, Some(64 << 7));
        assert_eq!(h.chr_ram_size, Some(64 << 7));
        assert_eq!(h.timing, Timing::DENDY);
        assert_eq!(
            h.vs_system,
            Some(VsSystem {
                ppu: 2,
                hardware: 0
            })
        );
        assert_eq!(h.misc_roms, Some(1));
        assert_eq!(h.expansion_device, Some(5));
    }

    #[test]
    fn reads_exponent_sizes() {
        // 2^5 * 3 bytes.
        assert_eq!(rom_size(0b10101, 0x0F, 16 * 1024), 96);
        assert_eq!(rom_size(0x00, 0x01, 16 * 1024), 256 * 16 * 1024);
    }

    #[test]
    fn refuses_other_files() {
        assert!(matches!(
            read(&mut std::io::Cursor::new([0u8; 16])),
            Err(Error::NotRecognized("NES"))
        ));
        assert!(matches!(
            read(&mut std::io::Cursor::new(*b"NES\x1A")),
            Err(Error::NotRecognized("NES"))
        ));
    }
}
