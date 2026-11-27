//! Frame pacing: when the session runs its next frame.
//!
//! When the session knows the display's vsync and the core's frame rate is
//! close to a whole fraction of the display's, frames start on the display's
//! vsyncs, every second one for a 60 fps core on a 120 Hz display. The core
//! then runs slightly faster or slower than its own rate, and the audio is
//! resampled to match. Otherwise a timer runs frames at the core's own rate.

use std::collections::VecDeque;
use std::time::{Duration, Instant};

/// How much faster or slower than its own frame rate a core may run so that
/// its frames land evenly on the display's refreshes. The audio is resampled
/// to match, so this is also the most its pitch changes. It covers the cores
/// of 60 Hz consoles, such as the Game Boy at 59.73 Hz, on displays a little
/// off 60 or 120 Hz, as the AYN Thor's are at about 118 Hz, but not PAL's
/// 50 Hz on a 60 Hz display.
pub(crate) const MAX_SPEED_CHANGE: f64 = 0.02;

/// How far behind schedule the session may fall before it gives up catching
/// up and starts counting from now.
const MAX_LAG_FRAMES: u32 = 4;

/// The display's refresh timing: the time of one vsync, and the period
/// between them.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Vsync {
    pub(crate) at: Instant,
    pub(crate) period: Duration,
}

impl Vsync {
    /// How many whole periods `t` is after `self.at`, rounded down, and
    /// negative before it.
    fn index(&self, t: Instant) -> i64 {
        let period = self.period.as_nanos() as i128;
        let offset = match t.checked_duration_since(self.at) {
            Some(d) => d.as_nanos() as i128,
            None => -(self.at.duration_since(t).as_nanos() as i128),
        };
        offset.div_euclid(period) as i64
    }

    /// The vsync `index` periods after `self.at`.
    fn time(&self, index: i64) -> Instant {
        let offset = self.period * index.unsigned_abs() as u32;
        if index >= 0 {
            self.at + offset
        } else {
            self.at - offset
        }
    }

    /// The vsync nearest to `t`.
    fn nearest(&self, t: Instant) -> Instant {
        self.time(self.index(t + self.period / 2))
    }
}

/// Learns the display's vsync timing from the times Choreographer gives its
/// vsyncs.
///
/// Those times are only roughly on the display's grid: off by up to a
/// millisecond, now and then twice for one vsync, and with a period that can
/// differ from the one the display reports, as on the AYN Thor, whose "120
/// Hz" panels refresh at about 118 Hz. So the estimator counts how many
/// vsyncs apart they are, and fits a line through the last couple of seconds
/// of them.
#[derive(Debug, Default)]
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
pub(crate) struct VsyncEstimator {
    last: Option<Instant>,
    /// The latest gaps between successive vsyncs, oldest first.
    gaps: VecDeque<Duration>,
    /// The latest vsyncs, oldest first: their times, and how many vsyncs
    /// after the first they came.
    samples: VecDeque<(Instant, i64)>,
}

/// How many gaps [`VsyncEstimator`] takes the median of, to count the
/// vsyncs between two of them.
const VSYNC_GAPS: usize = 15;

/// How many vsyncs [`VsyncEstimator`] fits its line through.
const VSYNC_SAMPLES: usize = 240;

/// How many vsyncs [`VsyncEstimator`] needs before it trusts its line.
const MIN_VSYNC_SAMPLES: usize = 8;

/// A gap between vsyncs past which the estimator starts over, as after its
/// callbacks pause.
const MAX_VSYNC_GAP: Duration = Duration::from_millis(100);

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
impl VsyncEstimator {
    /// Records a vsync at `at`.
    pub(crate) fn tick(&mut self, at: Instant) {
        let Some(last) = self.last.replace(at) else {
            return;
        };
        let gap = at.checked_duration_since(last).unwrap_or_default();
        if gap.is_zero() || gap > MAX_VSYNC_GAP {
            self.gaps.clear();
            self.samples.clear();
            return;
        }
        if self.gaps.len() == VSYNC_GAPS {
            self.gaps.pop_front();
        }
        self.gaps.push_back(gap);
        if self.gaps.len() < 3 {
            return;
        }
        let mut gaps: Vec<Duration> = self.gaps.iter().copied().collect();
        gaps.sort();
        let typical = gaps[gaps.len() / 2].as_secs_f64();
        let index = match self.samples.back() {
            None => 0,
            Some(&(time, index)) => {
                let vsyncs = (at.duration_since(time).as_secs_f64() / typical).round() as i64;
                if vsyncs < 1 {
                    // The same vsync again.
                    return;
                }
                index + vsyncs
            }
        };
        if self.samples.len() == VSYNC_SAMPLES {
            self.samples.pop_front();
        }
        self.samples.push_back((at, index));
    }

    /// The vsync timing, once enough vsyncs have been seen to know it: the
    /// least-squares line through the latest vsyncs, by their count.
    pub(crate) fn vsync(&self) -> Option<Vsync> {
        if self.samples.len() < MIN_VSYNC_SAMPLES {
            return None;
        }
        let &(first, _) = self.samples.front()?;
        let &(_, last_index) = self.samples.back()?;
        let n = self.samples.len() as f64;
        let points = || {
            self.samples
                .iter()
                .map(move |&(t, k)| (k as f64, t.duration_since(first).as_secs_f64()))
        };
        let (mean_k, mean_t) =
            points().fold((0.0, 0.0), |(k, t), (pk, pt)| (k + pk / n, t + pt / n));
        let (covariance, variance) = points().fold((0.0, 0.0), |(c, v), (k, t)| {
            (
                c + (k - mean_k) * (t - mean_t),
                v + (k - mean_k) * (k - mean_k),
            )
        });
        let period = covariance / variance;
        if !(period.is_finite() && period > 0.0) {
            return None;
        }
        let at = mean_t + period * (last_index as f64 - mean_k);
        Some(Vsync {
            at: first + Duration::from_secs_f64(at.max(0.0)),
            period: Duration::from_secs_f64(period),
        })
    }
}

/// How many vsyncs each frame of a core at `fps` stays on a display with
/// vsync `period`, if the core runs within [`MAX_SPEED_CHANGE`] of its own
/// rate that way.
pub(crate) fn vsyncs_per_frame(fps: f64, period: Duration) -> Option<u32> {
    if !(fps.is_finite() && fps > 1.0) || period.is_zero() {
        return None;
    }
    let refresh = 1.0 / period.as_secs_f64();
    let vsyncs = (refresh / fps).round();
    if vsyncs < 1.0 {
        return None;
    }
    let speed = refresh / vsyncs / fps;
    ((speed - 1.0).abs() <= MAX_SPEED_CHANGE).then_some(vsyncs as u32)
}

/// The core's frame period at `fps`, or at 60 frames a second if it gives
/// no usable rate.
fn core_period(fps: f64) -> Duration {
    if fps.is_finite() && fps > 1.0 {
        Duration::from_secs_f64(1.0 / fps)
    } else {
        Duration::from_secs_f64(1.0 / 60.0)
    }
}

/// When to run the next frame, and how fast that runs the core.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct Schedule {
    pub(crate) due: Instant,
    /// The rate frames run at, over the core's own.
    pub(crate) speed: f64,
    /// How many vsyncs each frame stays on the display, or `None` when a
    /// timer paces frames.
    pub(crate) vsyncs: Option<u32>,
}

/// Schedules the frame after one that was due at `due` and finished at
/// `now`, for a core at `fps`, on the display's `vsync` if it's known and
/// suits the core.
pub(crate) fn next_frame(due: Instant, now: Instant, fps: f64, vsync: Option<Vsync>) -> Schedule {
    if let Some(vsync) = vsync
        && let Some(vsyncs) = vsyncs_per_frame(fps, vsync.period)
    {
        let period = vsync.period * vsyncs;
        let mut next = vsync.nearest(due + period);
        if now > next + period * MAX_LAG_FRAMES {
            next = vsync.time(vsync.index(now) + 1);
        }
        return Schedule {
            due: next,
            speed: 1.0 / period.as_secs_f64() / fps,
            vsyncs: Some(vsyncs),
        };
    }
    Schedule {
        due: timer_next_frame(due, now, fps),
        speed: 1.0,
        vsyncs: None,
    }
}

/// When a timer runs the frame after one that was due at `due` and finished
/// at `now`, for a core at `fps`.
pub fn timer_next_frame(due: Instant, now: Instant, fps: f64) -> Instant {
    let period = core_period(fps);
    let next = due + period;
    if now > next + period * MAX_LAG_FRAMES {
        now
    } else {
        next
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const PERIOD_120: Duration = Duration::from_nanos(8_333_333);

    fn vsync(at: Instant) -> Vsync {
        Vsync {
            at,
            period: PERIOD_120,
        }
    }

    /// The AYN Thor's vsyncs as Choreographer times them: a 8.477 ms period,
    /// with each vsync this far off it in a repeating pattern, in
    /// microseconds, and one of them timed twice.
    const THOR_PERIOD: Duration = Duration::from_nanos(8_476_764);
    const THOR_OFFSETS: [i64; 8] = [-280, -430, 720, 10, 150, 150, 0, -140];

    fn thor_vsyncs(start: Instant, count: u32) -> Vec<Instant> {
        let mut times: Vec<Instant> = (0..count)
            .map(|i| {
                let offset = THOR_OFFSETS[i as usize % THOR_OFFSETS.len()];
                let t = start + Duration::from_millis(1) + THOR_PERIOD * i;
                if offset >= 0 {
                    t + Duration::from_micros(offset as u64)
                } else {
                    t - Duration::from_micros(offset.unsigned_abs())
                }
            })
            .collect();
        times.insert(50, times[49] + Duration::from_micros(1100));
        times
    }

    #[test]
    fn fits_the_vsync_grid_through_jittery_times() {
        let start = Instant::now();
        let mut estimator = VsyncEstimator::default();
        assert_eq!(estimator.vsync(), None);
        for t in thor_vsyncs(start, 300) {
            estimator.tick(t);
        }
        let vsync = estimator.vsync().unwrap();
        let error = vsync.period.as_secs_f64() - THOR_PERIOD.as_secs_f64();
        assert!(error.abs() < 2e-6, "period {:?}", vsync.period);
        // The latest vsync, on the grid.
        let truth = start + Duration::from_millis(1) + THOR_PERIOD * 299;
        let error = vsync.at.max(truth) - vsync.at.min(truth);
        assert!(error < Duration::from_micros(200), "off by {error:?}");
    }

    #[test]
    fn counts_missed_vsyncs() {
        let start = Instant::now();
        let mut estimator = VsyncEstimator::default();
        // Gaps of 1, 1, 1, 2, 3, 1, 2, 1, 1, 1 and 4 periods.
        let times = [0, 1, 2, 3, 5, 8, 9, 11, 12, 13, 14, 18].map(|i| start + PERIOD_120 * i);
        for t in times {
            estimator.tick(t);
        }
        let vsync = estimator.vsync().unwrap();
        assert_eq!(vsync.at, times[11]);
        let error = vsync.period.as_nanos().abs_diff(PERIOD_120.as_nanos());
        assert!(error < 10, "period {:?}", vsync.period);
    }

    #[test]
    fn starts_over_after_a_gap() {
        let start = Instant::now();
        let mut estimator = VsyncEstimator::default();
        for i in 0..20 {
            estimator.tick(start + PERIOD_120 * i);
        }
        assert!(estimator.vsync().is_some());
        estimator.tick(start + Duration::from_secs(1));
        assert_eq!(estimator.vsync(), None);
    }

    #[test]
    fn shows_60_hz_cores_on_every_second_vsync_at_120_hz() {
        assert_eq!(vsyncs_per_frame(60.0, PERIOD_120), Some(2));
        // The Game Boy.
        assert_eq!(vsyncs_per_frame(59.7275, PERIOD_120), Some(2));
        assert_eq!(vsyncs_per_frame(59.7275, PERIOD_120 * 2), Some(1));
        // The NES and SNES.
        assert_eq!(vsyncs_per_frame(60.0988, PERIOD_120), Some(2));
        // The Thor.
        assert_eq!(vsyncs_per_frame(59.7275, THOR_PERIOD), Some(2));
        assert_eq!(vsyncs_per_frame(60.0988, THOR_PERIOD), Some(2));
        // PAL on a 60 Hz display, and a core faster than the display.
        assert_eq!(vsyncs_per_frame(50.0, PERIOD_120 * 2), None);
        assert_eq!(vsyncs_per_frame(120.0, PERIOD_120 * 2), None);
        assert_eq!(vsyncs_per_frame(f64::NAN, PERIOD_120), None);
    }

    #[test]
    fn paces_frames_on_vsyncs() {
        let start = Instant::now();
        let v = vsync(start);
        // A frame due on a vsync, finished soon after: the next one is two
        // vsyncs later.
        let s = next_frame(start, start + Duration::from_millis(2), 59.7275, Some(v));
        assert_eq!(s.due, start + PERIOD_120 * 2);
        assert!((s.speed - 60.0 / 59.7275).abs() < 1e-4, "{}", s.speed);
        // Due slightly off the vsync grid, as after the period estimate
        // moved: it snaps back to the nearest vsync.
        let off = start + Duration::from_micros(700);
        let s = next_frame(off, off, 59.7275, Some(v));
        assert_eq!(s.due, start + PERIOD_120 * 2);
        // A vsync anchor after the frame works too.
        let s = next_frame(start, start, 60.0, Some(vsync(start + PERIOD_120 * 10)));
        assert_eq!(s.due, start + PERIOD_120 * 2);
    }

    #[test]
    fn catches_up_on_vsyncs_or_starts_over() {
        let start = Instant::now();
        let v = vsync(start);
        // A frame that ran long: the next is still due on schedule.
        let late = start + PERIOD_120 * 3;
        let s = next_frame(start, late, 60.0, Some(v));
        assert_eq!(s.due, start + PERIOD_120 * 2);
        // Far behind: the next is due on the next vsync.
        let stalled = start + Duration::from_secs(1) + Duration::from_millis(1);
        let s = next_frame(start, stalled, 60.0, Some(v));
        assert_eq!(s.due, start + PERIOD_120 * 121);
    }

    #[test]
    fn paces_by_timer_without_a_suitable_vsync() {
        let start = Instant::now();
        let period = Duration::from_secs_f64(1.0 / 50.0);
        let s = next_frame(start, start, 50.0, Some(vsync(start)));
        assert_eq!(
            s,
            Schedule {
                due: start + period,
                speed: 1.0,
                vsyncs: None
            }
        );
        let s = next_frame(start, start, 50.0, None);
        assert_eq!(s.due, start + period);
        let stalled = start + Duration::from_secs(1);
        assert_eq!(next_frame(start, stalled, 50.0, None).due, stalled);
    }
}
