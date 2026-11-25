//! Video frames and the sink the platform layer receives them through.

use crate::ffi;

/// A frame the core produced, as tightly packed RGBA8 rows from top to bottom.
#[derive(Clone, Copy, Debug)]
pub struct Frame<'a> {
    /// Width in pixels.
    pub width: u32,
    /// Height in pixels.
    pub height: u32,
    /// `width * height * 4` bytes, in R, G, B, A order with A always 255.
    pub pixels: &'a [u8],
}

impl Frame<'_> {
    /// The RGBA value of the pixel at (`x`, `y`), counted from the top left.
    ///
    /// # Panics
    ///
    /// If the pixel is outside the frame.
    pub fn pixel(&self, x: u32, y: u32) -> [u8; 4] {
        assert!(x < self.width && y < self.height, "pixel outside frame");
        let i = (y as usize * self.width as usize + x as usize) * 4;
        self.pixels[i..i + 4].try_into().expect("four bytes")
    }
}

/// A copy of a frame, as tightly packed RGBA8 rows from top to bottom.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct CapturedFrame {
    pub width: u32,
    pub height: u32,
    /// `width * height * 4` bytes, in R, G, B, A order with A always 255.
    pub pixels: Vec<u8>,
}

/// Receives the frames a session produces when it doesn't present them to a
/// window itself.
///
/// A session calls the sink on the thread that runs it, once for each frame
/// the core finishes, including frames the core repeats. Hardware-rendered
/// frames are read back from the GPU before they reach the sink.
pub trait VideoSink {
    /// Takes the frame. It's valid only for the duration of the call.
    fn frame(&mut self, frame: Frame<'_>);
}

/// The pixel formats a software-rendering core can produce.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum PixelFormat {
    /// 16 bits per pixel: one unused bit, then five bits each of red, green
    /// and blue. libretro's default.
    #[default]
    Rgb1555,
    /// 32 bits per pixel: eight unused bits, then eight bits each of red,
    /// green and blue.
    Xrgb8888,
    /// 16 bits per pixel: five bits of red, six of green, five of blue.
    Rgb565,
}

impl PixelFormat {
    pub(crate) fn from_retro(format: ffi::retro_pixel_format) -> Option<Self> {
        match format {
            ffi::RETRO_PIXEL_FORMAT_0RGB1555 => Some(Self::Rgb1555),
            ffi::RETRO_PIXEL_FORMAT_XRGB8888 => Some(Self::Xrgb8888),
            ffi::RETRO_PIXEL_FORMAT_RGB565 => Some(Self::Rgb565),
            _ => None,
        }
    }

    /// Bytes per pixel.
    pub fn bytes_per_pixel(self) -> usize {
        match self {
            Self::Rgb1555 | Self::Rgb565 => 2,
            Self::Xrgb8888 => 4,
        }
    }
}

/// Converts a core's frame into tightly packed RGBA8 in `out`, which it
/// resizes to fit.
///
/// `src` holds `height` rows `pitch` bytes apart, and the last row needs only
/// `width` pixels.
///
/// # Panics
///
/// If `src` is too short for the dimensions, or `pitch` is shorter than a row.
pub fn convert_frame(
    format: PixelFormat,
    src: &[u8],
    width: usize,
    height: usize,
    pitch: usize,
    out: &mut Vec<u8>,
) {
    let row_bytes = width * format.bytes_per_pixel();
    assert!(pitch >= row_bytes, "pitch shorter than a row");
    if height > 0 {
        assert!(
            src.len() >= pitch * (height - 1) + row_bytes,
            "frame data too short"
        );
    }
    out.resize(width * height * 4, 0);
    if width == 0 {
        return;
    }
    for (y, dst_row) in out.chunks_exact_mut(width * 4).enumerate() {
        let src_row = &src[y * pitch..y * pitch + row_bytes];
        let dst = dst_row.as_chunks_mut::<4>().0;
        match format {
            PixelFormat::Xrgb8888 => {
                for (dst, px) in dst.iter_mut().zip(src_row.as_chunks::<4>().0) {
                    let p = u32::from_ne_bytes(*px);
                    *dst = [(p >> 16) as u8, (p >> 8) as u8, p as u8, 255];
                }
            }
            PixelFormat::Rgb565 => {
                for (dst, px) in dst.iter_mut().zip(src_row.as_chunks::<2>().0) {
                    let p = u16::from_ne_bytes(*px);
                    let r = expand5((p >> 11) & 0x1f);
                    let g = expand6((p >> 5) & 0x3f);
                    let b = expand5(p & 0x1f);
                    *dst = [r, g, b, 255];
                }
            }
            PixelFormat::Rgb1555 => {
                for (dst, px) in dst.iter_mut().zip(src_row.as_chunks::<2>().0) {
                    let p = u16::from_ne_bytes(*px);
                    let r = expand5((p >> 10) & 0x1f);
                    let g = expand5((p >> 5) & 0x1f);
                    let b = expand5(p & 0x1f);
                    *dst = [r, g, b, 255];
                }
            }
        }
    }
}

/// Scales a 5-bit channel to 8 bits, so that 0 and 31 map to 0 and 255.
fn expand5(v: u16) -> u8 {
    ((v << 3) | (v >> 2)) as u8
}

/// Scales a 6-bit channel to 8 bits, so that 0 and 63 map to 0 and 255.
fn expand6(v: u16) -> u8 {
    ((v << 2) | (v >> 4)) as u8
}

/// Flips tightly packed RGBA8 rows in place, top to bottom.
pub(crate) fn flip_rows(pixels: &mut [u8], width: usize, height: usize) {
    let row = width * 4;
    for y in 0..height / 2 {
        let (top, bottom) = pixels.split_at_mut((height - 1 - y) * row);
        top[y * row..(y + 1) * row].swap_with_slice(&mut bottom[..row]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ne16(values: &[u16]) -> Vec<u8> {
        values.iter().flat_map(|v| v.to_ne_bytes()).collect()
    }

    #[test]
    fn converts_xrgb8888() {
        let src: Vec<u8> = [0xff12_3456u32, 0x0000_00ff]
            .iter()
            .flat_map(|v| v.to_ne_bytes())
            .collect();
        let mut out = Vec::new();
        convert_frame(PixelFormat::Xrgb8888, &src, 2, 1, 8, &mut out);
        assert_eq!(out, [0x12, 0x34, 0x56, 255, 0, 0, 0xff, 255]);
    }

    #[test]
    fn converts_rgb565_to_full_range() {
        let src = ne16(&[0xf800, 0x07e0, 0x001f, 0xffff, 0x0000]);
        let mut out = Vec::new();
        convert_frame(PixelFormat::Rgb565, &src, 5, 1, 10, &mut out);
        assert_eq!(
            out,
            [
                255, 0, 0, 255, //
                0, 255, 0, 255, //
                0, 0, 255, 255, //
                255, 255, 255, 255, //
                0, 0, 0, 255,
            ]
        );
    }

    #[test]
    fn converts_rgb1555_and_ignores_the_top_bit() {
        let src = ne16(&[0x7c00, 0x03e0, 0x001f, 0x8000 | 0x4210]);
        let mut out = Vec::new();
        convert_frame(PixelFormat::Rgb1555, &src, 4, 1, 8, &mut out);
        assert_eq!(
            out,
            [
                255, 0, 0, 255, //
                0, 255, 0, 255, //
                0, 0, 255, 255, //
                132, 132, 132, 255,
            ]
        );
    }

    #[test]
    fn honors_pitch_padding() {
        // Two rows of one RGB565 pixel each, padded to 4 bytes per row, with
        // the last row's padding missing.
        let mut src = ne16(&[0xf800, 0xdead]);
        src.extend(ne16(&[0x001f]));
        let mut out = Vec::new();
        convert_frame(PixelFormat::Rgb565, &src, 1, 2, 4, &mut out);
        assert_eq!(out, [255, 0, 0, 255, 0, 0, 255, 255]);
    }

    #[test]
    fn flips_rows() {
        let mut px: Vec<u8> = (0..3).flat_map(|r| [r; 8]).collect();
        flip_rows(&mut px, 2, 3);
        let rows: Vec<u8> = px.chunks(8).map(|r| r[0]).collect();
        assert_eq!(rows, [2, 1, 0]);
    }
}
