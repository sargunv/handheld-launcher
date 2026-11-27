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

/// A copy of a frame, as tightly packed RGBA8 rows from top to bottom.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct CapturedFrame {
    pub width: u32,
    pub height: u32,
    /// `width * height * 4` bytes, in R, G, B, A order with A always 255.
    pub pixels: Vec<u8>,
}

impl From<Frame<'_>> for CapturedFrame {
    fn from(frame: Frame<'_>) -> Self {
        Self {
            width: frame.width,
            height: frame.height,
            pixels: frame.pixels.to_vec(),
        }
    }
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

/// A sink that drops every frame.
pub(crate) struct NullVideoSink;

impl VideoSink for NullVideoSink {
    fn frame(&mut self, _frame: Frame<'_>) {}
}

/// The pixel formats a software-rendering core can produce.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) enum PixelFormat {
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
    pub(crate) fn bytes_per_pixel(self) -> usize {
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
pub(crate) fn convert_frame(
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

/// The largest rectangle with `aspect` that fits the window, centered, as
/// (x, y, width, height). A non-positive `aspect` uses the frame's own.
pub(crate) fn fit(
    width: u32,
    height: u32,
    aspect: f32,
    win_w: u32,
    win_h: u32,
) -> (i32, i32, i32, i32) {
    let aspect = if aspect > 0.0 {
        f64::from(aspect)
    } else {
        f64::from(width) / f64::from(height.max(1))
    };
    let (win_w, win_h) = (f64::from(win_w), f64::from(win_h));
    let (w, h) = if win_w / win_h.max(1.0) > aspect {
        (win_h * aspect, win_h)
    } else {
        (win_w, win_w / aspect)
    };
    let (w, h) = (w.round(), h.round());
    (
        ((win_w - w) / 2.0).round() as i32,
        ((win_h - h) / 2.0).round() as i32,
        w as i32,
        h as i32,
    )
}

/// The part of the core's frame a window shows. A two-screen system's core
/// draws its screens stacked, top above bottom, with no gap, so a session with
/// a lower window shows the frame's top half on the main window and its bottom
/// half on the lower one. The bottom screen may be narrower than the top, as
/// the 3DS's is, centred under it, so the lower window shows only its share of
/// the frame's width.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Region {
    /// The whole frame.
    Whole,
    /// The upper half.
    Top,
    /// The lower half, with the odd row if there is one, and of it, the
    /// centred `share` of the frame's width, from 0 to 1.
    Bottom { share: f32 },
}

impl Region {
    /// The lower half of a frame whose bottom screen spans `share` of its
    /// width, centred, or the whole width when it's `None`, not positive or
    /// over 1.
    pub fn bottom(share: Option<f32>) -> Self {
        let share = share.filter(|s| *s > 0.0 && *s <= 1.0).unwrap_or(1.0);
        Self::Bottom { share }
    }

    /// The region's rows of a frame `height` rows tall, as the first row,
    /// counted from the top, and how many.
    pub(crate) fn rows(self, height: u32) -> (u32, u32) {
        let half = height / 2;
        match self {
            Self::Whole => (0, height),
            Self::Top => (0, half),
            Self::Bottom { .. } => (half, height - half),
        }
    }

    /// The region's columns of a frame `width` pixels wide, as the first
    /// column, counted from the left, and how many.
    pub(crate) fn columns(self, width: u32) -> (u32, u32) {
        match self {
            Self::Whole | Self::Top => (0, width),
            Self::Bottom { share } => {
                let columns = (f64::from(width) * f64::from(share)).round() as u32;
                let columns = columns.clamp(width.min(1), width);
                ((width - columns) / 2, columns)
            }
        }
    }

    /// The display aspect ratio the region has in a `width` by `height`
    /// frame shown at `aspect`, or at the frame's own when it's not positive.
    pub(crate) fn aspect(self, width: u32, height: u32, aspect: f32) -> f32 {
        let frame = if aspect > 0.0 {
            f64::from(aspect)
        } else {
            f64::from(width) / f64::from(height.max(1))
        };
        let (_, rows) = self.rows(height);
        let (_, columns) = self.columns(width);
        (frame * f64::from(height) / f64::from(rows.max(1)) * f64::from(columns)
            / f64::from(width.max(1))) as f32
    }

    /// Where the region of a `width` by `height` frame shown at `aspect` goes
    /// on a `win_w` by `win_h` window: scaled to fit and centred, as
    /// (x, y, width, height).
    pub fn fit(
        self,
        width: u32,
        height: u32,
        aspect: f32,
        win_w: u32,
        win_h: u32,
    ) -> (i32, i32, i32, i32) {
        fit(
            self.columns(width).1,
            self.rows(height).1,
            self.aspect(width, height, aspect),
            win_w,
            win_h,
        )
    }
}

/// Where a touch at (`x`, `y`) on a `win_w` by `win_h` window that shows
/// `region` of a `width` by `height` frame at `aspect` lands, as libretro's
/// pointer reads it: each axis from -32767 to 32767 across the whole frame,
/// whichever region the window shows. A touch on the window's black bars,
/// outside the region, lands nowhere.
#[allow(clippy::too_many_arguments)]
pub(crate) fn pointer_position(
    region: Region,
    width: u32,
    height: u32,
    aspect: f32,
    win_w: u32,
    win_h: u32,
    x: f32,
    y: f32,
) -> Option<(i16, i16)> {
    if width == 0 || height == 0 {
        return None;
    }
    let (left, top, w, h) = region.fit(width, height, aspect, win_w, win_h);
    let across = (f64::from(x) - f64::from(left)) / f64::from(w.max(1));
    let down = (f64::from(y) - f64::from(top)) / f64::from(h.max(1));
    if !(0.0..=1.0).contains(&across) || !(0.0..=1.0).contains(&down) {
        return None;
    }
    let (first_column, columns) = region.columns(width);
    let (first_row, rows) = region.rows(height);
    let frame_across = (f64::from(first_column) + across * f64::from(columns)) / f64::from(width);
    let frame_down = (f64::from(first_row) + down * f64::from(rows)) / f64::from(height);
    let scale = |n: f64| ((n * 2.0 - 1.0) * f64::from(i16::MAX)).round() as i16;
    Some((scale(frame_across), scale(frame_down)))
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

    #[test]
    fn fit_letterboxes_and_pillarboxes() {
        assert_eq!(fit(320, 240, 4.0 / 3.0, 1920, 1080), (240, 0, 1440, 1080));
        assert_eq!(fit(320, 240, 4.0 / 3.0, 800, 1000), (0, 200, 800, 600));
        assert_eq!(fit(256, 224, 0.0, 256, 224), (0, 0, 256, 224));
    }

    #[test]
    fn splits_a_frame_into_halves() {
        assert_eq!(Region::Whole.rows(384), (0, 384));
        assert_eq!(Region::Top.rows(384), (0, 192));
        assert_eq!(Region::bottom(None).rows(384), (192, 192));
        assert_eq!(Region::bottom(None).rows(5), (2, 3));
    }

    #[test]
    fn each_half_of_a_ds_frame_is_four_by_three() {
        // melonDS DS draws its screens stacked at 256x384, shown at 2:3.
        for region in [Region::Top, Region::bottom(None)] {
            let aspect = region.aspect(256, 384, 2.0 / 3.0);
            assert!((aspect - 4.0 / 3.0).abs() < 1e-6, "{region:?}: {aspect}");
            assert!((region.aspect(256, 384, 0.0) - 4.0 / 3.0).abs() < 1e-6);
        }
        // On the Thor's screens.
        let (w, h) = (256, 192);
        let top = Region::Top.aspect(256, 384, 2.0 / 3.0);
        let bottom = Region::bottom(None).aspect(256, 384, 2.0 / 3.0);
        assert_eq!(fit(w, h, top, 1920, 1080), (240, 0, 1440, 1080));
        assert_eq!(fit(w, h, bottom, 1240, 1080), (0, 75, 1240, 930));
    }

    #[test]
    fn touches_on_the_lower_window_land_on_the_frames_bottom_half() {
        let at =
            |x, y| pointer_position(Region::bottom(None), 256, 384, 2.0 / 3.0, 1240, 1080, x, y);
        // The bottom half shows at (0, 75), 1240x930.
        assert_eq!(at(0.0, 75.0), Some((-32767, 0)));
        assert_eq!(at(1240.0, 1005.0), Some((32767, 32767)));
        assert_eq!(at(620.0, 540.0), Some((0, 16384)));
        // The black bars above and below.
        assert_eq!(at(620.0, 40.0), None);
        assert_eq!(at(620.0, 1040.0), None);
    }

    #[test]
    fn crops_a_narrower_bottom_screen_to_its_columns() {
        // Azahar draws the 3DS's 400x240 top screen above its 320x240 bottom
        // one, centred, at 400x480, shown at 5:6.
        let bottom = Region::bottom(Some(0.8));
        assert_eq!(bottom.rows(480), (240, 240));
        assert_eq!(bottom.columns(400), (40, 320));
        assert!((bottom.aspect(400, 480, 400.0 / 480.0) - 4.0 / 3.0).abs() < 1e-6);
        // It fills the Thor's lower screen's width.
        assert_eq!(
            bottom.fit(400, 480, 400.0 / 480.0, 1240, 1080),
            (0, 75, 1240, 930)
        );
        // The top screen keeps the whole width, at 5:3 on the main screen.
        assert_eq!(Region::Top.columns(400), (0, 400));
        assert_eq!(
            Region::Top.fit(400, 480, 400.0 / 480.0, 1920, 1080),
            (60, 0, 1800, 1080)
        );
    }

    #[test]
    fn a_missing_or_impossible_share_keeps_the_whole_width() {
        for share in [None, Some(0.0), Some(-1.0), Some(1.5), Some(f32::NAN)] {
            assert_eq!(Region::bottom(share).columns(256), (0, 256), "{share:?}");
        }
        assert_eq!(Region::bottom(Some(0.001)).columns(256), (127, 1));
        assert_eq!(Region::bottom(Some(0.5)).columns(0), (0, 0));
    }

    #[test]
    fn touches_on_a_cropped_lower_window_land_on_the_bottom_screen() {
        let at = |x, y| {
            pointer_position(
                Region::bottom(Some(0.8)),
                400,
                480,
                400.0 / 480.0,
                1240,
                1080,
                x,
                y,
            )
        };
        // The window's left and right edges are the bottom screen's, at
        // columns 40 and 360 of 400: a tenth of the way in from each side.
        assert_eq!(at(0.0, 75.0), Some((-26214, 0)));
        assert_eq!(at(1240.0, 1005.0), Some((26214, 32767)));
        assert_eq!(at(620.0, 540.0), Some((0, 16384)));
        // The black bars above and below.
        assert_eq!(at(620.0, 40.0), None);
        assert_eq!(at(620.0, 1040.0), None);
    }

    #[test]
    fn touches_on_one_window_land_on_the_whole_frame() {
        let at = |x, y| pointer_position(Region::Whole, 256, 384, 2.0 / 3.0, 1920, 1080, x, y);
        // The frame shows at (600, 0), 720x1080.
        assert_eq!(at(600.0, 0.0), Some((-32767, -32767)));
        assert_eq!(at(960.0, 810.0), Some((0, 16384)));
        assert_eq!(at(1320.0, 1080.0), Some((32767, 32767)));
        assert_eq!(at(100.0, 540.0), None);
        let top = |x, y| pointer_position(Region::Top, 256, 384, 2.0 / 3.0, 1920, 1080, x, y);
        assert_eq!(top(1680.0, 1080.0), Some((32767, 0)));
    }
}
