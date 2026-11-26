#![doc = include_str!("../README.md")]
#![forbid(unsafe_code)]

mod bytes;
mod code;
pub mod disc;
pub mod dreamcast;
pub mod gamecube;
pub mod gb;
pub mod gba;
pub mod mega_drive;
pub mod n3ds;
pub mod n64;
pub mod nds;
pub mod nes;
pub mod playstation;
pub mod saturn;
pub mod sfo;
pub mod sms;
pub mod snes;
pub mod xbox;
#[cfg(feature = "zstd")]
pub mod z3ds;

use std::io::{self, Read, Seek};

use crate::disc::Disc;

/// A game platform, as the caller knows it, which says which header to read.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Platform {
    Nes,
    Snes,
    N64,
    /// The Game Boy and Game Boy Color.
    GameBoy,
    GameBoyAdvance,
    NintendoDs,
    Nintendo3ds,
    /// The GameCube and Wii.
    GameCube,
    /// The Master System and Game Gear.
    MasterSystem,
    /// The Mega Drive and 32X.
    MegaDrive,
    MegaCd,
    Saturn,
    Dreamcast,
    PlayStation,
    PlayStation2,
    Psp,
    Xbox,
}

/// A game's header, of the kind its platform has.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Header {
    Nes(nes::Header),
    Snes(snes::Header),
    N64(n64::Header),
    GameBoy(gb::Header),
    GameBoyAdvance(gba::Header),
    NintendoDs(nds::Header),
    Nintendo3ds(n3ds::Header),
    GameCube(gamecube::Header),
    MasterSystem(sms::Header),
    MegaDrive(mega_drive::Cartridge),
    MegaCd(mega_drive::Disc),
    Saturn(saturn::Header),
    Dreamcast(dreamcast::Header),
    /// A PlayStation or PlayStation 2 disc's `SYSTEM.CNF`.
    SystemCnf(playstation::SystemCnf),
    /// A PSP game's PARAM.SFO, or a PlayStation game's in a PBP.
    ParamSfo(sfo::ParamSfo),
    Xbox(xbox::Certificate),
}

/// Why a header couldn't be read.
#[derive(Debug)]
pub enum Error {
    /// The reader failed.
    Io(io::Error),
    /// What it read doesn't hold the named format.
    NotRecognized(&'static str),
    /// The image doesn't have the named file or track that holds the header.
    NotFound(String),
    /// The image is in a form this build can't read.
    Unsupported(&'static str),
}

impl Error {
    /// Maps an I/O error that ran out of input to [`Error::NotRecognized`],
    /// since a file too short for a header doesn't hold it.
    pub(crate) fn short(format: &'static str) -> impl FnOnce(io::Error) -> Self {
        move |e| {
            if e.kind() == io::ErrorKind::UnexpectedEof {
                Self::NotRecognized(format)
            } else {
                Self::Io(e)
            }
        }
    }
}

impl core::fmt::Display for Error {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            Self::Io(e) => write!(f, "couldn't read the file: {e}"),
            Self::NotRecognized(format) => write!(f, "not a {format} image"),
            Self::NotFound(what) => write!(f, "the image has no {what}"),
            Self::Unsupported(what) => write!(f, "unsupported: {what}"),
        }
    }
}

impl std::error::Error for Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(e) => Some(e),
            _ => None,
        }
    }
}

impl From<io::Error> for Error {
    fn from(e: io::Error) -> Self {
        Self::Io(e)
    }
}

pub type Result<T, E = Error> = std::result::Result<T, E>;

/// Reads the header of a game for `platform` from `reader`, which holds its
/// file: a ROM, or a disc image in any form [`disc::Disc`] opens. A
/// multi-file disc's file is its data track's, and a GD-ROM's is its third
/// track's, unless it's a CHD, which holds every track.
pub fn identify<R: Read + Seek>(platform: Platform, mut reader: R) -> Result<Header> {
    let reader = &mut reader;
    Ok(match platform {
        Platform::Nes => Header::Nes(nes::read(reader)?),
        Platform::Snes => Header::Snes(snes::read(reader)?),
        Platform::N64 => Header::N64(n64::read(reader)?),
        Platform::GameBoy => Header::GameBoy(gb::read(reader)?),
        Platform::GameBoyAdvance => Header::GameBoyAdvance(gba::read(reader)?),
        Platform::NintendoDs => Header::NintendoDs(nds::read(reader)?),
        Platform::Nintendo3ds => Header::Nintendo3ds(n3ds::read(reader)?),
        Platform::GameCube => Header::GameCube(gamecube::read(reader)?),
        Platform::MasterSystem => Header::MasterSystem(sms::read(reader)?),
        Platform::MegaDrive => Header::MegaDrive(mega_drive::read(reader)?),
        Platform::MegaCd => Header::MegaCd(mega_drive::read_disc(&mut Disc::open(reader)?)?),
        Platform::Saturn => Header::Saturn(saturn::read(&mut Disc::open(reader)?)?),
        Platform::Dreamcast => Header::Dreamcast(dreamcast_header(reader)?),
        Platform::PlayStation | Platform::PlayStation2 => {
            if let Some(sfo) = sfo::read_pbp(reader)? {
                return Ok(Header::ParamSfo(sfo));
            }
            let cnf = disc::read_file(&mut Disc::open(reader)?, "SYSTEM.CNF", 0x10000)?;
            Header::SystemCnf(playstation::parse(&cnf).ok_or(Error::NotRecognized("SYSTEM.CNF"))?)
        }
        Platform::Psp => {
            if let Some(sfo) = sfo::read_pbp(reader)? {
                return Ok(Header::ParamSfo(sfo));
            }
            let param = disc::read_file(
                &mut Disc::open(reader)?,
                "PSP_GAME/PARAM.SFO",
                sfo::MAX_SIZE,
            )?;
            Header::ParamSfo(sfo::parse(&param)?)
        }
        Platform::Xbox => Header::Xbox(xbox::read(reader)?),
    })
}

/// A Dreamcast disc's IP.BIN: in a CHD, the first data track that has one.
fn dreamcast_header<R: Read + Seek>(reader: &mut R) -> Result<dreamcast::Header> {
    #[cfg(feature = "chd")]
    if bytes::prefix_at(reader, 0, 8)? == b"MComprHD" {
        let tracks = Disc::chd_tracks(&mut *reader)?;
        for track in tracks.iter().filter(|t| t.kind == disc::TrackType::Data) {
            let mut disc = Disc::open_chd(&mut *reader, Some(track.number))?;
            if let Ok(header) = dreamcast::read(&mut disc) {
                return Ok(header);
            }
        }
    }
    dreamcast::read(&mut Disc::open(reader)?)
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;

    #[test]
    fn identifies_a_playstation_2_disc_in_each_image_format() {
        let cnf = b"BOOT2 = cdrom0:\\SLUS_201.52;1\r\nVER = 1.00\r\nVMODE = NTSC\r\n";
        let iso = disc::iso9660::tests::image(b"", &[("SYSTEM.CNF", cnf)]);
        #[cfg_attr(not(any(feature = "chd", feature = "deflate")), allow(unused_mut))]
        let mut images = vec![iso.clone(), disc::tests::raw(&iso)];
        #[cfg(feature = "deflate")]
        images.push(disc::cso_tests::cso(&iso));
        #[cfg(feature = "chd")]
        images.push(disc::chd_tests::chd(
            disc::SECTOR,
            &iso.chunks(disc::SECTOR as usize)
                .map(<[u8]>::to_vec)
                .collect::<Vec<_>>(),
            &[],
        ));
        for image in images {
            let Header::SystemCnf(cnf) =
                identify(Platform::PlayStation2, Cursor::new(image)).unwrap()
            else {
                panic!("not a SYSTEM.CNF");
            };
            assert_eq!(cnf.serial().as_deref(), Some("SLUS-20152"));
            assert_eq!(cnf.video_mode.as_deref(), Some("NTSC"));
        }
    }

    #[test]
    fn identifies_a_psp_disc() {
        let param = sfo::tests::sfo(&[("DISC_ID", "ULUS10064"), ("TITLE", "Ridge Racer")], &[]);
        let iso = disc::iso9660::tests::image(b"", &[("PSP_GAME/PARAM.SFO", &param)]);
        let Header::ParamSfo(sfo) = identify(Platform::Psp, Cursor::new(iso)).unwrap() else {
            panic!("not a PARAM.SFO");
        };
        assert_eq!(sfo.disc_id(), Some("ULUS10064"));
        assert_eq!(sfo.title(), Some("Ridge Racer"));
    }

    #[test]
    fn identifies_sega_discs_by_their_first_sector() {
        let mut saturn = vec![b' '; 0x100];
        saturn[..16].copy_from_slice(b"SEGA SEGASATURN ");
        saturn[0x20..0x28].copy_from_slice(b"MK-81022");
        let iso = disc::iso9660::tests::image(&saturn, &[]);
        let Header::Saturn(h) =
            identify(Platform::Saturn, Cursor::new(disc::tests::raw(&iso))).unwrap()
        else {
            panic!("not a Saturn header");
        };
        assert_eq!(h.product_number, "MK-81022");
        assert!(identify(Platform::Dreamcast, Cursor::new(iso)).is_err());
    }

    #[test]
    fn refuses_a_disc_without_the_file() {
        let iso = disc::iso9660::tests::image(b"", &[("OTHER.CNF", b"BOOT2 = x")]);
        assert!(matches!(
            identify(Platform::PlayStation2, Cursor::new(iso)),
            Err(Error::NotFound(_))
        ));
    }
}
