//! Disc images, read as the 2048-byte data sectors a disc's file system and
//! its system area use: cooked images (`.iso`) as they are, raw ones
//! (`.bin`, 2352-byte sectors) through each sector's header, and with their
//! features, CHD and CSO images.
//!
//! [`Disc`] reads and seeks through the data, and [`iso9660`] finds files in
//! it.

#[cfg(feature = "chd")]
mod chd;
#[cfg(feature = "deflate")]
mod cso;
pub mod iso9660;

use std::io::{self, Read, Seek, SeekFrom};

use crate::bytes::{read_at, stream_len};
use crate::{Error, Result};

#[cfg(all(test, feature = "chd"))]
pub(crate) use self::chd::tests as chd_tests;
#[cfg(feature = "chd")]
pub use self::chd::{Track, TrackType};
#[cfg(all(test, feature = "deflate"))]
pub(crate) use self::cso::tests as cso_tests;

/// A data sector's size.
pub const SECTOR: u64 = 2048;
/// A raw CD sector's size, without subcode.
const RAW_SECTOR: u64 = 2352;
/// The sync pattern a raw CD data sector starts with.
const CD_SYNC: [u8; 12] = [
    0, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0,
];

/// What holds a disc's data.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ImageFormat {
    /// 2048-byte sectors, as an `.iso` holds them.
    Cooked,
    /// 2352-byte raw CD sectors, as a `.bin` holds them.
    Raw,
    /// MAME's compressed hunks of data.
    Chd,
    /// A compressed ISO.
    Cso,
}

impl core::fmt::Display for ImageFormat {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str(match self {
            Self::Cooked => "ISO",
            Self::Raw => "BIN",
            Self::Chd => "CHD",
            Self::Cso => "CSO",
        })
    }
}

/// A source of a disc's 2048-byte data sectors.
trait Sectors {
    /// How many data sectors there are.
    fn count(&self) -> u64;
    /// Reads data sector `n`, which is below [`Sectors::count`].
    fn read(&mut self, n: u64, out: &mut [u8; SECTOR as usize]) -> io::Result<()>;
}

/// A disc image's data sectors, read and seeked as one stream of bytes. It
/// reads one sector at a time, so it never holds more of a disc than that.
pub struct Disc<'a> {
    format: ImageFormat,
    sectors: Box<dyn Sectors + 'a>,
    position: u64,
    cached: Option<(u64, Box<[u8; SECTOR as usize]>)>,
}

impl<'a> Disc<'a> {
    /// Opens a disc image, telling its format by its contents. A CHD opens on
    /// its first data track.
    pub fn open<R: Read + Seek + 'a>(mut reader: R) -> Result<Self> {
        let mut magic = [0u8; 16];
        let read = crate::bytes::prefix_at(&mut reader, 0, 16)?;
        magic[..read.len()].copy_from_slice(&read);
        if &magic[..8] == b"MComprHD" {
            #[cfg(feature = "chd")]
            return Self::open_chd(reader, None);
            #[cfg(not(feature = "chd"))]
            return Err(Error::Unsupported("CHD needs the chd feature"));
        }
        if &magic[..4] == b"CISO" {
            #[cfg(feature = "deflate")]
            return Ok(Self::new(ImageFormat::Cso, cso::Cso::open(reader)?));
            #[cfg(not(feature = "deflate"))]
            return Err(Error::Unsupported("CSO needs the deflate feature"));
        }
        if magic[..12] == CD_SYNC {
            let raw = Raw::open(reader)?;
            return Ok(Self::new(ImageFormat::Raw, raw));
        }
        let count = stream_len(&mut reader)? / SECTOR;
        Ok(Self::new(ImageFormat::Cooked, Cooked { reader, count }))
    }

    /// Opens a CHD image on its track `number`, or its first data track.
    #[cfg(feature = "chd")]
    pub fn open_chd<R: Read + Seek + 'a>(reader: R, number: Option<u32>) -> Result<Self> {
        Ok(Self::new(
            ImageFormat::Chd,
            chd::ChdSectors::open(reader, number)?,
        ))
    }

    /// The tracks of a CHD image, as its metadata lists them: none for a DVD
    /// or a CD that lists none.
    #[cfg(feature = "chd")]
    pub fn chd_tracks<R: Read + Seek>(reader: R) -> Result<Vec<Track>> {
        chd::tracks(reader)
    }

    fn new(format: ImageFormat, sectors: impl Sectors + 'a) -> Self {
        Self {
            format,
            sectors: Box::new(sectors),
            position: 0,
            cached: None,
        }
    }

    /// What holds the disc's data.
    pub fn format(&self) -> ImageFormat {
        self.format
    }

    /// How many bytes of data the disc holds.
    pub fn len(&self) -> u64 {
        self.sectors.count() * SECTOR
    }

    /// Whether the disc holds no data.
    pub fn is_empty(&self) -> bool {
        self.sectors.count() == 0
    }
}

impl Read for Disc<'_> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let n = self.position / SECTOR;
        if n >= self.sectors.count() || buf.is_empty() {
            return Ok(0);
        }
        if self.cached.as_ref().map(|c| c.0) != Some(n) {
            let mut sector = self
                .cached
                .take()
                .map_or_else(|| Box::new([0u8; SECTOR as usize]), |c| c.1);
            self.sectors.read(n, &mut sector)?;
            self.cached = Some((n, sector));
        }
        let sector = &self.cached.as_ref().unwrap().1;
        let skip = (self.position % SECTOR) as usize;
        let take = buf.len().min(SECTOR as usize - skip);
        buf[..take].copy_from_slice(&sector[skip..skip + take]);
        self.position += take as u64;
        Ok(take)
    }
}

impl Seek for Disc<'_> {
    fn seek(&mut self, to: SeekFrom) -> io::Result<u64> {
        self.position = match to {
            SeekFrom::Start(at) => Some(at),
            SeekFrom::End(by) => self.len().checked_add_signed(by),
            SeekFrom::Current(by) => self.position.checked_add_signed(by),
        }
        .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidInput))?;
        Ok(self.position)
    }
}

/// A cooked image's sectors.
struct Cooked<R> {
    reader: R,
    count: u64,
}

impl<R: Read + Seek> Sectors for Cooked<R> {
    fn count(&self) -> u64 {
        self.count
    }

    fn read(&mut self, n: u64, out: &mut [u8; SECTOR as usize]) -> io::Result<()> {
        read_at(&mut self.reader, n * SECTOR, out)
    }
}

/// A raw image's sectors.
struct Raw<R> {
    reader: R,
    count: u64,
    sector: Box<[u8; RAW_SECTOR as usize]>,
}

impl<R: Read + Seek> Raw<R> {
    fn open(mut reader: R) -> io::Result<Self> {
        let count = stream_len(&mut reader)? / RAW_SECTOR;
        Ok(Self {
            reader,
            count,
            sector: Box::new([0; RAW_SECTOR as usize]),
        })
    }
}

impl<R: Read + Seek> Sectors for Raw<R> {
    fn count(&self) -> u64 {
        self.count
    }

    fn read(&mut self, n: u64, out: &mut [u8; SECTOR as usize]) -> io::Result<()> {
        read_at(&mut self.reader, n * RAW_SECTOR, &mut self.sector[..])?;
        out.copy_from_slice(raw_user_data(&self.sector[..])?);
        Ok(())
    }
}

/// The user data of a raw CD sector: after the header in mode 1, after the
/// subheader too in mode 2 form 1.
fn raw_user_data(sector: &[u8]) -> io::Result<&[u8]> {
    if sector[..12] != CD_SYNC {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "not a CD data sector",
        ));
    }
    let skip = match sector[15] {
        1 => 16,
        2 => 24,
        _ => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "a CD sector with no data",
            ));
        }
    };
    Ok(&sector[skip..skip + SECTOR as usize])
}

/// Opens `reader` as a disc and reads the file at `path` in its ISO 9660 file
/// system, as long as it's at most `max` bytes. Not found when the disc has no
/// such file system or no such file.
pub(crate) fn read_file<R: Read + Seek + ?Sized>(
    disc: &mut R,
    path: &str,
    max: u64,
) -> Result<Vec<u8>> {
    let (offset, size) = iso9660::find(disc, path)?.ok_or(Error::NotFound(path.to_owned()))?;
    if size > max {
        return Err(Error::NotFound(path.to_owned()));
    }
    Ok(crate::bytes::vec_at(disc, offset, size as usize)?)
}

#[cfg(test)]
pub(crate) mod tests {
    use std::io::Cursor;

    use super::*;

    /// A cooked image whose sector `i` is filled with `i`.
    pub(crate) fn cooked(sectors: u8) -> Vec<u8> {
        (0..sectors)
            .flat_map(|i| vec![i; SECTOR as usize])
            .collect()
    }

    /// `cooked` as raw sectors, alternating mode 1 and mode 2.
    pub(crate) fn raw(cooked: &[u8]) -> Vec<u8> {
        cooked
            .chunks(SECTOR as usize)
            .enumerate()
            .flat_map(|(i, data)| {
                let mut sector = vec![0u8; RAW_SECTOR as usize];
                sector[..12].copy_from_slice(&CD_SYNC);
                let mode = if i % 2 == 0 { 2 } else { 1 };
                sector[15] = mode;
                let skip = if mode == 1 { 16 } else { 24 };
                sector[skip..skip + data.len()].copy_from_slice(data);
                sector
            })
            .collect()
    }

    #[test]
    fn reads_across_sectors() {
        for (image, format) in [
            (cooked(3), ImageFormat::Cooked),
            (raw(&cooked(3)), ImageFormat::Raw),
        ] {
            let mut disc = Disc::open(Cursor::new(image)).unwrap();
            assert_eq!(disc.format(), format);
            assert_eq!(disc.len(), 3 * SECTOR);
            let mut buf = vec![0u8; 2050];
            read_at(&mut disc, 2047, &mut buf).unwrap();
            assert_eq!(buf[0], 0);
            assert!(buf[1..2049].iter().all(|&b| b == 1));
            assert_eq!(buf[2049], 2);
            let mut tail = Vec::new();
            disc.seek(SeekFrom::Start(3 * SECTOR - 4)).unwrap();
            disc.read_to_end(&mut tail).unwrap();
            assert_eq!(tail, [2; 4], "The disc ends after three sectors");
        }
    }
}
