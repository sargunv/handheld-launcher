//! CHD disc images: a DVD's 2048-byte sectors as they are, and a CD's tracks
//! from its raw 2448-byte frames, each a 2352-byte sector and its subcode.
//!
//! - <https://github.com/mamedev/mame/blob/master/src/lib/util/chd.h>
//! - <https://github.com/mamedev/mame/blob/master/src/lib/util/cdrom.cpp>

use std::io::{self, Read, Seek};

use chd::Chd;
use chd::metadata::MetadataTag;

use super::{SECTOR, Sectors, raw_user_data};
use crate::{Error, Result};

/// A CD's frame in a CHD: a raw sector and its subcode.
const CD_FRAME: u64 = 2448;
/// A CHD pads each CD track to a multiple of this many frames.
const TRACK_PADDING: u64 = 4;

/// What a CD track holds, by its metadata's `TYPE`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum TrackType {
    Audio,
    /// Data: `MODE1`, `MODE2_RAW` and so on.
    Data,
}

/// A CD track, as a CHD's metadata lists it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Track {
    pub number: u32,
    pub kind: TrackType,
    /// Its type, such as `MODE1_RAW` or `AUDIO`.
    pub type_name: String,
    /// Its frames of data, after any pregap stored with it.
    pub frames: u64,
    /// The frame in the CHD where its data starts.
    pub start: u64,
}

/// The tracks a CHD's metadata lists.
pub(super) fn tracks<R: Read + Seek>(reader: R) -> Result<Vec<Track>> {
    let mut chd = Chd::open(reader, None).map_err(chd_error)?;
    read_tracks(&mut chd)
}

fn read_tracks<R: Read + Seek>(chd: &mut Chd<R>) -> Result<Vec<Track>> {
    let refs: Vec<_> = chd
        .metadata_refs()
        .collect::<std::result::Result<_, _>>()
        .map_err(chd_error)?;
    let mut tracks = Vec::new();
    let mut frame = 0u64;
    for meta in refs {
        // CHTR, CHT2 and CHGD hold the same text fields.
        if !matches!(&meta.metatag().to_be_bytes(), b"CHTR" | b"CHT2" | b"CHGD") {
            continue;
        }
        let value = meta.read(chd.inner()).map_err(chd_error)?.value;
        let text = String::from_utf8_lossy(&value);
        let field = |key: &str| {
            text.split_whitespace()
                .find_map(|kv| kv.strip_prefix(key)?.strip_prefix(':'))
                .map(|v| v.trim_end_matches('\0').to_owned())
        };
        let number = field("TRACK").and_then(|v| v.parse().ok()).unwrap_or(0);
        let type_name = field("TYPE").unwrap_or_default();
        let frames: u64 = field("FRAMES").and_then(|v| v.parse().ok()).unwrap_or(0);
        let pregap: u64 = field("PREGAP").and_then(|v| v.parse().ok()).unwrap_or(0);
        // A pregap whose type starts with V is stored with the track.
        let stored_pregap = if field("PGTYPE").is_some_and(|t| t.starts_with('V')) {
            pregap
        } else {
            0
        };
        tracks.push(Track {
            number,
            kind: if type_name == "AUDIO" {
                TrackType::Audio
            } else {
                TrackType::Data
            },
            type_name,
            frames: frames.saturating_sub(stored_pregap),
            start: frame + stored_pregap,
        });
        frame += frames.div_ceil(TRACK_PADDING) * TRACK_PADDING;
    }
    Ok(tracks)
}

/// A CHD error: chd-rs keeps no I/O error, so every error says the file
/// isn't a CHD it can read.
fn chd_error(_: chd::Error) -> Error {
    Error::NotRecognized("CHD")
}

/// The data sectors of a CHD: a DVD's, or a CD track's.
pub(super) struct ChdSectors<R: Read + Seek> {
    chd: Chd<R>,
    unit: u64,
    hunk_size: u64,
    /// The frame the data starts at.
    start: u64,
    count: u64,
    cached: Option<(u32, Vec<u8>)>,
    compressed: Vec<u8>,
}

impl<R: Read + Seek> ChdSectors<R> {
    /// Opens a CHD on its track `number`, or its first data track. A DVD, or
    /// a CD whose metadata lists no tracks, is one track.
    pub(super) fn open(reader: R, number: Option<u32>) -> Result<Self> {
        let mut chd = Chd::open(reader, None).map_err(chd_error)?;
        let header = chd.header();
        let unit = u64::from(header.unit_bytes());
        let hunk_size = u64::from(header.hunk_size());
        let logical = header.logical_bytes();
        if (unit != SECTOR && unit != CD_FRAME) || hunk_size == 0 {
            return Err(Error::Unsupported("a CHD that isn't a CD or DVD"));
        }
        let tracks = if unit == CD_FRAME {
            read_tracks(&mut chd)?
        } else {
            Vec::new()
        };
        let all = logical / unit;
        let (start, count) = if tracks.is_empty() {
            if number.is_some_and(|n| n != 1) {
                return Err(Error::NotFound(format!("track {}", number.unwrap())));
            }
            (0, all)
        } else {
            let track = tracks
                .iter()
                .find(|t| match number {
                    Some(n) => t.number == n,
                    None => t.kind == TrackType::Data,
                })
                .ok_or_else(|| Error::NotFound("a data track".to_owned()))?;
            let end = (track.start + track.frames).min(all);
            (track.start, end.saturating_sub(track.start))
        };
        Ok(Self {
            chd,
            unit,
            hunk_size,
            start,
            count,
            cached: None,
            compressed: Vec::new(),
        })
    }

    /// Fills `out` from byte `at` of the image's logical bytes.
    fn read_logical(&mut self, mut at: u64, out: &mut [u8]) -> io::Result<()> {
        let mut done = 0;
        while done < out.len() {
            let hunk = u32::try_from(at / self.hunk_size)
                .map_err(|_| io::Error::from(io::ErrorKind::InvalidInput))?;
            if self.cached.as_ref().map(|c| c.0) != Some(hunk) {
                let mut buffer = self.chd.get_hunksized_buffer();
                self.chd
                    .hunk(hunk)
                    .and_then(|mut h| h.read_hunk_in(&mut self.compressed, &mut buffer))
                    .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
                self.cached = Some((hunk, buffer));
            }
            let data = &self.cached.as_ref().unwrap().1;
            let skip = (at % self.hunk_size) as usize;
            let take = (out.len() - done).min(data.len() - skip);
            out[done..done + take].copy_from_slice(&data[skip..skip + take]);
            done += take;
            at += take as u64;
        }
        Ok(())
    }
}

impl<R: Read + Seek> Sectors for ChdSectors<R> {
    fn count(&self) -> u64 {
        self.count
    }

    fn read(&mut self, n: u64, out: &mut [u8; SECTOR as usize]) -> io::Result<()> {
        let at = (self.start + n) * self.unit;
        if self.unit == SECTOR {
            return self.read_logical(at, out);
        }
        let mut frame = [0u8; CD_FRAME as usize];
        self.read_logical(at, &mut frame)?;
        // A cooked track keeps its data at the start of the frame.
        let data = if frame[..12] == super::CD_SYNC {
            raw_user_data(&frame)?
        } else {
            &frame[..SECTOR as usize]
        };
        out.copy_from_slice(data);
        Ok(())
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use std::io::{Cursor, Read, Seek, SeekFrom};

    use super::super::{CD_SYNC, Disc, ImageFormat, SECTOR};
    use super::{CD_FRAME, Track, TrackType};

    /// An uncompressed CHD v5 of `units`, each `unit` bytes, two to a hunk,
    /// with `metadata` entries.
    pub(crate) fn chd(unit: u64, units: &[Vec<u8>], metadata: &[(&[u8; 4], &str)]) -> Vec<u8> {
        let hunk = unit * 2;
        let hunks = units.len().div_ceil(2) as u64;
        let map_end = 124 + 4 * hunks;
        // The metadata follows the map, then the hunks start at the first
        // hunk-aligned offset after it.
        let mut meta = Vec::new();
        let mut meta_offsets = Vec::new();
        for (tag, text) in metadata {
            let offset = map_end + meta.len() as u64;
            meta_offsets.push(offset);
            let mut value = text.as_bytes().to_vec();
            value.push(0);
            meta.extend_from_slice(*tag);
            meta.extend_from_slice(&(value.len() as u32).to_be_bytes());
            meta.extend_from_slice(&[0; 8]);
            meta.extend(value);
        }
        // Chain each entry to the next.
        for (i, offset) in meta_offsets.iter().enumerate() {
            let next = meta_offsets.get(i + 1).copied().unwrap_or(0);
            let at = (*offset - map_end) as usize + 8;
            meta[at..at + 8].copy_from_slice(&next.to_be_bytes());
        }
        let first = (map_end + meta.len() as u64).div_ceil(hunk);
        let mut file = Vec::new();
        file.extend_from_slice(b"MComprHD");
        file.extend_from_slice(&124u32.to_be_bytes());
        file.extend_from_slice(&5u32.to_be_bytes());
        file.extend_from_slice(&[0; 16]);
        file.extend_from_slice(&(hunks * hunk).to_be_bytes());
        file.extend_from_slice(&124u64.to_be_bytes());
        file.extend_from_slice(&meta_offsets.first().copied().unwrap_or(0).to_be_bytes());
        file.extend_from_slice(&(hunk as u32).to_be_bytes());
        file.extend_from_slice(&(unit as u32).to_be_bytes());
        file.extend_from_slice(&[0; 60]);
        for i in 0..hunks {
            file.extend_from_slice(&((first + i) as u32).to_be_bytes());
        }
        file.extend(meta);
        file.resize((first * hunk) as usize, 0);
        for data in units {
            file.extend_from_slice(data);
        }
        file.resize((first * hunk + hunks * hunk) as usize, 0);
        file
    }

    fn frame(mode: u8, fill: u8) -> Vec<u8> {
        let mut f = vec![0u8; CD_FRAME as usize];
        f[..12].copy_from_slice(&CD_SYNC);
        f[15] = mode;
        let data = if mode == 1 { 16 } else { 24 };
        f[data..data + 2048].fill(fill);
        f
    }

    #[test]
    fn reads_a_dvd_across_sectors_and_hunks() {
        let sectors: Vec<Vec<u8>> = (0..5u8).map(|i| vec![i; SECTOR as usize]).collect();
        let mut disc = Disc::open(Cursor::new(chd(SECTOR, &sectors, &[]))).unwrap();
        assert_eq!(disc.format(), ImageFormat::Chd);
        assert_eq!(disc.len(), 6 * SECTOR, "The hunks pad the disc to six");
        let mut read = vec![0u8; 4200];
        disc.seek(SeekFrom::Start(2040)).unwrap();
        disc.read_exact(&mut read).unwrap();
        let mut want = vec![0u8; 8];
        want.extend(vec![1u8; 2048]);
        want.extend(vec![2u8; 2048]);
        want.extend(vec![3u8; 96]);
        assert_eq!(read, want);
    }

    #[test]
    fn reads_the_user_data_of_raw_cd_frames() {
        let image = chd(CD_FRAME, &[frame(2, 7), frame(1, 9)], &[]);
        let mut disc = Disc::open(Cursor::new(image)).unwrap();
        let mut read = [0u8; 4];
        disc.seek(SeekFrom::Start(2046)).unwrap();
        disc.read_exact(&mut read).unwrap();
        assert_eq!(read, [7, 7, 9, 9]);
    }

    #[test]
    fn opens_a_track_by_its_metadata() {
        // A GD-ROM: a data track of 3 frames, padded to 4, an audio track of
        // 4, then the high-density data track.
        let frames: Vec<Vec<u8>> = [1, 1, 1, 0, 2, 2, 2, 2, 3, 3]
            .iter()
            .map(|&fill| frame(1, fill))
            .collect();
        let metadata: [(&[u8; 4], &str); 3] = [
            (
                b"CHGD",
                "TRACK:1 TYPE:MODE1_RAW SUBTYPE:NONE FRAMES:3 PAD:0 PREGAP:0 PGTYPE:MODE1 PGSUB:RW POSTGAP:0",
            ),
            (
                b"CHGD",
                "TRACK:2 TYPE:AUDIO SUBTYPE:NONE FRAMES:4 PAD:0 PREGAP:0 PGTYPE:MODE1 PGSUB:RW POSTGAP:0",
            ),
            (
                b"CHGD",
                "TRACK:3 TYPE:MODE1_RAW SUBTYPE:NONE FRAMES:2 PAD:0 PREGAP:0 PGTYPE:MODE1 PGSUB:RW POSTGAP:0",
            ),
        ];
        let image = chd(CD_FRAME, &frames, &metadata);
        let tracks = Disc::chd_tracks(Cursor::new(&image)).unwrap();
        assert_eq!(
            tracks
                .iter()
                .map(|t| (t.number, t.kind, t.start))
                .collect::<Vec<_>>(),
            [
                (1, TrackType::Data, 0),
                (2, TrackType::Audio, 4),
                (3, TrackType::Data, 8)
            ]
        );
        let _: &Track = &tracks[0];
        let mut first = Disc::open(Cursor::new(&image)).unwrap();
        assert_eq!(first.len(), 3 * SECTOR);
        let mut byte = [0u8];
        first.read_exact(&mut byte).unwrap();
        assert_eq!(byte, [1]);
        let mut third = Disc::open_chd(Cursor::new(&image), Some(3)).unwrap();
        assert_eq!(third.len(), 2 * SECTOR);
        third.read_exact(&mut byte).unwrap();
        assert_eq!(byte, [3]);
    }

    #[test]
    fn refuses_what_isnt_a_chd() {
        let mut image = b"MComprHD".to_vec();
        image.resize(512, 0);
        assert!(Disc::open(Cursor::new(image)).is_err());
    }
}
