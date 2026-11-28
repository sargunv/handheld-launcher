//! The Mega Drive's header at $100, which the 32X and Mega CD share: the 32X
//! marks its cartridges with a `MARS` header at $3C0, and a Mega CD disc
//! starts its first sector with a disc identifier and the same header after
//! it. Cartridge dumps in the Super Magic Drive's interleaved format (SMD) are
//! read through their interleaving.
//!
//! - <https://plutiedev.com/rom-header>
//! - <https://www.retrodev.com/segacd.html>

use std::io::{Read, Seek};

use crate::bytes::{array_at, ascii, be16, be32, shift_jis, stream_len};
use crate::{Error, Result};

/// How a cartridge dump is laid out.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Format {
    /// As the cartridge is: `.md`, `.bin`, `.gen`.
    Plain,
    /// The Super Magic Drive's: a 512-byte header, then 16 KiB blocks, each
    /// with the odd bytes first.
    Smd,
}

impl core::fmt::Display for Format {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str(match self {
            Self::Plain => "Plain",
            Self::Smd => "SMD (interleaved)",
        })
    }
}

/// The regions a game supports, from the region field at $1F0: the old
/// letters `J`, `U` and `E`, or a hex digit whose bits say the same.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub struct Regions {
    /// Japan: domestic, 60 Hz.
    pub japan: bool,
    /// Americas: overseas, 60 Hz.
    pub americas: bool,
    /// Europe: overseas, 50 Hz.
    pub europe: bool,
}

impl Regions {
    fn parse(field: &[u8]) -> Self {
        let code: Vec<u8> = field
            .iter()
            .copied()
            .take_while(|&c| c != 0 && c != b' ')
            .collect();
        let letters = Self {
            japan: code.contains(&b'J'),
            americas: code.contains(&b'U'),
            europe: code.contains(&b'E'),
        };
        if letters != Self::default() {
            return letters;
        }
        // A single hex digit: bit 0 Japan, bit 2 Americas, bit 3 Europe.
        match code.as_slice() {
            [digit] => match (*digit as char).to_digit(16) {
                Some(bits) => Self {
                    japan: bits & 1 != 0,
                    americas: bits & 4 != 0,
                    europe: bits & 8 != 0,
                },
                None => Self::default(),
            },
            _ => Self::default(),
        }
    }
}

impl Regions {
    /// The names of the regions the game plays in.
    pub fn names(&self) -> Vec<&'static str> {
        [
            (self.japan, "Japan"),
            (self.americas, "Americas"),
            (self.europe, "Europe"),
        ]
        .into_iter()
        .filter_map(|(on, name)| on.then_some(name))
        .collect()
    }
}

impl core::fmt::Display for Regions {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str(&self.names().join(", "))
    }
}

/// The header at $100 that cartridges and Mega CD discs share.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Header {
    /// Such as `SEGA MEGA DRIVE`, `SEGA GENESIS` or `SEGA 32X`.
    pub system_type: String,
    /// The copyright holder and release date, such as `(C)SEGA 1991.APR`.
    pub copyright: String,
    /// The Japanese title, in Shift-JIS.
    pub domestic_title: String,
    /// The title for everywhere else.
    pub overseas_title: String,
    /// The serial number, such as `GM 00001009-00`: type, product code and
    /// version.
    pub serial_number: String,
    /// The checksum over the ROM after the header. Mega CD discs don't use it.
    pub checksum: u16,
    /// The input devices the game supports, one letter each, such as `J` for
    /// the three-button pad and `6` for the six-button one.
    pub devices: String,
    pub rom_start: u32,
    pub rom_end: u32,
    pub ram_start: u32,
    pub ram_end: u32,
    /// The extra memory field: `RA` and its type when the cartridge saves.
    pub extra_memory: String,
    pub modem: String,
    pub regions: Regions,
}

/// A Mega Drive or 32X cartridge's header.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Cartridge {
    pub format: Format,
    /// Whether the cartridge has the 32X's `MARS` header.
    pub is_32x: bool,
    pub header: Header,
}

/// Whether a Mega CD disc boots, by its identifier.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum DiscType {
    /// `SEGADISCSYSTEM` or `SEGABOOTDISC`.
    Bootable,
    /// `SEGADISC` or `SEGADATADISC`.
    Data,
}

/// A Mega CD disc's header, from its first sector.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Disc {
    /// The disc identifier, such as `SEGADISCSYSTEM`.
    pub disc_id: String,
    pub disc_type: DiscType,
    pub header: Header,
}

/// Reads a Mega Drive or 32X cartridge's header, plain or SMD.
pub fn read<R: Read + Seek + ?Sized>(reader: &mut R) -> Result<Cartridge> {
    let size = stream_len(reader)?;
    let (format, start) = if size >= 512 + 0x4000 && is_smd(&array_at(reader, 0)?) {
        let block: [u8; 0x4000] = array_at(reader, 512)?;
        (Format::Smd, deinterleave(&block).to_vec())
    } else {
        let mut start = vec![0u8; 0x3C4.min(size as usize)];
        crate::bytes::read_at(reader, 0, &mut start)?;
        (Format::Plain, start)
    };
    if start.len() < 0x200 {
        return Err(Error::NotRecognized("Mega Drive"));
    }
    let header = parse(&start).ok_or(Error::NotRecognized("Mega Drive"))?;
    Ok(Cartridge {
        format,
        is_32x: start.get(0x3C0..0x3C4) == Some(b"MARS"),
        header,
    })
}

/// Reads a Mega CD disc's header from its first 2048-byte sector, as
/// [`crate::disc`] reads it.
pub fn read_disc<R: Read + Seek + ?Sized>(reader: &mut R) -> Result<Disc> {
    let sector: [u8; 0x200] = array_at(reader, 0).map_err(Error::short("Mega CD"))?;
    let disc_type = match &sector[..16] {
        b"SEGADISCSYSTEM  " | b"SEGABOOTDISC    " => DiscType::Bootable,
        b"SEGADISC        " | b"SEGADATADISC    " => DiscType::Data,
        _ => return Err(Error::NotRecognized("Mega CD")),
    };
    Ok(Disc {
        disc_id: ascii(&sector[..16]),
        disc_type,
        header: parse_fields(&sector),
    })
}

/// The header in the first $200 bytes, when its system type names Sega.
fn parse(start: &[u8]) -> Option<Header> {
    let header = parse_fields(start);
    header.system_type.contains("SEGA").then_some(header)
}

fn parse_fields(s: &[u8]) -> Header {
    let devices = &s[0x190..0x1A0];
    Header {
        system_type: ascii(&s[0x100..0x110]),
        copyright: ascii(&s[0x110..0x120]),
        domestic_title: shift_jis(&s[0x120..0x150]),
        overseas_title: ascii(&s[0x150..0x180]),
        serial_number: ascii(&s[0x180..0x18E]),
        checksum: be16(s, 0x18E),
        devices: ascii(
            &devices[..devices
                .iter()
                .position(|&c| c == 0 || c == b' ')
                .unwrap_or(devices.len())],
        ),
        rom_start: be32(s, 0x1A0),
        rom_end: be32(s, 0x1A4),
        ram_start: be32(s, 0x1A8),
        ram_end: be32(s, 0x1AC),
        extra_memory: ascii(&s[0x1B0..0x1BC]),
        modem: ascii(&s[0x1BC..0x1C8]),
        regions: Regions::parse(&s[0x1F0..0x200]),
    }
}

/// Whether a 512-byte block is an SMD header.
fn is_smd(header: &[u8; 512]) -> bool {
    header[1] == 0x03 && header[8] == 0xAA && header[9] == 0xBB && header[3..8] == [0; 5]
}

/// Puts a 16 KiB SMD block back in order: its second half holds the even
/// bytes, its first half the odd ones.
fn deinterleave(block: &[u8; 0x4000]) -> [u8; 0x4000] {
    let mut out = [0u8; 0x4000];
    for i in 0..0x2000 {
        out[i * 2] = block[0x2000 + i];
        out[i * 2 + 1] = block[i];
    }
    out
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;

    fn header() -> Vec<u8> {
        let mut rom = vec![0u8; 0x4000];
        let mut put = |at: usize, text: &[u8]| rom[at..at + text.len()].copy_from_slice(text);
        put(0x100, b"SEGA MEGA DRIVE ");
        put(0x110, b"(C)SEGA 1991.APR");
        put(
            0x120,
            b"\x83\x5c\x83\x6a\x83\x62\x83\x4e                                        ",
        );
        put(0x150, b"SONIC THE               HEDGEHOG                ");
        put(0x180, b"GM 00001009-00");
        put(0x18E, &[0x26, 0x4A]);
        put(0x190, b"J               ");
        put(0x1A0, &[0, 0, 0, 0, 0, 0x07, 0xFF, 0xFF]);
        put(0x1F0, b"JUE             ");
        rom
    }

    #[test]
    fn reads_a_cartridge() {
        let c = read(&mut Cursor::new(header())).unwrap();
        assert_eq!(c.format, Format::Plain);
        assert!(!c.is_32x);
        let h = c.header;
        assert_eq!(h.system_type, "SEGA MEGA DRIVE");
        assert_eq!(h.copyright, "(C)SEGA 1991.APR");
        assert_eq!(h.domestic_title, "ソニック");
        assert_eq!(h.overseas_title, "SONIC THE               HEDGEHOG");
        assert_eq!(h.serial_number, "GM 00001009-00");
        assert_eq!(h.checksum, 0x264A);
        assert_eq!(h.devices, "J");
        assert_eq!(h.rom_end, 0x7FFFF);
        assert_eq!(h.regions.to_string(), "Japan, Americas, Europe");
    }

    #[test]
    fn reads_an_smd_dump() {
        let plain = header();
        let mut smd = vec![0u8; 512];
        smd[1] = 0x03;
        smd[8] = 0xAA;
        smd[9] = 0xBB;
        let odd: Vec<u8> = plain.iter().skip(1).step_by(2).copied().collect();
        let even: Vec<u8> = plain.iter().step_by(2).copied().collect();
        smd.extend(odd);
        smd.extend(even);
        let c = read(&mut Cursor::new(smd)).unwrap();
        assert_eq!(c.format, Format::Smd);
        assert_eq!(c.header.serial_number, "GM 00001009-00");
    }

    #[test]
    fn reads_a_32x_cartridge() {
        let mut rom = header();
        rom[0x100..0x110].copy_from_slice(b"SEGA 32X        ");
        rom[0x3C0..0x3C4].copy_from_slice(b"MARS");
        assert!(read(&mut Cursor::new(rom)).unwrap().is_32x);
    }

    #[test]
    fn reads_regions_as_bits() {
        assert_eq!(
            Regions::parse(b"4"),
            Regions {
                japan: false,
                americas: true,
                europe: false
            }
        );
        assert_eq!(Regions::parse(b"F").to_string(), "Japan, Americas, Europe");
        assert_eq!(Regions::parse(b"  "), Regions::default());
    }

    #[test]
    fn reads_a_mega_cd_disc() {
        let mut sector = header();
        sector[..16].copy_from_slice(b"SEGADISCSYSTEM  ");
        sector[0x100..0x110].copy_from_slice(b"SEGA MEGA CD    ");
        let d = read_disc(&mut Cursor::new(sector)).unwrap();
        assert_eq!(d.disc_id, "SEGADISCSYSTEM");
        assert_eq!(d.disc_type, DiscType::Bootable);
        assert_eq!(d.header.serial_number, "GM 00001009-00");
    }

    #[test]
    fn refuses_other_files() {
        assert!(read(&mut Cursor::new(vec![0u8; 0x4000])).is_err());
        assert!(read_disc(&mut Cursor::new(vec![0u8; 0x800])).is_err());
    }
}
