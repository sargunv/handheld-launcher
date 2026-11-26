//! Reading fields out of headers.

use std::io::{self, Read, Seek, SeekFrom};

/// Fills `buf` from `offset` in `reader`.
pub(crate) fn read_at<R: Read + Seek + ?Sized>(
    reader: &mut R,
    offset: u64,
    buf: &mut [u8],
) -> io::Result<()> {
    reader.seek(SeekFrom::Start(offset))?;
    reader.read_exact(buf)
}

/// `N` bytes from `offset` in `reader`.
pub(crate) fn array_at<const N: usize, R: Read + Seek + ?Sized>(
    reader: &mut R,
    offset: u64,
) -> io::Result<[u8; N]> {
    let mut buf = [0; N];
    read_at(reader, offset, &mut buf)?;
    Ok(buf)
}

/// `len` bytes from `offset` in `reader`.
pub(crate) fn vec_at<R: Read + Seek + ?Sized>(
    reader: &mut R,
    offset: u64,
    len: usize,
) -> io::Result<Vec<u8>> {
    let mut buf = vec![0; len];
    read_at(reader, offset, &mut buf)?;
    Ok(buf)
}

/// Up to `len` bytes from `offset` in `reader`: fewer where it ends sooner.
pub(crate) fn prefix_at<R: Read + Seek + ?Sized>(
    reader: &mut R,
    offset: u64,
    len: usize,
) -> io::Result<Vec<u8>> {
    reader.seek(SeekFrom::Start(offset))?;
    let mut buf = Vec::with_capacity(len);
    reader.take(len as u64).read_to_end(&mut buf)?;
    Ok(buf)
}

/// The length of what `reader` reads, in bytes.
pub(crate) fn stream_len<R: Seek + ?Sized>(reader: &mut R) -> io::Result<u64> {
    reader.seek(SeekFrom::End(0))
}

pub(crate) fn le16(b: &[u8], at: usize) -> u16 {
    u16::from_le_bytes([b[at], b[at + 1]])
}

pub(crate) fn le32(b: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(b[at..at + 4].try_into().unwrap())
}

pub(crate) fn le64(b: &[u8], at: usize) -> u64 {
    u64::from_le_bytes(b[at..at + 8].try_into().unwrap())
}

pub(crate) fn be16(b: &[u8], at: usize) -> u16 {
    u16::from_be_bytes([b[at], b[at + 1]])
}

pub(crate) fn be32(b: &[u8], at: usize) -> u32 {
    u32::from_be_bytes(b[at..at + 4].try_into().unwrap())
}

pub(crate) fn be64(b: &[u8], at: usize) -> u64 {
    u64::from_be_bytes(b[at..at + 8].try_into().unwrap())
}

/// The bytes before the first NUL.
fn until_nul(b: &[u8]) -> &[u8] {
    b.iter().position(|&c| c == 0).map_or(b, |end| &b[..end])
}

/// A text field: the bytes before the first NUL, as ASCII, trimmed of spaces.
/// A byte outside ASCII reads as U+FFFD.
pub(crate) fn ascii(b: &[u8]) -> String {
    until_nul(b)
        .iter()
        .map(|&c| if c.is_ascii() { c as char } else { '\u{FFFD}' })
        .collect::<String>()
        .trim()
        .to_owned()
}

/// A JIS X 0201 text field, as the Super Famicom writes titles: ASCII and
/// half-width katakana, up to the first NUL or byte outside them, trimmed of
/// spaces.
pub(crate) fn jis_x0201(b: &[u8]) -> String {
    let mut out = String::new();
    for &c in b {
        match c {
            0x20..=0x7E => out.push(c as char),
            // Half-width katakana: U+FF61 to U+FF9F.
            0xA1..=0xDF => out.push(char::from_u32(0xFF61 + (c - 0xA1) as u32).unwrap()),
            _ => break,
        }
    }
    out.trim().to_owned()
}

/// A Shift-JIS text field, as Sega's Japanese titles are, up to the first NUL,
/// trimmed of spaces, including the full-width space.
pub(crate) fn shift_jis(b: &[u8]) -> String {
    let (text, _, _) = encoding_rs::SHIFT_JIS.decode(until_nul(b));
    text.trim_matches(|c: char| c.is_whitespace()).to_owned()
}

/// A UTF-16LE text field, up to the first NUL.
pub(crate) fn utf16le(b: &[u8]) -> String {
    let units: Vec<u16> = b
        .as_chunks::<2>()
        .0
        .iter()
        .map(|&c| u16::from_le_bytes(c))
        .take_while(|&u| u != 0)
        .collect();
    String::from_utf16_lossy(&units)
}

/// A code made of characters, such as a game code, when every byte is
/// printable ASCII; else its bytes in hex.
pub(crate) fn code(b: &[u8]) -> String {
    if b.iter().all(|c| c.is_ascii_graphic() || *c == b' ') {
        String::from_utf8_lossy(b).into_owned()
    } else {
        b.iter().map(|c| format!("{c:02X}")).collect()
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn reads_text_fields() {
        assert_eq!(super::ascii(b"ROGUE\0\0\0junk"), "ROGUE");
        assert_eq!(super::ascii(b"  TITLE   "), "TITLE");
        assert_eq!(
            super::jis_x0201(b"ABC \xB1\xB2\xFF rest"),
            "ABC \u{FF71}\u{FF72}"
        );
        assert_eq!(super::shift_jis(b"\x83\x51\x81\x5B\x83\x80  \0x"), "ゲーム");
        assert_eq!(super::utf16le(b"H\0i\0\0\0x\0"), "Hi");
        assert_eq!(super::code(b"01"), "01");
        assert_eq!(super::code(b"\xB1\xB0"), "B1B0");
    }
}
