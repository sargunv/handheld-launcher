//! Azahar's compressed 3DS images (Z3DS): a header, then the image as a
//! seekable zstd stream, a run of zstd frames.
//!
//! - <https://github.com/azahar-emu/azahar/blob/master/src/common/zstd_compression.h>

use std::io::{self, Read, Seek, SeekFrom};

use ruzstd::decoding::StreamingDecoder;

use crate::bytes::{array_at, le16, le32, le64};
use crate::{Error, Result};

/// How much of an image [`Z3ds`] decompresses at most: enough for the headers
/// at its start.
const LIMIT: usize = 16 << 20;

/// The start of a Z3DS image's decompressed content, read and seeked as the
/// image itself. It decompresses as far as reads reach, up to 16 MiB.
pub struct Z3ds<R: Read> {
    decoder: Option<StreamingDecoder<R, ruzstd::decoding::FrameDecoder>>,
    decompressed: Vec<u8>,
    position: u64,
    size: u64,
}

impl<R: Read + Seek> Z3ds<R> {
    /// Opens the Z3DS image `reader` reads.
    pub fn open(mut reader: R) -> Result<Self> {
        let header: [u8; 0x20] = array_at(&mut reader, 0).map_err(Error::short("Z3DS"))?;
        if &header[..4] != b"Z3DS" || header[8] != 1 {
            return Err(Error::NotRecognized("Z3DS"));
        }
        let payload = u64::from(le16(&header, 0x0A)) + u64::from(le32(&header, 0x0C));
        reader.seek(SeekFrom::Start(payload))?;
        Ok(Self {
            decoder: StreamingDecoder::new(reader).ok(),
            decompressed: Vec::new(),
            position: 0,
            size: le64(&header, 0x18),
        })
    }
}

impl<R: Read> Z3ds<R> {
    /// Decompresses until `end` bytes are, or the stream ends.
    fn fill(&mut self, end: u64) -> io::Result<()> {
        let end = end.min(self.size);
        if end > LIMIT as u64 {
            return Err(io::Error::other("past what a Z3DS image decompresses"));
        }
        let mut chunk = [0u8; 0x4000];
        while (self.decompressed.len() as u64) < end {
            let Some(decoder) = self.decoder.as_mut() else {
                break;
            };
            let n = decoder.read(&mut chunk)?;
            if n > 0 {
                self.decompressed.extend_from_slice(&chunk[..n]);
                continue;
            }
            // The frame ended; the next one follows it, unless the stream
            // ends here or the seek table, a skippable frame, comes next.
            let source = self.decoder.take().unwrap().into_inner();
            self.decoder = StreamingDecoder::new(source).ok();
        }
        Ok(())
    }
}

impl<R: Read> Read for Z3ds<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.fill(self.position + buf.len() as u64)?;
        let start = (self.position as usize).min(self.decompressed.len());
        let n = buf.len().min(self.decompressed.len() - start);
        buf[..n].copy_from_slice(&self.decompressed[start..start + n]);
        self.position += n as u64;
        Ok(n)
    }
}

impl<R: Read> Seek for Z3ds<R> {
    fn seek(&mut self, to: SeekFrom) -> io::Result<u64> {
        self.position = match to {
            SeekFrom::Start(at) => Some(at),
            SeekFrom::End(by) => self.size.checked_add_signed(by),
            SeekFrom::Current(by) => self.position.checked_add_signed(by),
        }
        .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidInput))?;
        Ok(self.position)
    }
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use ruzstd::encoding::{CompressionLevel, compress_to_vec};

    use super::*;

    /// A Z3DS image of `content`, compressed as two frames.
    pub(crate) fn z3ds(content: &[u8]) -> Vec<u8> {
        let mut image = Vec::new();
        image.extend_from_slice(b"Z3DSNCSD\x01\x00");
        image.extend_from_slice(&0x20u16.to_le_bytes());
        image.extend_from_slice(&0u32.to_le_bytes());
        image.extend_from_slice(&0u64.to_le_bytes());
        image.extend_from_slice(&(content.len() as u64).to_le_bytes());
        let (first, second) = content.split_at(content.len() / 3);
        image.extend(compress_to_vec(first, CompressionLevel::Fastest));
        image.extend(compress_to_vec(second, CompressionLevel::Fastest));
        image
    }

    #[test]
    fn reads_across_frames() {
        let content: Vec<u8> = (0..0x9000u32).map(|i| (i % 251) as u8).collect();
        let mut image = Z3ds::open(Cursor::new(z3ds(&content))).unwrap();
        assert_eq!(image.seek(SeekFrom::End(0)).unwrap(), 0x9000);
        let mut buf = vec![0u8; 0x100];
        crate::bytes::read_at(&mut image, 0x4000, &mut buf).unwrap();
        assert_eq!(buf, &content[0x4000..0x4100]);
        crate::bytes::read_at(&mut image, 0x10, &mut buf).unwrap();
        assert_eq!(buf, &content[0x10..0x110]);
    }

    #[test]
    fn reads_a_compressed_3ds_image() {
        let image = z3ds(&crate::n3ds::tests::cci(0x0004_0000_0005_5E00));
        let h = crate::n3ds::read(&mut Cursor::new(image)).unwrap();
        assert!(h.compressed);
        assert_eq!(h.container, crate::n3ds::Container::Cci);
        assert_eq!(h.program_id, 0x0004_0000_0005_5E00);
    }
}
