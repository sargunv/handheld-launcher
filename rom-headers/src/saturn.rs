//! The Saturn's system ID, which starts a disc's first sector with
//! `SEGA SEGASATURN`.
//!
//! - <https://antime.kapsi.fi/sega/files/ST-040-R4-051795.pdf>

use std::io::{Read, Seek};

use crate::bytes::{array_at, ascii, shift_jis};
use crate::{Error, Result};

/// A Saturn disc's system ID.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Header {
    /// Such as `SEGA ENTERPRISES` or `SEGA TP T-81`.
    pub maker_id: String,
    /// Such as `MK-81022` or `T-17602G`.
    pub product_number: String,
    /// Such as `V1.000`.
    pub version: String,
    /// The release date, as the header writes it: `YYYYMMDD`.
    pub release_date: String,
    /// Such as `CD-1/1`: the disc's number among the game's discs.
    pub device_info: String,
    /// The area symbols, one letter each, such as `JTUE`. See
    /// [`area_name`].
    pub areas: String,
    /// The peripherals the game supports, one letter each, such as `J` for
    /// the control pad.
    pub peripherals: String,
    /// The title, in Shift-JIS.
    pub title: String,
}

/// What an area symbol means.
pub fn area_name(symbol: char) -> Option<&'static str> {
    Some(match symbol {
        'J' => "Japan",
        'T' => "Asia (NTSC)",
        'U' => "North America",
        'B' => "Central and South America (NTSC)",
        'K' => "Korea",
        'A' => "East Asia (PAL)",
        'E' => "Europe",
        'L' => "Central and South America (PAL)",
        _ => return None,
    })
}

/// Reads a Saturn disc's system ID from its first 2048-byte sector, as
/// [`crate::disc`] reads it.
pub fn read<R: Read + Seek + ?Sized>(reader: &mut R) -> Result<Header> {
    let h: [u8; 0x100] = array_at(reader, 0).map_err(Error::short("Saturn"))?;
    if &h[..16] != b"SEGA SEGASATURN " {
        return Err(Error::NotRecognized("Saturn"));
    }
    Ok(Header {
        maker_id: ascii(&h[0x10..0x20]),
        product_number: ascii(&h[0x20..0x2A]),
        version: ascii(&h[0x2A..0x30]),
        release_date: ascii(&h[0x30..0x38]),
        device_info: ascii(&h[0x38..0x40]),
        areas: ascii(&h[0x40..0x50]).replace(' ', ""),
        peripherals: ascii(&h[0x50..0x60]).replace(' ', ""),
        title: shift_jis(&h[0x60..0xD0]),
    })
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;

    #[test]
    fn reads_a_system_id() {
        let mut sector = vec![b' '; 0x800];
        let mut put = |at: usize, text: &[u8]| sector[at..at + text.len()].copy_from_slice(text);
        put(
            0,
            b"SEGA SEGASATURN SEGA ENTERPRISESMK-81022  V1.00019951110CD-1/1  JTUE",
        );
        put(0x50, b"J");
        put(0x60, b"NIGHTS into dreams...");
        let h = read(&mut Cursor::new(sector)).unwrap();
        assert_eq!(h.maker_id, "SEGA ENTERPRISES");
        assert_eq!(h.product_number, "MK-81022");
        assert_eq!(h.version, "V1.000");
        assert_eq!(h.release_date, "19951110");
        assert_eq!(h.device_info, "CD-1/1");
        assert_eq!(h.areas, "JTUE");
        assert_eq!(h.peripherals, "J");
        assert_eq!(h.title, "NIGHTS into dreams...");
        assert_eq!(area_name('U'), Some("North America"));
    }

    #[test]
    fn refuses_other_sectors() {
        assert!(read(&mut Cursor::new(vec![0u8; 0x800])).is_err());
    }
}
