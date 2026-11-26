//! The Game Boy Advance cartridge header, the first 192 bytes.
//!
//! - <https://problemkaputt.de/gbatek-gba-cartridge-header.htm>

use std::io::{Read, Seek};

use crate::bytes::{array_at, ascii};
use crate::code::char_code;
use crate::{Error, Result};

char_code! {
    /// The game type, the game code's first character.
    GameType {
        NORMAL_OLD = b'A' => "Normal (until 2003)",
        NORMAL_NEW = b'B' => "Normal (from 2003)",
        NORMAL = b'C' => "Normal",
        FAMICOM_MINI = b'F' => "Classic NES Series",
        ACCELEROMETER = b'K' => "With accelerometer",
        E_READER = b'P' => "e-Reader",
        RUMBLE_GYRO = b'R' => "With rumble and gyro sensor",
        RTC_SOLAR = b'U' => "With clock and solar sensor",
        RUMBLE = b'V' => "With rumble",
    }
}

char_code! {
    /// The destination, the game code's last character.
    Destination {
        GERMANY = b'D' => "Germany",
        USA = b'E' => "USA",
        FRANCE = b'F' => "France",
        ITALY = b'I' => "Italy",
        JAPAN = b'J' => "Japan",
        EUROPE = b'P' => "Europe",
        SPAIN = b'S' => "Spain",
    }
}

/// A Game Boy Advance ROM's header.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Header {
    /// Up to 12 characters.
    pub title: String,
    /// The four-character game code: game type, unique code and destination.
    pub game_code: String,
    pub game_type: GameType,
    pub destination: Destination,
    /// The two-character maker code.
    pub maker_code: String,
    /// The main unit code: $00 for the Game Boy Advance.
    pub main_unit_code: u8,
    /// The device type, whose bit 7 enables the debugging handler.
    pub device_type: u8,
    /// The software version: 0 for the first release.
    pub version: u8,
    pub complement_check: u8,
    /// Whether [`Header::complement_check`] matches the header, as the BIOS
    /// checks before it starts the game.
    pub complement_check_valid: bool,
}

/// Reads a Game Boy Advance ROM's header.
pub fn read<R: Read + Seek + ?Sized>(reader: &mut R) -> Result<Header> {
    let h: [u8; 0xC0] = array_at(reader, 0).map_err(Error::short("Game Boy Advance"))?;
    // The fixed value every header holds.
    if h[0xB2] != 0x96 {
        return Err(Error::NotRecognized("Game Boy Advance"));
    }
    let complement = h[0xA0..0xBD]
        .iter()
        .fold(0u8, |x, &c| x.wrapping_sub(c))
        .wrapping_sub(0x19);
    Ok(Header {
        title: ascii(&h[0xA0..0xAC]),
        game_code: ascii(&h[0xAC..0xB0]),
        game_type: GameType(h[0xAC]),
        destination: Destination(h[0xAF]),
        maker_code: ascii(&h[0xB0..0xB2]),
        main_unit_code: h[0xB3],
        device_type: h[0xB4],
        version: h[0xBC],
        complement_check: h[0xBD],
        complement_check_valid: complement == h[0xBD],
    })
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;

    #[test]
    fn reads_a_header() {
        let mut rom = vec![0u8; 0xC0];
        rom[0xA0..0xAC].copy_from_slice(b"POKEMON EMER");
        rom[0xAC..0xB2].copy_from_slice(b"BPEE01");
        rom[0xB2] = 0x96;
        rom[0xBC] = 0x01;
        rom[0xBD] = rom[0xA0..0xBD]
            .iter()
            .fold(0u8, |x, &c| x.wrapping_sub(c))
            .wrapping_sub(0x19);
        let h = read(&mut Cursor::new(&rom)).unwrap();
        assert_eq!(h.title, "POKEMON EMER");
        assert_eq!(h.game_code, "BPEE");
        assert_eq!(h.game_type, GameType::NORMAL_NEW);
        assert_eq!(h.destination, Destination::USA);
        assert_eq!(h.maker_code, "01");
        assert_eq!(h.version, 1);
        assert!(h.complement_check_valid);
    }

    #[test]
    fn refuses_other_files() {
        assert!(read(&mut Cursor::new(vec![0u8; 0xC0])).is_err());
    }
}
