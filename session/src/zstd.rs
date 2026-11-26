//! Zstd decompression for the app, which reads game ids from the start of
//! compressed images, such as Azahar's `.zcci`.

use std::fs::File;
use std::io::{BufReader, Read, Seek, SeekFrom};

/// Up to `length` bytes of the zstd stream that starts at `offset` in the
/// file at `path`, decompressed: fewer when the stream ends sooner. Null when
/// the file can't be read or isn't zstd there.
#[uniffi::export]
pub fn zstd_prefix(path: String, offset: u64, length: u32) -> Option<Vec<u8>> {
    let mut file = File::open(path).ok()?;
    file.seek(SeekFrom::Start(offset)).ok()?;
    let mut reader = BufReader::new(file);
    let mut out = Vec::with_capacity(length as usize);
    // A seekable stream, as Azahar writes, is a run of frames.
    while out.len() < length as usize {
        let Ok(decoder) = ruzstd::decoding::StreamingDecoder::new(&mut reader) else {
            break;
        };
        let left = length as usize - out.len();
        let before = out.len();
        decoder.take(left as u64).read_to_end(&mut out).ok()?;
        if out.len() == before {
            break;
        }
    }
    (!out.is_empty()).then_some(out)
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    #[test]
    fn reads_a_prefix_after_an_offset() {
        let data: Vec<u8> = (0..100_000u32).map(|i| (i % 251) as u8).collect();
        let level = ruzstd::encoding::CompressionLevel::Fastest;
        let mut file = tempfile::NamedTempFile::new().unwrap();
        file.write_all(b"header").unwrap();
        // Two frames, as a seekable stream holds.
        for half in data.chunks(0x3000).take(2) {
            file.write_all(&ruzstd::encoding::compress_to_vec(half, level))
                .unwrap();
        }
        let path = file.path().to_str().unwrap().to_owned();

        let prefix = super::zstd_prefix(path.clone(), 6, 0x4000).unwrap();
        assert_eq!(prefix, &data[..0x4000]);
        let all = super::zstd_prefix(path.clone(), 6, 0x10000).unwrap();
        assert_eq!(all, &data[..0x6000], "The stream ends after two frames");
        assert!(super::zstd_prefix(path, 0, 16).is_none());
    }
}
