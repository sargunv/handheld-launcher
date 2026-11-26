//! CSO, the compressed ISO PSP and PS2 emulators read: an index of blocks,
//! each raw deflate or stored.
//!
//! - <https://github.com/unknownbrackets/maxcso/blob/master/README_CSO.md>

use std::io::{self, Read, Seek};

use super::{SECTOR, Sectors};
use crate::bytes::{array_at, le32, le64, vec_at};
use crate::{Error, Result};

pub(super) struct Cso<R> {
    reader: R,
    total: u64,
    block_size: u64,
    align: u8,
    cached: Option<(u64, Vec<u8>)>,
}

impl<R: Read + Seek> Cso<R> {
    pub(super) fn open(mut reader: R) -> Result<Self> {
        let h: [u8; 0x18] = array_at(&mut reader, 0).map_err(Error::short("CSO"))?;
        let block_size = u64::from(le32(&h, 0x10));
        // Version 2 compresses some blocks with LZ4, which it doesn't read.
        if &h[..4] != b"CISO" || h[0x14] > 1 || !(SECTOR..=1 << 20).contains(&block_size) {
            return Err(Error::NotRecognized("CSO"));
        }
        Ok(Self {
            reader,
            total: le64(&h, 0x08),
            block_size,
            align: h[0x15],
            cached: None,
        })
    }

    fn block(&mut self, n: u64) -> io::Result<&[u8]> {
        if self.cached.as_ref().map(|c| c.0) != Some(n) {
            let index: [u8; 8] = array_at(&mut self.reader, 0x18 + n * 4)?;
            let start = le32(&index, 0);
            let end = u64::from(le32(&index, 4) & 0x7FFF_FFFF) << self.align;
            let stored = start & 0x8000_0000 != 0;
            let from = u64::from(start & 0x7FFF_FFFF) << self.align;
            let length = end
                .checked_sub(from)
                .filter(|&l| l <= 2 * self.block_size)
                .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "a bad CSO index"))?;
            let raw = vec_at(&mut self.reader, from, length as usize)?;
            let data = if stored {
                raw
            } else {
                miniz_oxide::inflate::decompress_to_vec_with_limit(&raw, self.block_size as usize)
                    .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "a bad CSO block"))?
            };
            self.cached = Some((n, data));
        }
        Ok(&self.cached.as_ref().unwrap().1)
    }
}

impl<R: Read + Seek> Sectors for Cso<R> {
    fn count(&self) -> u64 {
        self.total / SECTOR
    }

    fn read(&mut self, n: u64, out: &mut [u8; SECTOR as usize]) -> io::Result<()> {
        let at = n * SECTOR;
        let block_size = self.block_size;
        let block = self.block(at / block_size)?;
        let skip = (at % block_size) as usize;
        let data = block
            .get(skip..skip + SECTOR as usize)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "a short CSO block"))?;
        out.copy_from_slice(data);
        Ok(())
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use std::io::{Cursor, Read, Seek, SeekFrom};

    use super::super::{Disc, ImageFormat, SECTOR};

    /// A CSO of `data` in 2048-byte blocks, alternately compressed and
    /// stored.
    pub(crate) fn cso(data: &[u8]) -> Vec<u8> {
        let blocks: Vec<&[u8]> = data.chunks(SECTOR as usize).collect();
        let mut header = Vec::new();
        header.extend_from_slice(b"CISO");
        header.extend_from_slice(&0x18u32.to_le_bytes());
        header.extend_from_slice(&(data.len() as u64).to_le_bytes());
        header.extend_from_slice(&(SECTOR as u32).to_le_bytes());
        header.extend_from_slice(&[1, 0, 0, 0]);
        let mut body = Vec::new();
        let mut index = Vec::new();
        let start = 0x18 + 4 * (blocks.len() as u32 + 1);
        for (i, block) in blocks.iter().enumerate() {
            let offset = start + body.len() as u32;
            if i % 2 == 0 {
                index.push(offset);
                body.extend(miniz_oxide::deflate::compress_to_vec(block, 6));
            } else {
                index.push(offset | 0x8000_0000);
                body.extend_from_slice(block);
            }
        }
        index.push(start + body.len() as u32);
        let mut file = header;
        for entry in index {
            file.extend_from_slice(&entry.to_le_bytes());
        }
        file.extend(body);
        file
    }

    #[test]
    fn reads_compressed_and_stored_blocks() {
        let data = super::super::tests::cooked(4);
        let mut disc = Disc::open(Cursor::new(cso(&data))).unwrap();
        assert_eq!(disc.format(), ImageFormat::Cso);
        assert_eq!(disc.len(), 4 * SECTOR);
        let mut all = Vec::new();
        disc.read_to_end(&mut all).unwrap();
        assert_eq!(all, data);
        disc.seek(SeekFrom::Start(SECTOR * 2 + 5)).unwrap();
        let mut byte = [0u8];
        disc.read_exact(&mut byte).unwrap();
        assert_eq!(byte, [2]);
    }
}
