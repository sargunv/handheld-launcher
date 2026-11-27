// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of
// the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Ported from argosy-sigil (https://github.com/rommapp/argosy-sigil, MPL-2.0): src/switch_nsp.c
// and src/switch_xci.c.

//! The Nintendo Switch's title ids, read from a game's containers: an NSP
//! (or NSZ), a PFS0 partition of NCAs and tickets, or an XCI (or XCZ), a game
//! card image whose secure HFS0 partition holds them. An NCA's header, which
//! the console's header key encrypts, names its program; without the key, the
//! tickets name their titles.
//!
//! - <https://switchbrew.org/wiki/NCA>
//! - <https://switchbrew.org/wiki/XCI>
//! - <https://switchbrew.org/wiki/Ticket>

use std::io::{Read, Seek};

use aes::Aes128;
use aes::cipher::{Array, KeyInit};
use xts_mode::Xts128;

use crate::bytes::{array_at, be64, le32, le64, prefix_at, vec_at};
use crate::{Error, Result};

/// The game a Switch image holds.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct Header {
    /// The game's application id, which its saves are filed under. An
    /// update's or add-on's is the id of the game it belongs to.
    pub title_id: u64,
}

/// The largest file table of a partition it reads. Real ones list a few
/// dozen files.
const TABLE_LIMIT: u64 = 1 << 20;

/// An NCA's content type for a program, as opposed to an add-on's data.
const NCA_PROGRAM: u8 = 0;

/// Reads the game of the NSP or XCI `reader` reads. With `header_key`, the
/// console's `header_key` (see [`header_key`]), the NCAs' headers name it;
/// without it, or when it opens none of them, the tickets do. It fails unless
/// they name one game.
pub fn read<R: Read + Seek>(mut reader: R, header_key: Option<&[u8; 32]>) -> Result<Header> {
    let reader = &mut reader;
    let files = files(reader)?;
    let mut ids = Vec::new();
    if let Some(key) = header_key {
        let xts = xts(key);
        let ncas = files
            .iter()
            .filter(|f| f.name.ends_with(".nca") || f.name.ends_with(".ncz"));
        for file in ncas {
            ids.extend(nca_title(reader, file.offset, &xts)?);
        }
    }
    if ids.is_empty() {
        for file in files.iter().filter(|f| f.name.ends_with(".tik")) {
            ids.extend(ticket_title(reader, file.offset)?);
        }
    }
    let mut games = ids.into_iter().map(application_of);
    let title_id = games
        .next()
        .filter(|&first| games.all(|id| id == first))
        .ok_or_else(|| Error::NotFound("title id".into()))?;
    Ok(Header { title_id })
}

/// The `header_key` in the text of a `prod.keys` file, as Switch emulators
/// keep the console's keys.
pub fn header_key(prod_keys: &str) -> Option<[u8; 32]> {
    let hex = prod_keys.lines().find_map(|line| {
        let (name, value) = line.split_once('=')?;
        (name.trim() == "header_key").then(|| value.trim())
    })?;
    if hex.len() != 64 {
        return None;
    }
    let mut key = [0; 32];
    for (i, byte) in key.iter_mut().enumerate() {
        *byte = u8::from_str_radix(hex.get(2 * i..2 * i + 2)?, 16).ok()?;
    }
    Some(key)
}

/// AES-128-XTS with the two halves of `key`, as the Switch encrypts NCA
/// headers.
fn xts(key: &[u8; 32]) -> Xts128<Aes128> {
    let (halves, _) = key.as_chunks::<16>();
    Xts128::new(
        Aes128::new(&Array(halves[0])),
        Aes128::new(&Array(halves[1])),
    )
}

struct Entry {
    name: String,
    offset: u64,
}

/// The files of an NSP's PFS0 partition, or of an XCI's secure partition.
fn files<R: Read + Seek>(reader: &mut R) -> Result<Vec<Entry>> {
    if prefix_at(reader, 0, 4)? == b"PFS0" {
        return partition(reader, 0, 0x18);
    }
    if prefix_at(reader, 0x100, 4)? == b"HEAD" {
        let root = le64(&array_at::<8, _>(reader, 0x130)?, 0);
        let secure = hfs0(reader, root)?
            .into_iter()
            .find(|f| f.name == "secure")
            .ok_or_else(|| Error::NotFound("secure partition".into()))?;
        return hfs0(reader, secure.offset);
    }
    Err(Error::NotRecognized("NSP or XCI"))
}

/// The files of the HFS0 partition, an XCI's, at `base`.
fn hfs0<R: Read + Seek>(reader: &mut R, base: u64) -> Result<Vec<Entry>> {
    if prefix_at(reader, base, 4)? != b"HFS0" {
        return Err(Error::NotRecognized("HFS0"));
    }
    partition(reader, base, 0x40)
}

/// The files of the PFS0 or HFS0 partition at `base`, whose file entries are
/// `entry_size` bytes: each starts with its file's offset, and names it at 16.
fn partition<R: Read + Seek>(reader: &mut R, base: u64, entry_size: usize) -> Result<Vec<Entry>> {
    let head: [u8; 16] = array_at(reader, base)?;
    let count = le32(&head, 4) as usize;
    let size = (count * entry_size) as u64 + u64::from(le32(&head, 8));
    if size > TABLE_LIMIT {
        return Err(Error::NotRecognized("PFS0 or HFS0"));
    }
    let table = vec_at(reader, base + 16, size as usize)?;
    let (entries, names) = table.split_at(count * entry_size);
    let data = base + 16 + size;
    entries
        .chunks(entry_size)
        .map(|entry| {
            let name = names
                .get(le32(entry, 16) as usize..)
                .ok_or(Error::NotRecognized("PFS0 or HFS0"))?;
            let end = name.iter().position(|&c| c == 0).unwrap_or(name.len());
            Ok(Entry {
                name: String::from_utf8_lossy(&name[..end]).into_owned(),
                offset: data.saturating_add(le64(entry, 0)),
            })
        })
        .collect()
}

/// The title id a ticket's rights id starts with, or none for a ticket it
/// can't read.
fn ticket_title<R: Read + Seek>(reader: &mut R, offset: u64) -> Result<Option<u64>> {
    let signature = match le32(&array_at::<4, _>(reader, offset)?, 0) {
        0x10000 | 0x10003 => 0x200 + 0x3C,
        0x10001 | 0x10004 => 0x100 + 0x3C,
        0x10002 | 0x10005 => 0x3C + 0x40,
        _ => return Ok(None),
    };
    let rights = be64(
        &array_at::<8, _>(reader, offset + 4 + signature + 0x160)?,
        0,
    );
    Ok(Some(rights).filter(|&id| id != 0))
}

/// The program id in the header of the NCA at `offset`, or none when `xts`
/// doesn't open it or it isn't a game's. A game made of several programs
/// numbers them in the id's last digit, as yuzu's
/// `GetBaseTitleIDWithProgramIndex` does, so a program's id is taken back to
/// its first program's. An add-on's id can end the same way, so only a
/// program's is.
fn nca_title<R: Read + Seek>(
    reader: &mut R,
    offset: u64,
    xts: &Xts128<Aes128>,
) -> Result<Option<u64>> {
    // The magic, content type and program id are in the header's second
    // sector, whose number is the tweak.
    let mut sector: [u8; 0x200] = array_at(reader, offset + 0x200)?;
    xts.decrypt_sector(&mut sector, Array(1u128.to_be_bytes()));
    let id = le64(&sector, 0x10);
    // A game's program id starts with 01.
    if &sector[..3] != b"NCA" || id >> 56 != 1 {
        return Ok(None);
    }
    let is_program = sector[5] == NCA_PROGRAM;
    Ok(Some(if is_program && (1..=0xF).contains(&(id & 0xFFF)) {
        id & !0xF
    } else {
        id
    }))
}

/// The game an update or add-on belongs to, whose id its saves are filed
/// under. An update's id ends in `800`, its game's in `000`; an add-on's game
/// is the block below its own.
fn application_of(id: u64) -> u64 {
    match id & 0xFFF {
        0 => id,
        0x800 => id & !0xFFF,
        _ => (id & !0xFFF) - 0x1000,
    }
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;

    fn key() -> [u8; 32] {
        std::array::from_fn(|i| (i * 7 + 3) as u8)
    }

    fn read(image: Vec<u8>, header_key: Option<&[u8; 32]>) -> Option<u64> {
        super::read(Cursor::new(image), header_key)
            .ok()
            .map(|h| h.title_id)
    }

    /// A partition holding `files`, with `entry_size`-byte entries.
    fn partition(magic: &[u8; 4], entry_size: usize, files: &[(&str, Vec<u8>)]) -> Vec<u8> {
        let mut names = Vec::new();
        let mut entries = Vec::new();
        let mut offset = 0u64;
        for (name, bytes) in files {
            let mut entry = vec![0; entry_size];
            entry[..8].copy_from_slice(&offset.to_le_bytes());
            entry[8..16].copy_from_slice(&(bytes.len() as u64).to_le_bytes());
            entry[16..20].copy_from_slice(&(names.len() as u32).to_le_bytes());
            entries.extend(entry);
            names.extend(name.bytes().chain([0]));
            offset += bytes.len() as u64;
        }
        names.resize(names.len().next_multiple_of(16), 0);
        let mut image = magic.to_vec();
        image.extend((files.len() as u32).to_le_bytes());
        image.extend((names.len() as u32).to_le_bytes());
        image.extend([0; 4]);
        image.extend(entries);
        image.extend(names);
        for (_, bytes) in files {
            image.extend(bytes);
        }
        image
    }

    fn pfs0(files: &[(&str, Vec<u8>)]) -> Vec<u8> {
        partition(b"PFS0", 0x18, files)
    }

    fn hfs0(files: &[(&str, Vec<u8>)]) -> Vec<u8> {
        partition(b"HFS0", 0x40, files)
    }

    fn ticket(title_id: u64) -> Vec<u8> {
        let mut ticket = vec![0; 0x2C0];
        ticket[..4].copy_from_slice(&0x10004u32.to_le_bytes());
        ticket[0x2A0..0x2A8].copy_from_slice(&title_id.to_be_bytes());
        ticket
    }

    /// An NCA whose header, encrypted with [`key`], names `id`.
    fn nca(id: u64, content_type: u8) -> Vec<u8> {
        let mut sector = [0; 0x200];
        sector[..4].copy_from_slice(b"NCA3");
        sector[5] = content_type;
        sector[0x10..0x18].copy_from_slice(&id.to_le_bytes());
        xts(&key()).encrypt_sector(&mut sector, Array(1u128.to_be_bytes()));
        [vec![0; 0x200], sector.to_vec()].concat()
    }

    #[test]
    fn reads_an_nsps_ticket() {
        let nsp = pfs0(&[(
            "0100abcd12345000000000000000000a.tik",
            ticket(0x0100_ABCD_1234_5000),
        )]);
        assert_eq!(read(nsp, None), Some(0x0100_ABCD_1234_5000));
    }

    #[test]
    fn reads_an_update_or_add_on_as_its_game() {
        let update = pfs0(&[("u.tik", ticket(0x0100_ABCD_1234_5800))]);
        assert_eq!(read(update, None), Some(0x0100_ABCD_1234_5000));
        let add_on = pfs0(&[("a.tik", ticket(0x0100_1520_0002_3001))]);
        assert_eq!(read(add_on, None), Some(0x0100_1520_0002_2000));
        let two_games = pfs0(&[
            ("a.tik", ticket(0x0100_ABCD_1234_5000)),
            ("b.tik", ticket(0x0100_0000_0001_0000)),
        ]);
        assert_eq!(read(two_games, None), None);
    }

    #[test]
    fn reads_an_ncas_header_with_the_keys() {
        let hex: String = key().iter().map(|b| format!("{b:02x}")).collect();
        let prod_keys = format!("master_key_00 = 00\nheader_key = {hex}\n");
        assert_eq!(header_key(&prod_keys), Some(key()));
        let nsp = pfs0(&[("0123.nca", nca(0x0100_ABCD_1234_5000, NCA_PROGRAM))]);
        assert_eq!(read(nsp.clone(), Some(&key())), Some(0x0100_ABCD_1234_5000));
        assert_eq!(
            read(nsp, None),
            None,
            "Without keys or a ticket there's no id"
        );
    }

    #[test]
    fn reads_a_games_second_program_as_its_first_but_an_add_on_as_its_game() {
        // A game of two programs: the second's id ends in 1.
        let game = pfs0(&[
            ("a.nca", nca(0x0100_4990_0F54_6000, NCA_PROGRAM)),
            ("b.nca", nca(0x0100_4990_0F54_6001, NCA_PROGRAM)),
        ]);
        assert_eq!(read(game, Some(&key())), Some(0x0100_4990_0F54_6000));
        // An add-on's data with the same id belongs to the game a block below.
        let add_on = pfs0(&[("c.nca", nca(0x0100_4990_0F54_6001, 4))]);
        assert_eq!(read(add_on, Some(&key())), Some(0x0100_4990_0F54_5000));
    }

    #[test]
    fn reads_an_xcis_secure_partition() {
        // A game card carries a ticket only for an update bundled with the game.
        let root = hfs0(&[
            ("normal", Vec::new()),
            ("secure", hfs0(&[("u.tik", ticket(0x0100_ABCD_1234_5800))])),
        ]);
        let mut xci = vec![0; 0x200];
        xci[0x100..0x104].copy_from_slice(b"HEAD");
        xci[0x130..0x138].copy_from_slice(&0x200u64.to_le_bytes());
        xci.extend(root);
        assert_eq!(read(xci, None), Some(0x0100_ABCD_1234_5000));
    }
}
