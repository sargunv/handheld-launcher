//! Audio output through AAudio.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use ndk::audio::{
    AudioCallbackResult, AudioDirection, AudioError, AudioFormat, AudioPerformanceMode,
    AudioSharingMode, AudioStream, AudioStreamBuilder, AudioUsage,
};

use crate::audio::{AudioSink, BufferStatus};

/// How much audio the session keeps queued, at most, in seconds. Rate
/// control keeps the queue half full.
const QUEUE_SECONDS: f64 = 0.1;

/// The ring buffer's size in seconds at the highest rate a device is likely
/// to pick. The ring is made before the stream says its rate.
const RING_SAMPLES: usize = 2 * (192_000.0 * QUEUE_SECONDS) as usize;

/// Plays the session's audio on the default output, in a low-latency AAudio
/// stream fed from a lock-free queue.
pub(crate) struct AAudioSink {
    stream: AudioStream,
    queue: rtrb::Producer<f32>,
    rate: u32,
    /// Set by the stream when its device goes away, as when headphones are
    /// unplugged. The next write opens a new stream on the new default.
    disconnected: Arc<AtomicBool>,
    /// Whether the stream is started. It starts once the queue is half full,
    /// the level the session keeps it at, so it doesn't underrun at first.
    playing: bool,
    paused: bool,
}

impl AAudioSink {
    pub(crate) fn new() -> Result<Self, AudioError> {
        let (stream, queue, disconnected) = open(None)?;
        let rate = u32::try_from(stream.sample_rate()).unwrap_or(48_000);
        log::info!(
            "audio: AAudio at {rate} Hz, {} frames per burst, {:?}",
            stream.frames_per_burst(),
            stream.performance_mode()
        );
        Ok(Self {
            stream,
            queue,
            rate,
            disconnected,
            playing: false,
            paused: false,
        })
    }

    fn capacity(&self) -> usize {
        (f64::from(self.rate) * QUEUE_SECONDS) as usize
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
                self.stream = stream;
                self.queue = queue;
                self.disconnected = disconnected;
                self.playing = false;
            }
            Err(e) => log::error!("can't reopen audio output: {e}"),
        }
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
        let room = self.capacity().saturating_sub(self.queued()) * 2;
        let n = samples.len().min(room).min(self.queue.slots()) & !1;
        let _ = self.queue.push_partial_slice(&samples[..n]);
        if !self.playing && !self.paused && self.queued() >= self.capacity() / 2 {
            match self.stream.request_start() {
                Ok(()) => self.playing = true,
                Err(e) => log::error!("can't start audio output: {e}"),
            }
        }
    }

    fn buffer_status(&self) -> Option<BufferStatus> {
        Some(BufferStatus {
            queued: self.queued(),
            capacity: self.capacity(),
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
