//! The disc header GameCube and Wii discs start with, found inside the image
//! that holds it: a plain disc image (GCM, ISO), Dolphin's RVZ and WIA, which
//! keep it uncompressed, WBFS, Dolphin's CISO and, with the `deflate` feature,
//! GCZ.
//!
//! - <https://wiibrew.org/wiki/Wii_disc#Header>
//! - <https://wiki.dolphin-emu.org/index.php?title=GameIDs>
//! - <https://github.com/dolphin-emu/dolphin/blob/master/docs/WiaAndRvz.md>

use std::io::{Read, Seek};

use crate::bytes::{array_at, ascii, be32};
use crate::code::char_code;
use crate::{Error, Result};

/// What holds the disc.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Container {
    /// A plain disc image.
    Iso,
    Rvz,
    Wia,
    Wbfs,
    /// Dolphin's compact ISO, which leaves out unused blocks.
    Ciso,
    /// Dolphin's zlib-compressed image.
    Gcz,
}

impl core::fmt::Display for Container {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str(match self {
            Self::Iso => "ISO",
            Self::Rvz => "RVZ",
            Self::Wia => "WIA",
            Self::Wbfs => "WBFS",
            Self::Ciso => "CISO",
            Self::Gcz => "GCZ",
        })
    }
}

/// Which console's magic word the header carries.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Console {
    GameCube,
    Wii,
}

impl core::fmt::Display for Console {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str(match self {
            Self::GameCube => "GameCube",
            Self::Wii => "Wii",
        })
    }
}

char_code! {
    /// The system code, the game id's first character.
    SystemCode {
        GAMECUBE = b'G' => "GameCube",
        GAMECUBE_RERELEASE = b'D' => "GameCube rerelease or demo",
        PROMOTIONAL = b'P' => "Promotional",
        WII = b'R' => "Wii",
        WII_NEWER = b'S' => "Wii",
        WII_CHANNEL = b'H' => "Wii channel",
        WIIWARE = b'W' => "WiiWare",
        NES = b'F' => "NES Virtual Console",
        SNES = b'J' => "Super NES Virtual Console",
        N64 = b'N' => "Nintendo 64 Virtual Console",
        MASTER_SYSTEM = b'L' => "Master System Virtual Console",
        GENESIS = b'M' => "Genesis Virtual Console",
        TURBOGRAFX_CD = b'Q' => "TurboGrafx-CD Virtual Console",
        COMMODORE_64 = b'C' => "Commodore 64 Virtual Console",
        ARCADE = b'E' => "Arcade Virtual Console",
        MSX = b'X' => "MSX Virtual Console or WiiWare demo",
    }
}

char_code! {
    /// The region code, the game id's fourth character.
    RegionCode {
        SYSTEM_CHANNEL = b'A' => "All regions",
        GERMANY = b'D' => "Germany",
        USA = b'E' => "USA",
        FRANCE = b'F' => "France",
        NETHERLANDS = b'H' => "Netherlands",
        ITALY = b'I' => "Italy",
        JAPAN = b'J' => "Japan",
        KOREA = b'K' => "Korea",
        JAPAN_IMPORT_PAL = b'L' => "Japanese import to Europe",
        USA_IMPORT_PAL = b'M' => "American import to Europe",
        JAPAN_IMPORT_USA = b'N' => "Japanese import to USA",
        EUROPE = b'P' => "Europe",
        JAPAN_IMPORT_KOREA = b'Q' => "Japanese import to Korea",
        RUSSIA = b'R' => "Russia",
        SPAIN = b'S' => "Spain",
        USA_IMPORT_KOREA = b'T' => "American import to Korea",
        AUSTRALIA = b'U' => "Australia",
        SCANDINAVIA = b'V' => "Scandinavia",
        TAIWAN = b'W' => "Taiwan",
        SPECIAL_X = b'X' => "Europe (special release)",
        SPECIAL_Y = b'Y' => "Europe (special release)",
        SPECIAL_Z = b'Z' => "Europe (special release)",
    }
}

/// A GameCube or Wii disc's header.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Header {
    pub container: Container,
    pub console: Console,
    /// The six-character id: system code, game code, region code and maker
    /// code, such as `GALE01`.
    pub game_id: String,
    pub system_code: SystemCode,
    pub region_code: RegionCode,
    /// The two-character maker code.
    pub maker_code: String,
    /// The disc number, from 0.
    pub disc_number: u8,
    /// The disc version: 0 for the first release.
    pub version: u8,
    /// Up to 64 characters.
    pub title: String,
}

/// Reads a GameCube or Wii disc's header.
pub fn read<R: Read + Seek + ?Sized>(reader: &mut R) -> Result<Header> {
    let start: [u8; 0x20] = array_at(reader, 0).map_err(Error::short("GameCube or Wii"))?;
    let (container, offset) = match &start[..4] {
        b"RVZ\x01" => (Container::Rvz, 0x58),
        b"WIA\x01" => (Container::Wia, 0x58),
        b"WBFS" => {
            // The disc's copy of its header starts the second sector.
            let shift = start[8];
            if !(9..=16).contains(&shift) {
                return Err(Error::NotRecognized("WBFS"));
            }
            (Container::Wbfs, 1u64 << shift)
        }
        // The first block follows the 32 KiB block map.
        b"CISO" => (Container::Ciso, 0x8000),
        [0x01, 0xC0, 0x0B, 0xB1] => {
            #[cfg(feature = "deflate")]
            {
                let block = gcz_first_block(reader)?;
                return parse(&block, Container::Gcz);
            }
            #[cfg(not(feature = "deflate"))]
            return Err(Error::Unsupported("GCZ needs the deflate feature"));
        }
        _ => (Container::Iso, 0),
    };
    let h: [u8; 0x60] = array_at(reader, offset).map_err(Error::short("GameCube or Wii"))?;
    parse(&h, container)
}

fn parse(h: &[u8], container: Container) -> Result<Header> {
    if h.len() < 0x60 {
        return Err(Error::NotRecognized("GameCube or Wii"));
    }
    let console = if be32(h, 0x18) == 0x5D1C_9EA3 {
        Console::Wii
    } else if be32(h, 0x1C) == 0xC233_9F3D {
        Console::GameCube
    } else {
        return Err(Error::NotRecognized("GameCube or Wii"));
    };
    Ok(Header {
        container,
        console,
        game_id: ascii(&h[..6]),
        system_code: SystemCode(h[0]),
        region_code: RegionCode(h[3]),
        maker_code: ascii(&h[4..6]),
        disc_number: h[6],
        version: h[7],
        title: ascii(&h[0x20..0x60]),
    })
}

/// The first block of a GCZ image, which holds the disc header.
#[cfg(feature = "deflate")]
fn gcz_first_block<R: Read + Seek + ?Sized>(reader: &mut R) -> Result<Vec<u8>> {
    use crate::bytes::{le32, le64, vec_at};

    let head: [u8; 0x30] = array_at(reader, 0).map_err(Error::short("GCZ"))?;
    let compressed_size = le64(&head, 8);
    let block_size = le32(&head, 24) as usize;
    let blocks = u64::from(le32(&head, 28));
    if block_size == 0 || block_size > 1 << 24 || blocks == 0 {
        return Err(Error::NotRecognized("GCZ"));
    }
    let first = le64(&head, 32);
    // A block ends where the next starts, and the last where the data does.
    let next = if blocks > 1 {
        le64(&head, 40)
    } else {
        compressed_size
    };
    let stored = first & (1 << 63) != 0;
    let start = first & !(1 << 63);
    let end = next & !(1 << 63);
    let length = end
        .checked_sub(start)
        .filter(|&n| n <= 2 * block_size as u64)
        .ok_or(Error::NotRecognized("GCZ"))?;
    // The block pointers and their hashes come before the data.
    let data = 32 + blocks * 12;
    let raw = vec_at(reader, data + start, length as usize)?;
    if stored {
        return Ok(raw);
    }
    miniz_oxide::inflate::decompress_to_vec_zlib_with_limit(&raw, block_size)
        .map_err(|_| Error::NotRecognized("GCZ"))
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;

    fn disc(id: &[u8; 6], wii: bool) -> Vec<u8> {
        let mut h = vec![0u8; 0x440];
        h[..6].copy_from_slice(id);
        h[6] = 1;
        h[7] = 2;
        if wii {
            h[0x18..0x1C].copy_from_slice(&0x5D1C_9EA3u32.to_be_bytes());
        } else {
            h[0x1C..0x20].copy_from_slice(&0xC233_9F3Du32.to_be_bytes());
        }
        h[0x20..0x30].copy_from_slice(b"Super Smash Bros");
        h
    }

    #[test]
    fn reads_a_plain_image() {
        let h = read(&mut Cursor::new(disc(b"GALE01", false))).unwrap();
        assert_eq!(h.container, Container::Iso);
        assert_eq!(h.console, Console::GameCube);
        assert_eq!(h.game_id, "GALE01");
        assert_eq!(h.system_code, SystemCode::GAMECUBE);
        assert_eq!(h.region_code, RegionCode::USA);
        assert_eq!(h.maker_code, "01");
        assert_eq!(h.disc_number, 1);
        assert_eq!(h.version, 2);
        assert_eq!(h.title, "Super Smash Bros");
    }

    #[test]
    fn reads_compressed_containers() {
        let mut rvz = b"RVZ\x01".to_vec();
        rvz.resize(0x58, 0);
        rvz.extend(disc(b"RMGE01", true));
        let h = read(&mut Cursor::new(rvz)).unwrap();
        assert_eq!((h.container, h.console), (Container::Rvz, Console::Wii));
        assert_eq!(h.game_id, "RMGE01");

        let mut wbfs = b"WBFS\0\0\0\0\x09".to_vec();
        wbfs.resize(0x200, 0);
        wbfs.extend(disc(b"RMGE01", true));
        let h = read(&mut Cursor::new(wbfs)).unwrap();
        assert_eq!(h.container, Container::Wbfs);

        let mut ciso = b"CISO".to_vec();
        ciso.resize(0x8000, 0);
        ciso.extend(disc(b"GALE01", false));
        assert_eq!(
            read(&mut Cursor::new(ciso)).unwrap().container,
            Container::Ciso
        );
    }

    #[cfg(feature = "deflate")]
    #[test]
    fn reads_gcz() {
        let block = disc(b"GALP01", false);
        let compressed = miniz_oxide::deflate::compress_to_vec_zlib(&block, 6);
        let mut gcz = Vec::new();
        gcz.extend_from_slice(&0xB10B_C001u32.to_le_bytes());
        gcz.extend_from_slice(&0u32.to_le_bytes());
        gcz.extend_from_slice(&(compressed.len() as u64).to_le_bytes());
        gcz.extend_from_slice(&(block.len() as u64).to_le_bytes());
        gcz.extend_from_slice(&(block.len() as u32).to_le_bytes());
        gcz.extend_from_slice(&1u32.to_le_bytes());
        gcz.extend_from_slice(&0u64.to_le_bytes());
        gcz.extend_from_slice(&0u32.to_le_bytes());
        gcz.extend(compressed);
        let h = read(&mut Cursor::new(gcz)).unwrap();
        assert_eq!(h.container, Container::Gcz);
        assert_eq!(h.game_id, "GALP01");
    }

    #[test]
    fn refuses_other_files() {
        assert!(read(&mut Cursor::new(vec![0u8; 0x100])).is_err());
    }
}
