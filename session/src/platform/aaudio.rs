//! Audio output through AAudio.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use ndk::audio::{
    AudioCallbackResult, AudioDirection, AudioError, AudioFormat, AudioPerformanceMode,
    AudioSharingMode, AudioStream, AudioStreamBuilder, AudioUsage,
};

use crate::audio::{AudioSink, BufferStatus, NullAudioSink};

/// How much audio, in seconds, rate control keeps queued beyond a burst
/// when a frame's audio comes: a stream's callback takes a burst at a time,
/// and frames come a little early or late.
const MARGIN_SECONDS: f64 = 0.004;

/// How much more than its target the queue holds, in seconds: a few frames'
/// audio, for frames that come late and then catch up. Audio past that is
/// dropped rather than added as latency.
const HEADROOM_SECONDS: f64 = 0.05;

/// The ring buffer's size in samples: room for the queue at the highest rate
/// and the largest burst a device is likely to have. The ring is made before
/// the stream says its rate.
const RING_SAMPLES: usize = 2 * (192_000.0 * 0.2) as usize;

/// AAudio's output, or none if it can't be opened.
pub(crate) fn audio_sink() -> Box<dyn AudioSink> {
    match AAudioSink::new() {
        Ok(sink) => Box::new(sink),
        Err(e) => {
            log::error!("can't open audio output, so the session plays none: {e}");
            Box::new(NullAudioSink)
        }
    }
}

/// Plays the session's audio on the default output, in a low-latency AAudio
/// stream fed from a lock-free queue.
struct AAudioSink {
    stream: AudioStream,
    queue: rtrb::Producer<f32>,
    rate: u32,
    /// Set by the stream when its device goes away, as when headphones are
    /// unplugged. The next write opens a new stream on the new default.
    disconnected: Arc<AtomicBool>,
    /// Whether the stream is started.
    playing: bool,
    paused: bool,
    /// The stream's underruns as of the last write. The stream's buffer
    /// starts at a burst, the least latency it can have, and grows a burst
    /// whenever the stream underruns.
    xruns: i32,
}

impl AAudioSink {
    fn new() -> Result<Self, AudioError> {
        let (stream, queue, disconnected) = open(None)?;
        let rate = u32::try_from(stream.sample_rate()).unwrap_or(48_000);
        set_buffer_size(&stream, stream.frames_per_burst());
        log::info!(
            "audio: AAudio at {rate} Hz, {:?}, {:?}, {} frames per burst, buffer {} of {} frames",
            stream.sharing_mode(),
            stream.performance_mode(),
            stream.frames_per_burst(),
            stream.buffer_size_in_frames(),
            stream.buffer_capacity_in_frames(),
        );
        let xruns = stream.x_run_count();
        Ok(Self {
            stream,
            queue,
            rate,
            disconnected,
            playing: false,
            paused: false,
            xruns,
        })
    }

    /// Adds a burst to the stream's buffer if it underran since the last
    /// write.
    fn grow_after_underrun(&mut self) {
        let xruns = self.stream.x_run_count();
        if xruns <= self.xruns {
            return;
        }
        self.xruns = xruns;
        let size = self.stream.buffer_size_in_frames();
        if size < self.stream.buffer_capacity_in_frames() {
            set_buffer_size(&self.stream, size + self.stream.frames_per_burst());
            log::info!(
                "audio: the stream underran; its buffer is now {} frames",
                self.stream.buffer_size_in_frames()
            );
        }
    }

    fn target(&self) -> usize {
        let burst = usize::try_from(self.stream.frames_per_burst()).unwrap_or(0);
        burst + (f64::from(self.rate) * MARGIN_SECONDS) as usize
    }

    fn capacity(&self) -> usize {
        self.target() + (f64::from(self.rate) * HEADROOM_SECONDS) as usize
    }

    fn queued(&self) -> usize {
        (self.queue.buffer().capacity() - self.queue.slots()) / 2
    }

    /// Replaces a disconnected stream with one on the current default output,
    /// at the same rate.
    fn reopen(&mut self) {
        log::warn!("audio output disconnected; reopening");
        match open(i32::try_from(self.rate).ok()) {
            Ok((stream, queue, disconnected)) => {
                set_buffer_size(&stream, stream.frames_per_burst());
                self.xruns = stream.x_run_count();
                self.stream = stream;
                self.queue = queue;
                self.disconnected = disconnected;
                self.playing = false;
            }
            Err(e) => log::error!("can't reopen audio output: {e}"),
        }
    }
}

/// Sizes the stream's buffer to `frames`, or as near as it allows.
fn set_buffer_size(stream: &AudioStream, frames: i32) {
    // The ndk crate takes the size AAudio returns on success for an error
    // code, so only a negative one is a failure.
    if let Err(e) = stream.set_buffer_size_in_frames(frames)
        && stream.buffer_size_in_frames() != frames
    {
        log::warn!("can't set the audio buffer's size: {e}");
    }
}

/// Opens a stereo float stream whose callback plays what the returned queue
/// receives, and silence when it runs dry.
fn open(
    rate: Option<i32>,
) -> Result<(AudioStream, rtrb::Producer<f32>, Arc<AtomicBool>), AudioError> {
    let (producer, mut consumer) = rtrb::RingBuffer::<f32>::new(RING_SAMPLES);
    let disconnected = Arc::new(AtomicBool::new(false));
    let flag = disconnected.clone();
    let mut builder = AudioStreamBuilder::new()?
        .direction(AudioDirection::Output)
        .format(AudioFormat::PCM_Float)
        .channel_count(2)
        .performance_mode(AudioPerformanceMode::LowLatency)
        .sharing_mode(AudioSharingMode::Shared)
        .usage(AudioUsage::Game)
        .data_callback(Box::new(move |_, data, frames| {
            let len = usize::try_from(frames).unwrap_or(0) * 2;
            // SAFETY: AAudio passes room for `frames` frames of the stream's
            // format, two floats each.
            let out = unsafe { std::slice::from_raw_parts_mut(data.cast::<f32>(), len) };
            let n = consumer.slots().min(len);
            let mut filled = 0;
            if let Ok(chunk) = consumer.read_chunk(n) {
                let (a, b) = chunk.as_slices();
                out[..a.len()].copy_from_slice(a);
                out[a.len()..a.len() + b.len()].copy_from_slice(b);
                filled = a.len() + b.len();
                chunk.commit_all();
            }
            out[filled..].fill(0.0);
            AudioCallbackResult::Continue
        }))
        .error_callback(Box::new(move |_, error| {
            log::warn!("audio stream error: {error}");
            flag.store(true, Ordering::Release);
        }));
    if let Some(rate) = rate {
        builder = builder.sample_rate(rate);
    }
    let stream = builder.open_stream()?;
    Ok((stream, producer, disconnected))
}

impl AudioSink for AAudioSink {
    fn sample_rate(&self) -> u32 {
        self.rate
    }

    fn write(&mut self, samples: &[f32]) {
        if self.disconnected.load(Ordering::Acquire) {
            self.reopen();
        }
        // Whole frames only, so the callback always reads left then right.
        // Audio past the queue's capacity is dropped rather than added as
        // latency that rate control would take minutes to work off.
        let queued = self.queued();
        let room = self.capacity().saturating_sub(queued) * 2;
        let n = samples.len().min(room).min(self.queue.slots()) & !1;
        let _ = self.queue.push_partial_slice(&samples[..n]);
        // The stream starts once the queue held its target before a frame's
        // audio, as rate control keeps it, so the frame's audio lasts until
        // the next frame's.
        if !self.playing && !self.paused && queued >= self.target() {
            match self.stream.request_start() {
                Ok(()) => self.playing = true,
                Err(e) => log::error!("can't start audio output: {e}"),
            }
        }
        if self.playing {
            self.grow_after_underrun();
        }
    }

    fn buffer_status(&self) -> Option<BufferStatus> {
        Some(BufferStatus {
            queued: self.queued(),
            target: self.target(),
        })
    }

    fn set_paused(&mut self, paused: bool) {
        self.paused = paused;
        if paused && self.playing {
            if let Err(e) = self.stream.request_pause() {
                log::warn!("can't pause audio output: {e}");
            }
            self.playing = false;
        }
    }
}
