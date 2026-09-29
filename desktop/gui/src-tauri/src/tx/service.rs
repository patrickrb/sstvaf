//! The TX thread: encode the image with the native codec at the output
//! device's own sample rate, then play the waveform through cpal.
//!
//! Everything runs on one thread because `cpal::Stream` is `!Send` on Windows
//! (same constraint as `rx::service`). Encoding at the device's native rate —
//! rather than a fixed rate resampled later — means the buffer the codec
//! produced is the buffer the device clocks out, sample for sample: nothing
//! downstream can clip the leader or trim the tail (CLAUDE.md, "TX audio
//! pipeline").

use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::mpsc::{channel, Sender};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::Duration;

use cpal::traits::{DeviceTrait, StreamTrait};
use cpal::{FromSample, SampleFormat, SizedSample};

use crate::audio::devices::find_output_device;
use crate::dsp::encode::{self, ENCODE_AMPLITUDE};
use crate::modes::mode_by_id;
use crate::tx::engine::{fill_frames, progress, TxEvent};
use crate::tx::{DRAIN_MS, PROGRESS_TICK_MS};

/// A transmission in flight. Dropping it stops playback and joins the thread.
pub struct TxService {
    stop: Arc<AtomicBool>,
    finished: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
    /// The device actually opened (an unplugged device falls back to default).
    pub device_name: String,
    /// The rate the waveform was encoded at — the device's native rate.
    pub sample_rate: u32,
    pub total_samples: usize,
}

impl TxService {
    /// Encode `pixels` (0xAARRGGBB, exactly the mode's dimensions) for
    /// `mode_id` and start playing on `device_name` (or the system default).
    /// Blocks until the audio stream is running, so a bad device, mode or
    /// image size surfaces as an error here rather than as silence on the air.
    pub fn start(
        device_name: Option<String>,
        mode_id: i32,
        pixels: Vec<u32>,
        events: Sender<TxEvent>,
    ) -> anyhow::Result<TxService> {
        let mode = *mode_by_id(mode_id)
            .ok_or_else(|| anyhow::anyhow!("unknown SSTV mode id {mode_id}"))?;

        let stop = Arc::new(AtomicBool::new(false));
        let finished = Arc::new(AtomicBool::new(false));
        let (ready_tx, ready_rx) = channel::<Result<(String, u32, usize), String>>();
        let stop_t = stop.clone();
        let finished_t = finished.clone();

        let thread = std::thread::Builder::new()
            .name("sstvaf-tx".into())
            .spawn(move || {
                // Set by `err_fn` when the stream faults (device unplugged
                // mid-TX); `run_loop` polls it so a dead stream can't leave
                // the loop spinning forever on a frozen `played`.
                let errored = Arc::new(AtomicBool::new(false));
                let result =
                    open_and_play(device_name.as_deref(), &mode, &pixels, &events, &errored);
                match result {
                    Ok((stream, dev_name, rate, played)) => {
                        let total = played.1;
                        let _ = ready_tx.send(Ok((dev_name, rate, total)));
                        run_loop(
                            total,
                            rate,
                            &played.0,
                            &stop_t,
                            &errored,
                            &events,
                            Duration::from_millis(PROGRESS_TICK_MS),
                            Duration::from_millis(DRAIN_MS),
                        );
                        // The stream must be dropped on this thread (cpal).
                        drop(stream);
                    }
                    Err(e) => {
                        let _ = ready_tx.send(Err(e.to_string()));
                    }
                }
                finished_t.store(true, Ordering::Relaxed);
            })?;

        // If the thread died before reporting, recv() errors — surface that
        // rather than hanging.
        let (device_name, sample_rate, total_samples) = match ready_rx.recv() {
            Ok(Ok(v)) => v,
            Ok(Err(e)) => {
                let _ = thread.join();
                anyhow::bail!(e);
            }
            Err(_) => {
                let _ = thread.join();
                anyhow::bail!("TX thread failed to start");
            }
        };

        Ok(TxService {
            stop,
            finished,
            thread: Some(thread),
            device_name,
            sample_rate,
            total_samples,
        })
    }

    /// Still playing? False once the waveform has fully drained (or the
    /// transmission was cancelled), even while the service object lingers in
    /// the app state slot.
    pub fn is_running(&self) -> bool {
        !self.finished.load(Ordering::Relaxed)
    }

    pub fn stop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
    }
}

impl Drop for TxService {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Open the output device, encode at its native rate, and start the stream.
/// Returns the live stream plus (played-position, total) for the loop.
#[allow(clippy::type_complexity)]
fn open_and_play(
    device_name: Option<&str>,
    mode: &crate::modes::ModeInfo,
    pixels: &[u32],
    events: &Sender<TxEvent>,
    errored: &Arc<AtomicBool>,
) -> anyhow::Result<(cpal::Stream, String, u32, (Arc<AtomicUsize>, usize))> {
    let device = find_output_device(device_name)
        .ok_or_else(|| anyhow::anyhow!("no output audio device available"))?;
    let dev_name = device.name().unwrap_or_else(|_| "unknown".into());
    let supported = device.default_output_config()?;
    let sample_format = supported.sample_format();
    let config: cpal::StreamConfig = supported.config();
    let rate = config.sample_rate.0;

    // Encode the entire transmission before the stream starts: the realtime
    // callback then only copies samples, so it can never stall mid-leader.
    let samples: Arc<Vec<f32>> = Arc::new(
        encode::encode(mode, pixels, rate, ENCODE_AMPLITUDE)
            .map_err(|e| anyhow::anyhow!("encode failed: {e}"))?,
    );
    let total = samples.len();
    let played = Arc::new(AtomicUsize::new(0));

    // cpal calls this on its own thread when the stream faults (device
    // unplugged mid-transmission). Report it upward instead of into the void,
    // and flag the fault so `run_loop` bails out — a dead stream never
    // advances `played`, so the loop would otherwise spin forever.
    let err_events = events.clone();
    let err_errored = errored.clone();
    let err_fn = move |e| {
        let _ = err_events.send(TxEvent::Error(format!("audio output stream error: {e}")));
        err_errored.store(true, Ordering::Relaxed);
    };

    let stream = match sample_format {
        SampleFormat::F32 => build_stream::<f32>(&device, &config, samples, played.clone(), err_fn)?,
        SampleFormat::I16 => build_stream::<i16>(&device, &config, samples, played.clone(), err_fn)?,
        SampleFormat::U16 => build_stream::<u16>(&device, &config, samples, played.clone(), err_fn)?,
        other => anyhow::bail!("unsupported output sample format: {other:?}"),
    };
    stream.play()?;

    Ok((stream, dev_name, rate, (played, total)))
}

/// One arm per sample format cpal may hand us; each replicates the mono
/// waveform across the device's channels via `fill_frames`.
fn build_stream<T>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    samples: Arc<Vec<f32>>,
    played: Arc<AtomicUsize>,
    err_fn: impl Fn(cpal::StreamError) + Send + 'static,
) -> Result<cpal::Stream, cpal::BuildStreamError>
where
    T: SizedSample + FromSample<f32>,
{
    let channels = config.channels as usize;
    device.build_output_stream(
        config,
        move |out: &mut [T], _| {
            // Only this callback writes `played`; the progress loop reads it.
            let pos = played.load(Ordering::Relaxed);
            let new = fill_frames(&samples, pos, out, channels);
            played.store(new, Ordering::Relaxed);
        },
        err_fn,
        None,
    )
}

/// The progress loop: watch the playback position, report it, and decide how
/// the transmission ends. Split out so it can be driven in tests with atomics
/// a test thread bumps — no audio device involved.
///
/// `drain` is slept *after* the final sample has been handed to the device,
/// covering its internal buffer, so dropping the stream can never cut the
/// tail of the transmission mid-symbol.
fn run_loop(
    total_samples: usize,
    sample_rate: u32,
    played: &AtomicUsize,
    stop: &AtomicBool,
    errored: &AtomicBool,
    events: &Sender<TxEvent>,
    tick: Duration,
    drain: Duration,
) {
    let total_seconds = if sample_rate > 0 {
        total_samples as f64 / sample_rate as f64
    } else {
        0.0
    };
    loop {
        let pos = played.load(Ordering::Relaxed);
        let (elapsed_seconds, fraction) = progress(pos, total_samples, sample_rate);
        // Stop wins over completion: the operator asked for silence *now*.
        if stop.load(Ordering::Relaxed) {
            let _ = events.send(TxEvent::Cancelled { elapsed_seconds });
            return;
        }
        // The stream faulted: `err_fn` already sent TxEvent::Error, so just
        // exit — no Complete, no drain (there is nothing left playing), which
        // lets the thread finish and `is_running()` go false.
        if errored.load(Ordering::Relaxed) {
            return;
        }
        let _ = events.send(TxEvent::Progress {
            elapsed_seconds,
            total_seconds,
            fraction,
        });
        if pos >= total_samples {
            // Every sample is in the device's hands; wait out its buffer so
            // the tail is audible before the stream is torn down.
            std::thread::sleep(drain);
            let _ = events.send(TxEvent::Complete);
            return;
        }
        std::thread::sleep(tick);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc::Receiver;

    /// Drive `run_loop` with hand-bumped atomics — no audio hardware.
    fn spawn_loop(
        total: usize,
        rate: u32,
    ) -> (
        Arc<AtomicUsize>,
        Arc<AtomicBool>,
        Arc<AtomicBool>,
        Receiver<TxEvent>,
        JoinHandle<()>,
    ) {
        let played = Arc::new(AtomicUsize::new(0));
        let stop = Arc::new(AtomicBool::new(false));
        let errored = Arc::new(AtomicBool::new(false));
        let (tx, rx) = channel();
        let (p, s, e) = (played.clone(), stop.clone(), errored.clone());
        let t = std::thread::spawn(move || {
            run_loop(
                total,
                rate,
                &p,
                &s,
                &e,
                &tx,
                Duration::from_millis(2),
                Duration::from_millis(2),
            );
        });
        (played, stop, errored, rx, t)
    }

    fn drain_events(rx: &Receiver<TxEvent>) -> Vec<TxEvent> {
        let mut out = Vec::new();
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        while std::time::Instant::now() < deadline {
            match rx.recv_timeout(Duration::from_millis(100)) {
                Ok(ev) => {
                    let terminal = matches!(ev, TxEvent::Complete | TxEvent::Cancelled { .. });
                    out.push(ev);
                    if terminal {
                        break;
                    }
                }
                Err(_) => break,
            }
        }
        out
    }

    #[test]
    fn a_played_out_waveform_completes_with_monotonic_progress() {
        let (played, _stop, _errored, rx, t) = spawn_loop(48_000, 48_000);
        // Simulate the audio callback advancing through the buffer.
        for chunk in [12_000usize, 24_000, 48_000] {
            std::thread::sleep(Duration::from_millis(10));
            played.store(chunk, Ordering::Relaxed);
        }
        let events = drain_events(&rx);
        t.join().expect("loop thread panicked");

        assert!(
            matches!(events.last(), Some(TxEvent::Complete)),
            "expected Complete last, got {events:?}"
        );
        let fractions: Vec<f64> = events
            .iter()
            .filter_map(|e| match e {
                TxEvent::Progress { fraction, .. } => Some(*fraction),
                _ => None,
            })
            .collect();
        assert!(!fractions.is_empty(), "no progress was reported");
        assert!(
            fractions.windows(2).all(|w| w[0] <= w[1]),
            "progress went backwards: {fractions:?}"
        );
        assert!(*fractions.last().unwrap() <= 1.0);
    }

    #[test]
    fn stopping_mid_transmission_reports_cancelled_at_the_cut_point() {
        let (played, stop, _errored, rx, t) = spawn_loop(48_000, 48_000);
        played.store(24_000, Ordering::Relaxed);
        std::thread::sleep(Duration::from_millis(10));
        stop.store(true, Ordering::Relaxed);
        let events = drain_events(&rx);
        t.join().expect("loop thread panicked");

        match events.last() {
            Some(TxEvent::Cancelled { elapsed_seconds }) => {
                assert!(
                    (*elapsed_seconds - 0.5).abs() < 0.01,
                    "cancelled at {elapsed_seconds}, expected ~0.5 s"
                );
            }
            other => panic!("expected Cancelled last, got {other:?}"),
        }
    }

    #[test]
    fn a_stream_error_ends_the_loop_without_a_complete_event() {
        // A dead output device never advances `played`; the errored flag
        // (set by cpal's err_fn) must end the loop — otherwise it spins
        // forever and `is_tx_running` stays true until a manual Stop.
        let (_played, _stop, errored, rx, t) = spawn_loop(48_000, 48_000);
        std::thread::sleep(Duration::from_millis(10));
        errored.store(true, Ordering::Relaxed);
        t.join().expect("loop thread never terminated");
        let events = drain_events(&rx);
        assert!(
            !events
                .iter()
                .any(|e| matches!(e, TxEvent::Complete | TxEvent::Cancelled { .. })),
            "an errored TX must not report Complete/Cancelled: {events:?}"
        );
    }

    #[test]
    fn progress_reports_the_total_duration_every_time() {
        let (played, _stop, _errored, rx, t) = spawn_loop(24_000, 12_000);
        played.store(24_000, Ordering::Relaxed);
        let events = drain_events(&rx);
        t.join().expect("loop thread panicked");
        for e in &events {
            if let TxEvent::Progress { total_seconds, .. } = e {
                assert!((*total_seconds - 2.0).abs() < 1e-9);
            }
        }
    }

    #[test]
    fn encode_at_a_device_rate_matches_the_mode_duration() {
        // The service encodes at whatever rate the output device runs;
        // sanity-check the length arithmetic at a typical 48 kHz.
        use crate::modes::MODES;
        let mode = &MODES[0];
        let n = encode::num_samples(mode, 48_000).expect("num_samples");
        let seconds = n as f64 / 48_000.0;
        assert!(
            (seconds - mode.tx_seconds).abs() < 0.05,
            "{}: {seconds:.3} s at 48 kHz vs table {:.3} s",
            mode.name,
            mode.tx_seconds
        );
    }

    #[test]
    fn start_rejects_an_unknown_mode_synchronously() {
        let (tx, _rx) = channel();
        let err = match TxService::start(None, 99, vec![0; 4], tx) {
            Ok(_) => panic!("mode 99 was accepted"),
            Err(e) => e,
        };
        assert!(err.to_string().contains("unknown SSTV mode"), "got {err}");
    }
}
