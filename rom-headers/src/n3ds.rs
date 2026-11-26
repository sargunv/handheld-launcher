//! The Nintendo 3DS's NCCH header, which every game's main content starts
//! with, found inside the image that holds it: a cartridge image (CCI, NCSD),
//! a bare NCCH (CXI), or an installable CIA, whose contents are readable only
//! when they're decrypted. With the `zstd` feature, also inside Azahar's
//! compressed images (Z3DS: `.zcci`, `.zcxi`, `.zcia`).
//!
//! - <https://www.3dbrew.org/wiki/NCSD>
//! - <https://www.3dbrew.org/wiki/NCCH>
//! - <https://www.3dbrew.org/wiki/CIA>

use std::io::{Read, Seek};

use crate::bytes::{array_at, ascii, le16, le32, le64};
use crate::code::char_code;
use crate::{Error, Result};

const MEDIA_UNIT: u64 = 0x200;

/// What holds the game's NCCH.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Container {
    /// A cartridge image: an NCSD whose first partition is the game.
    Cci,
    /// A bare NCCH.
    Cxi,
    /// An installable archive, whose first content is the game.
    Cia,
}

impl core::fmt::Display for Container {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str(match self {
            Self::Cci => "CCI",
            Self::Cxi => "CXI",
            Self::Cia => "CIA",
        })
    }
}

char_code! {
    /// The region, the last character of the product code's game code.
    Region {
        CHINA = b'C' => "China",
        USA = b'E' => "USA",
        JAPAN = b'J' => "Japan",
        KOREA = b'K' => "Korea",
        EUROPE = b'P' => "Europe",
        TAIWAN = b'T' => "Taiwan",
        AUSTRALIA = b'U' => "Australia",
        REGION_FREE = b'A' => "Region free",
    }
}

/// A cartridge image's NCSD header.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Ncsd {
    pub media_id: u64,
    /// The image, in bytes.
    pub image_size: u64,
    /// How many of its eight partitions are in use.
    pub partitions: u8,
}

/// A 3DS game's header: its NCCH, and what holds it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Header {
    pub container: Container,
    /// Whether the image is compressed with Z3DS.
    pub compressed: bool,
    /// The NCSD header, for a cartridge image.
    pub ncsd: Option<Ncsd>,
    /// The program's title id, which the system files its saves under.
    pub program_id: u64,
    pub partition_id: u64,
    /// The two-character maker code.
    pub maker_code: String,
    /// The NCCH format version.
    pub ncch_version: u16,
    /// Such as `CTR-P-AJRE`: platform, type and game code.
    pub product_code: String,
    pub region: Option<Region>,
    /// The content type flags: bit 0 data, bit 1 executable, and above them
    /// system update, manual, child or trial.
    pub content_type: u8,
    /// Whether the game runs only on the New 3DS.
    pub new_3ds_only: bool,
}

/// Reads the header of a 3DS game's image.
pub fn read<R: Read + Seek + ?Sized>(reader: &mut R) -> Result<Header> {
    let magic: [u8; 4] = array_at(reader, 0).map_err(Error::short("3DS"))?;
    if &magic == b"Z3DS" {
        #[cfg(feature = "zstd")]
        {
            let mut image = crate::z3ds::Z3ds::open(reader)?;
            let mut header = read_plain(&mut image)?;
            header.compressed = true;
            return Ok(header);
        }
        #[cfg(not(feature = "zstd"))]
        return Err(Error::Unsupported("Z3DS needs the zstd feature"));
    }
    read_plain(reader)
}

fn read_plain<R: Read + Seek + ?Sized>(reader: &mut R) -> Result<Header> {
    let first: [u8; 0x200] = array_at(reader, 0).map_err(Error::short("3DS"))?;
    let (container, ncsd, ncch_offset) = match &first[0x100..0x104] {
        b"NCSD" => {
            let image_size = u64::from(le32(&first, 0x104)) * MEDIA_UNIT;
            let partitions = (0..8)
                .filter(|i| {
                    let entry = 0x120 + i * 8;
                    le32(&first, entry) != 0 && le32(&first, entry + 4) != 0
                })
                .count() as u8;
            let ncsd = Ncsd {
                media_id: le64(&first, 0x108),
                image_size,
                partitions,
            };
            let offset = u64::from(le32(&first, 0x120)) * MEDIA_UNIT;
            (Container::Cci, Some(ncsd), offset)
        }
        b"NCCH" => (Container::Cxi, None, 0),
        _ if le32(&first, 0) == 0x2020 => {
            // Each section starts on a 64-byte boundary: the header, the
            // certificate chain, the ticket and the TMD, then the contents.
            let align = |n: u64| n.div_ceil(64) * 64;
            let offset = [0, 0x08, 0x0C, 0x10]
                .iter()
                .map(|&at| align(u64::from(le32(&first, at))))
                .sum();
            (Container::Cia, None, offset)
        }
        _ => return Err(Error::NotRecognized("3DS")),
    };
    let ncch: [u8; 0x200] = array_at(reader, ncch_offset).map_err(Error::short("3DS"))?;
    if &ncch[0x100..0x104] != b"NCCH" {
        return Err(Error::NotRecognized("3DS"));
    }
    let product_code = ascii(&ncch[0x150..0x160]);
    let region = product_code.as_bytes().get(9).map(|&c| Region(c));
    Ok(Header {
        container,
        compressed: false,
        ncsd,
        program_id: le64(&ncch, 0x118),
        partition_id: le64(&ncch, 0x108),
        maker_code: ascii(&ncch[0x110..0x112]),
        ncch_version: le16(&ncch, 0x112),
        product_code,
        region,
        content_type: ncch[0x188 + 5],
        new_3ds_only: ncch[0x188 + 4] & 0x02 != 0,
    })
}

#[cfg(test)]
pub(crate) mod tests {
    use std::io::Cursor;

    use super::*;

    pub(crate) fn ncch(program_id: u64) -> Vec<u8> {
        let mut h = vec![0u8; 0x200];
        h[0x100..0x104].copy_from_slice(b"NCCH");
        h[0x108..0x110].copy_from_slice(&program_id.to_le_bytes());
        h[0x110..0x114].copy_from_slice(b"01\x02\x00");
        h[0x118..0x120].copy_from_slice(&program_id.to_le_bytes());
        h[0x150..0x15A].copy_from_slice(b"CTR-P-AJRE");
        h[0x188 + 4] = 0x01;
        h[0x188 + 5] = 0x03;
        h
    }

    pub(crate) fn cci(program_id: u64) -> Vec<u8> {
        let mut image = vec![0u8; 0x4000];
        image[0x100..0x104].copy_from_slice(b"NCSD");
        image[0x104..0x108].copy_from_slice(&0x40u32.to_le_bytes());
        image[0x108..0x110].copy_from_slice(&0x1234u64.to_le_bytes());
        image[0x120..0x128].copy_from_slice(&[0x20, 0, 0, 0, 0x20, 0, 0, 0]);
        image.extend(ncch(program_id));
        image
    }

    #[test]
    fn reads_a_cartridge_image() {
        let h = read(&mut Cursor::new(cci(0x0004_0000_0003_3500))).unwrap();
        assert_eq!(h.container, Container::Cci);
        assert!(!h.compressed);
        assert_eq!(
            h.ncsd,
            Some(Ncsd {
                media_id: 0x1234,
                image_size: 0x8000,
                partitions: 1
            })
        );
        assert_eq!(h.program_id, 0x0004_0000_0003_3500);
        assert_eq!(h.maker_code, "01");
        assert_eq!(h.ncch_version, 2);
        assert_eq!(h.product_code, "CTR-P-AJRE");
        assert_eq!(h.region, Some(Region::USA));
        assert!(!h.new_3ds_only);
    }

    #[test]
    fn reads_a_bare_ncch() {
        let h = read(&mut Cursor::new(ncch(0x0004_0000_0005_5E00))).unwrap();
        assert_eq!(h.container, Container::Cxi);
        assert_eq!(h.program_id, 0x0004_0000_0005_5E00);
    }

    #[test]
    fn reads_a_decrypted_cia() {
        let mut cia = vec![0u8; 0x2040];
        cia[0..4].copy_from_slice(&0x2020u32.to_le_bytes());
        cia[0x08..0x0C].copy_from_slice(&0x10u32.to_le_bytes());
        cia[0x0C..0x10].copy_from_slice(&0x40u32.to_le_bytes());
        cia[0x10..0x14].copy_from_slice(&0x41u32.to_le_bytes());
        // Header 0x2040, certificates 0x40, ticket 0x40, TMD 0x80.
        cia.resize(0x2040 + 0x40 + 0x40 + 0x80, 0);
        cia.extend(ncch(0x0004_0000_0003_3500));
        let h = read(&mut Cursor::new(cia)).unwrap();
        assert_eq!(h.container, Container::Cia);
        assert_eq!(h.program_id, 0x0004_0000_0003_3500);
    }

    #[test]
    fn refuses_other_files() {
        assert!(read(&mut Cursor::new(vec![0u8; 0x400])).is_err());
    }
}
