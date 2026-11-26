//! Reads CHD disc images for the app, which reads game ids from them, such as
//! a PS2 disc's serial in its `SYSTEM.CNF`.

use std::fs::File;
use std::io::BufReader;

use chd::Chd;

/// A DVD's units are its 2048-byte sectors.
const DVD_SECTOR: u64 = 2048;
/// A CD's units are its raw 2352-byte frames, each followed by 96 bytes of
/// subcode.
const CD_FRAME: u64 = 2448;
/// The sync pattern a raw CD data frame starts with.
const CD_SYNC: [u8; 12] = [
    0, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0,
];

/// Up to `length` bytes of the disc in the CHD file at `path`, from byte
/// `offset` of its 2048-byte data sectors: a DVD's sectors as they are, and
/// the user data of a CD's first track. Fewer when the disc ends sooner. Null
/// when the file can't be read or isn't a CD or DVD image.
#[uniffi::export]
pub fn chd_disc_read(path: String, offset: u64, length: u32) -> Option<Vec<u8>> {
    let file = BufReader::new(File::open(path).ok()?);
    let mut disc = Disc::new(Chd::open(file, None).ok()?)?;
    let mut out = Vec::with_capacity(length as usize);
    let end = offset + length as u64;
    let mut at = offset;
    while at < end {
        let sector = disc.sector(at / DVD_SECTOR)?;
        let Some(sector) = sector else { break };
        let skip = (at % DVD_SECTOR) as usize;
        let take = ((end - at) as usize).min(sector.len() - skip);
        out.extend_from_slice(&sector[skip..skip + take]);
        at += take as u64;
    }
    Some(out)
}

struct Disc<F: std::io::Read + std::io::Seek> {
    chd: Chd<F>,
    unit: u64,
    hunk_size: u64,
    logical: u64,
    cached: Option<(u32, Vec<u8>)>,
    compressed: Vec<u8>,
}

impl<F: std::io::Read + std::io::Seek> Disc<F> {
    fn new(chd: Chd<F>) -> Option<Self> {
        let header = chd.header();
        let unit = header.unit_bytes() as u64;
        if unit != DVD_SECTOR && unit != CD_FRAME {
            return None;
        }
        Some(Self {
            unit,
            hunk_size: header.hunk_size() as u64,
            logical: header.logical_bytes(),
            chd,
            cached: None,
            compressed: Vec::new(),
        })
    }

    /// The data of sector `n`: Some(None) past the end of the disc, None when
    /// it can't be read.
    fn sector(&mut self, n: u64) -> Option<Option<Vec<u8>>> {
        let start = n * self.unit;
        if start + self.unit > self.logical {
            return Some(None);
        }
        let unit = self.read(start, self.unit as usize)?;
        if self.unit == DVD_SECTOR {
            return Some(Some(unit));
        }
        // A raw frame's user data follows its header: 16 bytes in mode 1, 24
        // in mode 2 (form 1). A cooked track keeps the data at the start.
        let skip = if unit[..12] == CD_SYNC {
            match unit[15] {
                1 => 16,
                2 => 24,
                _ => return None,
            }
        } else {
            0
        };
        Some(Some(unit[skip..skip + DVD_SECTOR as usize].to_vec()))
    }

    /// `length` bytes of the image's logical bytes from `start`.
    fn read(&mut self, start: u64, length: usize) -> Option<Vec<u8>> {
        let mut out = Vec::with_capacity(length);
        let mut at = start;
        while out.len() < length {
            let hunk = (at / self.hunk_size) as u32;
            if self.cached.as_ref().map(|c| c.0) != Some(hunk) {
                let mut buffer = self.chd.get_hunksized_buffer();
                self.chd
                    .hunk(hunk)
                    .ok()?
                    .read_hunk_in(&mut self.compressed, &mut buffer)
                    .ok()?;
                self.cached = Some((hunk, buffer));
            }
            let data = &self.cached.as_ref()?.1;
            let skip = (at % self.hunk_size) as usize;
            let take = (length - out.len()).min(data.len() - skip);
            out.extend_from_slice(&data[skip..skip + take]);
            at += take as u64;
        }
        Some(out)
    }
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    use super::{CD_FRAME, CD_SYNC, DVD_SECTOR};

    /// An uncompressed CHD v5 of `units`, each `unit` bytes, two to a hunk.
    fn chd(unit: u64, units: &[Vec<u8>]) -> tempfile::NamedTempFile {
        let hunk = unit * 2;
        let hunks = units.len().div_ceil(2) as u64;
        let mut header = Vec::new();
        header.extend_from_slice(b"MComprHD");
        header.extend_from_slice(&124u32.to_be_bytes());
        header.extend_from_slice(&5u32.to_be_bytes());
        header.extend_from_slice(&[0; 16]);
        header.extend_from_slice(&(unit * units.len() as u64).to_be_bytes());
        header.extend_from_slice(&124u64.to_be_bytes());
        header.extend_from_slice(&0u64.to_be_bytes());
        header.extend_from_slice(&(hunk as u32).to_be_bytes());
        header.extend_from_slice(&(unit as u32).to_be_bytes());
        header.extend_from_slice(&[0; 60]);
        // The hunks start at the first hunk-aligned offset after the map.
        let first = (124 + 4 * hunks).div_ceil(hunk);
        for i in 0..hunks {
            header.extend_from_slice(&((first + i) as u32).to_be_bytes());
        }
        header.resize((first * hunk) as usize, 0);
        for data in units {
            header.extend_from_slice(data);
        }
        header.resize((first * hunk + hunks * hunk) as usize, 0);
        let mut file = tempfile::NamedTempFile::new().unwrap();
        file.write_all(&header).unwrap();
        file
    }

    fn path(file: &tempfile::NamedTempFile) -> String {
        file.path().to_str().unwrap().to_owned()
    }

    #[test]
    fn reads_a_dvd_across_sectors_and_hunks() {
        let sectors: Vec<Vec<u8>> = (0..5u8).map(|i| vec![i; DVD_SECTOR as usize]).collect();
        let file = chd(DVD_SECTOR, &sectors);
        let read = super::chd_disc_read(path(&file), 2040, 4200).unwrap();
        let mut want = vec![0u8; 8];
        want.extend(vec![1u8; 2048]);
        want.extend(vec![2u8; 2048]);
        want.extend(vec![3u8; 96]);
        assert_eq!(read, want);
        let tail = super::chd_disc_read(path(&file), 4 * 2048 + 2000, 100).unwrap();
        assert_eq!(tail, vec![4u8; 48], "The disc ends after five sectors");
    }

    #[test]
    fn reads_the_user_data_of_raw_cd_frames() {
        let frame = |mode: u8, fill: u8| {
            let mut f = vec![0u8; CD_FRAME as usize];
            f[..12].copy_from_slice(&CD_SYNC);
            f[15] = mode;
            let data = if mode == 1 { 16 } else { 24 };
            f[data..data + 2048].fill(fill);
            f
        };
        let file = chd(CD_FRAME, &[frame(2, 7), frame(1, 9)]);
        let read = super::chd_disc_read(path(&file), 2046, 4).unwrap();
        assert_eq!(read, vec![7, 7, 9, 9]);
    }

    #[test]
    fn refuses_what_isnt_a_chd() {
        let mut file = tempfile::NamedTempFile::new().unwrap();
        file.write_all(&[0; 512]).unwrap();
        assert!(super::chd_disc_read(path(&file), 0, 16).is_none());
    }
}
