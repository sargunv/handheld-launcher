//! The Dreamcast's IP.BIN, which starts the first sector of a disc's
//! high-density area (a GD-ROM's third track) with `SEGA SEGAKATANA`.
//!
//! - <https://mc.pp.se/dc/ip0000.bin.html>

use std::io::{Read, Seek};

use crate::bytes::{array_at, ascii, shift_jis};
use crate::{Error, Result};

/// A Dreamcast disc's IP.BIN header.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Header {
    /// Such as `SEGA ENTERPRISES`.
    pub maker_id: String,
    /// The CRC of the product number and version, then the media and the
    /// disc's number among the game's discs, such as `5BB1 GD-ROM1/1`.
    pub device_info: String,
    /// The area symbols in their fixed places: `J` for Japan, `U` for the
    /// Americas and `E` for Europe, such as `JUE`.
    pub areas: String,
    /// The peripherals the game supports, as hex flags.
    pub peripherals: String,
    /// Such as `MK-51058` or `T-8101N`.
    pub product_number: String,
    /// Such as `V1.005`.
    pub version: String,
    /// The release date, as the header writes it: `YYYYMMDD`.
    pub release_date: String,
    /// The program the disc boots, such as `1ST_READ.BIN`.
    pub boot_filename: String,
    /// The company that made the software.
    pub software_maker: String,
    /// The title, in Shift-JIS.
    pub title: String,
}

/// What an area symbol means.
pub fn area_name(symbol: char) -> Option<&'static str> {
    Some(match symbol {
        'J' => "Japan",
        'U' => "Americas",
        'E' => "Europe",
        _ => return None,
    })
}

/// Reads a Dreamcast disc's IP.BIN from the first 2048-byte sector of its
/// high-density area, as [`crate::disc`] reads it.
pub fn read<R: Read + Seek + ?Sized>(reader: &mut R) -> Result<Header> {
    let h: [u8; 0x100] = array_at(reader, 0).map_err(Error::short("Dreamcast"))?;
    if &h[..16] != b"SEGA SEGAKATANA " {
        return Err(Error::NotRecognized("Dreamcast"));
    }
    Ok(Header {
        maker_id: ascii(&h[0x10..0x20]),
        device_info: ascii(&h[0x20..0x30]),
        areas: ascii(&h[0x30..0x38]).replace(' ', ""),
        peripherals: ascii(&h[0x38..0x40]),
        product_number: ascii(&h[0x40..0x4A]),
        version: ascii(&h[0x4A..0x50]),
        release_date: ascii(&h[0x50..0x58]),
        boot_filename: ascii(&h[0x60..0x70]),
        software_maker: ascii(&h[0x70..0x80]),
        title: shift_jis(&h[0x80..0x100]),
    })
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;

    #[test]
    fn reads_ip_bin() {
        let mut sector = vec![b' '; 0x800];
        let mut put = |at: usize, text: &[u8]| sector[at..at + text.len()].copy_from_slice(text);
        put(
            0,
            b"SEGA SEGAKATANA SEGA ENTERPRISES5BB1 GD-ROM1/1  JUE     0799A10 ",
        );
        put(0x40, b"MK-51058  V1.00520000911");
        put(0x60, b"1ST_READ.BIN    SEGA ENTERPRISES");
        put(0x80, b"\x83\x5c\x83\x6a\x83\x62\x83\x4e ADVENTURE 2");
        let h = read(&mut Cursor::new(sector)).unwrap();
        assert_eq!(h.maker_id, "SEGA ENTERPRISES");
        assert_eq!(h.device_info, "5BB1 GD-ROM1/1");
        assert_eq!(h.areas, "JUE");
        assert_eq!(h.peripherals, "0799A10");
        assert_eq!(h.product_number, "MK-51058");
        assert_eq!(h.version, "V1.005");
        assert_eq!(h.release_date, "20000911");
        assert_eq!(h.boot_filename, "1ST_READ.BIN");
        assert_eq!(h.software_maker, "SEGA ENTERPRISES");
        assert_eq!(h.title, "ソニック ADVENTURE 2");
    }

    #[test]
    fn refuses_other_sectors() {
        assert!(read(&mut Cursor::new(vec![0u8; 0x800])).is_err());
    }
}
