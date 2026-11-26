//! Finding files in an ISO 9660 file system, as disc games keep theirs, such
//! as a PlayStation disc's `SYSTEM.CNF`.
//!
//! - <https://wiki.osdev.org/ISO_9660>

use std::io::{Read, Seek};

use super::SECTOR;
use crate::Result;
use crate::bytes::{le32, prefix_at, read_at};

/// The most of a directory it reads.
const MAX_DIRECTORY: usize = 1 << 20;

/// Where the file at `path` is in the file system of `disc`'s 2048-byte data
/// sectors, as its offset and size in bytes. Path components are separated by
/// `/` and matched ignoring case and the `;1` version suffix. None when the
/// disc has no ISO 9660 file system or no such file.
pub fn find<R: Read + Seek + ?Sized>(disc: &mut R, path: &str) -> Result<Option<(u64, u64)>> {
    let mut pvd = [0u8; 190];
    if read_at(disc, 16 * SECTOR, &mut pvd).is_err() || &pvd[1..6] != b"CD001" {
        return Ok(None);
    }
    let mut entry = record(&pvd[156..]);
    for name in path.split('/') {
        if !entry.directory {
            return Ok(None);
        }
        let listing = prefix_at(
            disc,
            entry.sector * SECTOR,
            (entry.size as usize).min(MAX_DIRECTORY),
        )?;
        match entries(&listing).find(|(found, _)| found.eq_ignore_ascii_case(name)) {
            Some((_, found)) => entry = found,
            None => return Ok(None),
        }
    }
    Ok((!entry.directory).then_some((entry.sector * SECTOR, entry.size)))
}

#[derive(Clone, Copy)]
struct Record {
    sector: u64,
    size: u64,
    directory: bool,
}

fn record(r: &[u8]) -> Record {
    Record {
        sector: u64::from(le32(r, 2)),
        size: u64::from(le32(r, 10)),
        directory: r[25] & 0x02 != 0,
    }
}

/// A directory's entries, by name without version.
fn entries(listing: &[u8]) -> impl Iterator<Item = (String, Record)> + '_ {
    let mut at = 0;
    std::iter::from_fn(move || {
        loop {
            if at >= listing.len() {
                return None;
            }
            let length = listing[at] as usize;
            if length == 0 {
                // Records don't cross sectors; the rest of this one is
                // padding.
                at = (at / SECTOR as usize + 1) * SECTOR as usize;
                continue;
            }
            let entry = listing.get(at..at + length)?;
            at += length;
            if entry.len() < 34 {
                return None;
            }
            let name_length = entry[32] as usize;
            let name = entry.get(33..33 + name_length)?;
            let name = String::from_utf8_lossy(name);
            let name = name.split(';').next().unwrap_or_default().to_owned();
            return Some((name, record(entry)));
        }
    })
}

#[cfg(test)]
pub(crate) mod tests {
    use std::io::Cursor;

    use super::*;

    /// A directory record.
    fn entry(name: &[u8], sector: u32, size: u32, directory: bool) -> Vec<u8> {
        let mut r = vec![0u8; 33];
        r[0] = (33 + name.len() + (name.len() + 1) % 2) as u8;
        r[2..6].copy_from_slice(&sector.to_le_bytes());
        r[10..14].copy_from_slice(&size.to_le_bytes());
        r[25] = if directory { 2 } else { 0 };
        r[32] = name.len() as u8;
        r.extend_from_slice(name);
        r.resize(r[0] as usize, 0);
        r
    }

    /// A cooked ISO 9660 image holding `files` at their paths, in at most one
    /// directory below the root, with the system area's first sector holding
    /// `system`.
    pub(crate) fn image(system: &[u8], files: &[(&str, &[u8])]) -> Vec<u8> {
        let mut image = vec![0u8; 20 * SECTOR as usize];
        image[..system.len()].copy_from_slice(system);
        let mut next = 20u32;
        let mut root = vec![
            entry(b"\0", 18, SECTOR as u32, true),
            entry(b"\x01", 18, SECTOR as u32, true),
        ];
        let mut sub: Option<(String, Vec<Vec<u8>>)> = None;
        let mut data = Vec::new();
        for (path, contents) in files {
            let sector = next;
            next += (contents.len() as u32).div_ceil(SECTOR as u32).max(1);
            let mut bytes = contents.to_vec();
            bytes.resize(
                bytes.len().div_ceil(SECTOR as usize).max(1) * SECTOR as usize,
                0,
            );
            data.extend(bytes);
            match path.split_once('/') {
                Some((dir, name)) => {
                    let name = format!("{name};1");
                    sub.get_or_insert_with(|| (dir.to_owned(), Vec::new()))
                        .1
                        .push(entry(name.as_bytes(), sector, contents.len() as u32, false));
                }
                None => {
                    let name = format!("{path};1");
                    root.push(entry(name.as_bytes(), sector, contents.len() as u32, false));
                }
            }
        }
        if let Some((dir, entries)) = sub {
            root.push(entry(dir.as_bytes(), 19, SECTOR as u32, true));
            let listing: Vec<u8> = entries.concat();
            image[19 * SECTOR as usize..][..listing.len()].copy_from_slice(&listing);
        }
        let pvd = 16 * SECTOR as usize;
        image[pvd] = 1;
        image[pvd + 1..pvd + 6].copy_from_slice(b"CD001");
        image[pvd + 156..pvd + 190].copy_from_slice(&root[0][..34]);
        let listing: Vec<u8> = root.concat();
        image[18 * SECTOR as usize..][..listing.len()].copy_from_slice(&listing);
        image.extend(data);
        image
    }

    #[test]
    fn finds_files() {
        let iso = image(
            b"",
            &[
                ("SYSTEM.CNF", b"BOOT2 = cdrom0:\\SLUS_201.52;1\r\n"),
                ("PSP_GAME/PARAM.SFO", b"sfo"),
            ],
        );
        let mut disc = Cursor::new(iso);
        let (offset, size) = find(&mut disc, "system.cnf").unwrap().unwrap();
        assert_eq!(size, 31);
        let mut cnf = vec![0u8; size as usize];
        read_at(&mut disc, offset, &mut cnf).unwrap();
        assert!(cnf.starts_with(b"BOOT2"));
        assert_eq!(find(&mut disc, "PSP_GAME/PARAM.SFO").unwrap().unwrap().1, 3);
        assert_eq!(find(&mut disc, "PSP_GAME").unwrap(), None, "a directory");
        assert_eq!(find(&mut disc, "OTHER.CNF").unwrap(), None);
        assert_eq!(
            find(&mut Cursor::new(vec![0u8; 100]), "SYSTEM.CNF").unwrap(),
            None
        );
    }
}
