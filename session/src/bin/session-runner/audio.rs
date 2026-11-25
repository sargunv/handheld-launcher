//! Audio output through cpal.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{FromSample, SampleFormat, SizedSample, StreamConfig};
use handheld_session::{AudioSink, BufferStatus};

/// How much audio the queue holds, in seconds. The session keeps it half full.
const QUEUE_SECONDS: f64 = 0.1;

/// Queued stereo samples, shared with the output callback.
type Queue = Arc<Mutex<VecDeque<f32>>>;

/// Plays the session's audio on the default output device.
pub struct CpalSink {
    queue: Queue,
    rate: u32,
    capacity: usize,
    stream: cpal::Stream,
    /// The stream starts once the queue is half full, the level the session
    /// keeps it at, so it doesn't underrun while the core starts.
    playing: bool,
}

impl CpalSink {
    pub fn new() -> Result<Self, String> {
        let host = cpal::default_host();
        let device = host
            .default_output_device()
            .ok_or("no audio output device")?;
        let supported = device.default_output_config().map_err(|e| e.to_string())?;
        let format = supported.sample_format();
        let config: StreamConfig = supported.into();
        let rate = config.sample_rate;
        let capacity = (f64::from(rate) * QUEUE_SECONDS) as usize;
        let queue: Queue = Arc::new(Mutex::new(VecDeque::with_capacity(capacity * 2)));
        let stream = match format {
            SampleFormat::F32 => build::<f32>(&device, &config, queue.clone()),
            SampleFormat::I16 => build::<i16>(&device, &config, queue.clone()),
            SampleFormat::I32 => build::<i32>(&device, &config, queue.clone()),
            SampleFormat::U16 => build::<u16>(&device, &config, queue.clone()),
            other => return Err(format!("unsupported sample format {other}")),
        }?;
        log::info!("audio: {} Hz, {} channels, {format}", rate, config.channels);
        Ok(Self {
            queue,
            rate,
            capacity,
            stream,
            playing: false,
        })
    }
}

fn build<T>(
    device: &cpal::Device,
    config: &StreamConfig,
    queue: Queue,
) -> Result<cpal::Stream, String>
where
    T: SizedSample + FromSample<f32>,
{
    let channels = usize::from(config.channels);
    device
        .build_output_stream(
            *config,
            move |data: &mut [T], _| {
                let mut queue = queue.lock().unwrap_or_else(|e| e.into_inner());
                for frame in data.chunks_mut(channels) {
                    // An underrun plays silence.
                    let left = queue.pop_front().unwrap_or(0.0);
                    let right = queue.pop_front().unwrap_or(left);
                    if let [mono] = frame {
                        *mono = T::from_sample((left + right) / 2.0);
                        continue;
                    }
                    for (i, sample) in frame.iter_mut().enumerate() {
                        let value = match i {
                            0 => left,
                            1 => right,
                            _ => 0.0,
                        };
                        *sample = T::from_sample(value);
                    }
                }
            },
            |e| log::warn!("audio stream: {e}"),
            None,
        )
        .map_err(|e| e.to_string())
}

impl AudioSink for CpalSink {
    fn sample_rate(&self) -> u32 {
        self.rate
    }

    fn write(&mut self, samples: &[f32]) {
        let mut queue = self.queue.lock().unwrap_or_else(|e| e.into_inner());
        queue.extend(samples);
        // Drop the oldest audio rather than fall further behind.
        let excess = queue.len().saturating_sub(self.capacity * 2);
        queue.drain(..excess);
        let half_full = queue.len() / 2 >= self.capacity / 2;
        drop(queue);
        if !self.playing && half_full {
            self.playing = true;
            if let Err(e) = self.stream.play() {
                log::warn!("can't start audio: {e}");
            }
        }
    }

    fn buffer_status(&self) -> Option<BufferStatus> {
        let queued = self.queue.lock().unwrap_or_else(|e| e.into_inner()).len() / 2;
        Some(BufferStatus {
            queued,
            capacity: self.capacity,
        })
    }
}
