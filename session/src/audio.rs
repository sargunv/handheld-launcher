//! Core audio: buffering, resampling to the output rate, and dynamic rate
//! control, into the sink the platform layer provides.

use rubato::audioadapter_buffers::direct::InterleavedSlice;
use rubato::{Adjustable, Async, FixedAsync, PolynomialDegree, Resampler};

use crate::pacing::MAX_SPEED_CHANGE;

/// Plays the audio a session produces.
///
/// A session calls the sink on the thread that runs it, after each frame.
pub trait AudioSink {
    /// The rate, in Hz, the sink plays at. The session reads it once, when it
    /// starts or when the core's rate changes.
    fn sample_rate(&self) -> u32;

    /// Queues interleaved stereo samples, at [`AudioSink::sample_rate`], in
    /// the range -1.0 to 1.0.
    fn write(&mut self, samples: &[f32]);

    /// How full the sink's queue is. When the sink reports it, the session
    /// nudges the resampling ratio to keep the queue at its target, so that
    /// audio neither underruns nor drifts behind video. Without it, the
    /// session resamples at the nominal ratio.
    fn buffer_status(&self) -> Option<BufferStatus> {
        None
    }

    /// Called when the session pauses or resumes, so the sink can stop its
    /// output while no audio comes.
    fn set_paused(&mut self, _paused: bool) {}
}

/// How full an [`AudioSink`]'s queue is, in stereo frames.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct BufferStatus {
    /// Frames queued and not yet played.
    pub queued: usize,
    /// Frames the sink wants queued when the session is about to queue a
    /// frame's audio: the least it needs to play without a gap until the
    /// next frame's.
    pub target: usize,
}

/// A sink that drops everything, for sessions without audio output.
#[derive(Clone, Copy, Debug, Default)]
pub struct NullAudioSink;

impl AudioSink for NullAudioSink {
    fn sample_rate(&self) -> u32 {
        48_000
    }

    fn write(&mut self, _samples: &[f32]) {}
}

/// The largest fraction by which rate control changes the resampling ratio.
/// Half a percent of pitch is inaudible.
const MAX_RATE_DEVIATION: f64 = 0.005;

/// Input frames per resampler call. Small, since what's left over waits for
/// the next frame, which makes the sink's queue jitter by up to this much.
const CHUNK_FRAMES: usize = 64;

/// Converts the core's 16-bit audio at its rate into float audio at the
/// sink's rate.
pub(crate) struct AudioPipeline {
    resampler: Async<f32>,
    /// Interleaved input not yet resampled.
    pending: Vec<f32>,
    output: Vec<f32>,
}

impl AudioPipeline {
    /// A pipeline from `input_rate` to `output_rate`, or `None` if either rate
    /// is unusable.
    pub(crate) fn new(input_rate: f64, output_rate: u32) -> Option<Self> {
        if !(input_rate.is_finite() && input_rate > 0.0) || output_rate == 0 {
            return None;
        }
        let ratio = f64::from(output_rate) / input_rate;
        let resampler = Async::<f32>::new_poly(
            ratio,
            (1.0 + MAX_SPEED_CHANGE) * (1.0 + 2.0 * MAX_RATE_DEVIATION),
            PolynomialDegree::Cubic,
            CHUNK_FRAMES,
            2,
            FixedAsync::Input,
        )
        .inspect_err(|e| log::error!("can't resample {input_rate} Hz to {output_rate} Hz: {e}"))
        .ok()?;
        let output = vec![0.0; resampler.output_frames_max() * 2];
        Some(Self {
            resampler,
            pending: Vec::with_capacity(CHUNK_FRAMES * 8),
            output,
        })
    }

    /// Queues interleaved stereo samples from the core.
    pub(crate) fn push(&mut self, samples: &[i16]) {
        self.pending
            .extend(samples.iter().map(|&s| f32::from(s) / 32768.0));
    }

    /// Resamples every whole chunk queued so far into `sink`, and keeps the
    /// remainder for the next call. The core runs at `speed` times its own
    /// rate, so its audio plays that much slower to keep up.
    pub(crate) fn flush(&mut self, sink: &mut dyn AudioSink, speed: f64) {
        let speed = speed.clamp(1.0 - MAX_SPEED_CHANGE, 1.0 + MAX_SPEED_CHANGE);
        let control = sink.buffer_status().map_or(1.0, rate_adjustment);
        if let Err(e) = self
            .resampler
            .set_resample_ratio_relative(control / speed, true)
        {
            log::warn!("can't adjust the resampling ratio: {e}");
        }
        let mut consumed = 0;
        while self.pending.len() - consumed >= self.resampler.input_frames_next() * 2 {
            let frames_in = self.resampler.input_frames_next();
            let input = &self.pending[consumed..consumed + frames_in * 2];
            let input = InterleavedSlice::new(input, 2, frames_in).expect("sized input");
            let capacity = self.output.len() / 2;
            let mut output =
                InterleavedSlice::new_mut(&mut self.output, 2, capacity).expect("sized output");
            match self
                .resampler
                .process_into_buffer(&input, &mut output, None)
            {
                Ok((read, written)) => {
                    consumed += read * 2;
                    sink.write(&self.output[..written * 2]);
                }
                Err(e) => {
                    log::error!("resampling failed, dropping queued audio: {e}");
                    consumed = self.pending.len();
                    break;
                }
            }
        }
        self.pending.drain(..consumed);
    }
}

/// The relative resampling ratio that moves the sink's queue toward its
/// target: above 1 when the queue runs low, so that more output frames are
/// produced, and below 1 when it runs high.
fn rate_adjustment(status: BufferStatus) -> f64 {
    if status.target == 0 {
        return 1.0;
    }
    let target = status.target as f64;
    let direction = ((target - status.queued as f64) / target).clamp(-1.0, 1.0);
    1.0 + MAX_RATE_DEVIATION * direction
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A sink that records everything and reports a fixed queue level.
    struct Recorder {
        rate: u32,
        samples: Vec<f32>,
        status: Option<BufferStatus>,
    }

    impl AudioSink for Recorder {
        fn sample_rate(&self) -> u32 {
            self.rate
        }
        fn write(&mut self, samples: &[f32]) {
            self.samples.extend_from_slice(samples);
        }
        fn buffer_status(&self) -> Option<BufferStatus> {
            self.status
        }
    }

    fn sine(rate: f64, freq: f64, frames: usize) -> Vec<i16> {
        (0..frames)
            .flat_map(|i| {
                let v =
                    (8000.0 * (2.0 * std::f64::consts::PI * freq * i as f64 / rate).sin()) as i16;
                [v, v]
            })
            .collect()
    }

    fn run(status: Option<BufferStatus>) -> Vec<f32> {
        let mut pipeline = AudioPipeline::new(32_000.0, 48_000).unwrap();
        let mut sink = Recorder {
            rate: 48_000,
            samples: Vec::new(),
            status,
        };
        // One second of input, in video-frame-sized pieces.
        let input = sine(32_000.0, 440.0, 32_000);
        for piece in input.chunks(2 * 32_000 / 60) {
            pipeline.push(piece);
            pipeline.flush(&mut sink, 1.0);
        }
        sink.samples
    }

    fn zero_crossings(samples: &[f32]) -> usize {
        samples
            .as_chunks::<2>()
            .0
            .iter()
            .map(|f| f[0])
            .collect::<Vec<_>>()
            .windows(2)
            .filter(|w| (w[0] < 0.0) != (w[1] < 0.0))
            .count()
    }

    #[test]
    fn resamples_to_the_output_rate_and_keeps_pitch() {
        let out = run(None);
        let frames = out.len() / 2;
        // Up to one chunk stays queued, plus the resampler's delay.
        assert!((47_000..=48_000).contains(&frames), "{frames} frames");
        // 440 Hz crosses zero 880 times a second.
        let crossings = zero_crossings(&out);
        assert!((870..=890).contains(&crossings), "{crossings} crossings");
        assert!(out.iter().all(|s| s.abs() <= 0.3));
    }

    #[test]
    fn rate_control_fills_a_starved_queue_and_drains_a_full_one() {
        let nominal = run(None).len();
        let starved = run(Some(BufferStatus {
            queued: 0,
            target: 2048,
        }))
        .len();
        let full = run(Some(BufferStatus {
            queued: 4096,
            target: 2048,
        }))
        .len();
        assert!(starved > nominal, "{starved} <= {nominal}");
        assert!(full < nominal, "{full} >= {nominal}");
        // Never more than the maximum deviation, plus a chunk of slack.
        let bound = (nominal as f64 * MAX_RATE_DEVIATION) as usize + 2 * CHUNK_FRAMES;
        assert!(starved - nominal <= bound && nominal - full <= bound);
    }

    #[test]
    fn rate_adjustment_is_neutral_at_the_target() {
        let at_target = BufferStatus {
            queued: 500,
            target: 500,
        };
        assert_eq!(rate_adjustment(at_target), 1.0);
        assert_eq!(
            rate_adjustment(BufferStatus {
                queued: 0,
                target: 0
            }),
            1.0
        );
    }

    #[test]
    fn rejects_unusable_rates() {
        assert!(AudioPipeline::new(0.0, 48_000).is_none());
        assert!(AudioPipeline::new(f64::NAN, 48_000).is_none());
        assert!(AudioPipeline::new(32_000.0, 0).is_none());
    }
}
