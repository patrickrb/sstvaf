//! The RX thread: owns the audio capture session and the decoder, and pumps
//! `RxEvent`s to whoever is listening (the Tauri layer forwards them to the UI).
//!
//! Capture and decode share one thread because `cpal::Stream` is `!Send` on
//! Windows — it has to be created and dropped on the same thread that owns it.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, Sender};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::Duration;

use crate::audio::{AudioInput, AudioQueue};
use crate::dsp::decoder::Decoder;
use crate::rx::engine::{RxEngine, RxEvent};
use crate::rx::{DROP_LOG_EVERY, POLL_WAIT_MS, QUEUE_CAPACITY, SAMPLE_RATE};

/// A running receiver. Dropping it stops capture and joins the thread.
pub struct RxService {
    stop: Arc<AtomicBool>,
    queue: Arc<AudioQueue>,
    thread: Option<JoinHandle<()>>,
    /// The device actually opened, which may differ from the one requested
    /// (an unplugged device falls back to the system default).
    pub device_name: String,
    pub device_rate: u32,
}

impl RxService {
    /// Open `device_name` (or the default) and start decoding. Blocks until the
    /// audio device is open so a bad device surfaces as an error here rather
    /// than as silence.
    pub fn start(device_name: Option<String>, events: Sender<RxEvent>) -> anyhow::Result<RxService> {
        let stop = Arc::new(AtomicBool::new(false));
        let queue = Arc::new(AudioQueue::new(QUEUE_CAPACITY));
        // The decoder is built here, on the caller's thread, so an allocation
        // failure is reported synchronously.
        let decoder = Decoder::new(SAMPLE_RATE)?;

        let (ready_tx, ready_rx) = channel::<Result<(String, u32), String>>();
        let stop_t = stop.clone();
        let queue_t = queue.clone();

        let thread = std::thread::Builder::new()
            .name("sstvaf-rx".into())
            .spawn(move || {
                // AudioInput owns the cpal stream and must live and die on this
                // thread; it stops capture when dropped at the end of the loop.
                let input = match AudioInput::start(device_name.as_deref(), queue_t.clone()) {
                    Ok(i) => {
                        let _ = ready_tx.send(Ok((i.device_name.clone(), i.device_rate)));
                        i
                    }
                    Err(e) => {
                        let _ = ready_tx.send(Err(e.to_string()));
                        return;
                    }
                };
                run_loop(decoder, queue_t, stop_t, &events);
                drop(input);
            })?;

        // If the thread died before reporting, recv() errors — surface that
        // rather than hanging.
        let (device_name, device_rate) = match ready_rx.recv() {
            Ok(Ok(v)) => v,
            Ok(Err(e)) => {
                let _ = thread.join();
                anyhow::bail!(e);
            }
            Err(_) => {
                let _ = thread.join();
                anyhow::bail!("RX thread failed to start");
            }
        };

        Ok(RxService {
            stop,
            queue,
            thread: Some(thread),
            device_name,
            device_rate,
        })
    }

    pub fn stop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        // Wake the decode loop immediately instead of after one more timeout.
        self.queue.close();
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
    }
}

impl Drop for RxService {
    fn drop(&mut self) {
        self.stop();
    }
}

/// The decode loop: drain audio, feed the engine, forward its events. Split out
/// so it can be driven in tests with a queue that no audio device feeds.
fn run_loop(
    decoder: Decoder,
    queue: Arc<AudioQueue>,
    stop: Arc<AtomicBool>,
    events: &Sender<RxEvent>,
) {
    let mut engine = RxEngine::new(decoder);
    let mut logged_drops = 0u64;
    let _ = events.send(RxEvent::Info(format!("hunting (rate={SAMPLE_RATE} Hz)")));

    while !stop.load(Ordering::Relaxed) {
        let blocks = queue.drain_blocking(Duration::from_millis(POLL_WAIT_MS));
        let out = if blocks.is_empty() {
            engine.poll_only()
        } else {
            engine.feed(&blocks)
        };
        for ev in out {
            // A closed receiver means the app is shutting down.
            if events.send(ev).is_err() {
                return;
            }
        }

        // Report a lagging decoder rather than degrading silently.
        let dropped = queue.dropped();
        if dropped > 0 && (logged_drops == 0 || dropped - logged_drops >= DROP_LOG_EVERY) {
            logged_drops = dropped;
            let _ = events.send(RxEvent::Error(format!(
                "audio queue overflow, dropped={dropped} blocks (decode thread lagging)"
            )));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::rx::RxState;

    /// Drive `run_loop` with a hand-fed queue — no audio hardware involved.
    /// `capacity` is explicit because a test that feeds a whole transmission
    /// must be able to hold it: with a small queue, drop-oldest would discard
    /// audio whenever the machine is slow (CI under coverage instrumentation,
    /// for one), making the decode outcome depend on timing.
    fn spawn_loop(
        capacity: usize,
    ) -> (
        Arc<AudioQueue>,
        Arc<AtomicBool>,
        std::sync::mpsc::Receiver<RxEvent>,
        JoinHandle<()>,
    ) {
        let queue = Arc::new(AudioQueue::new(capacity));
        let stop = Arc::new(AtomicBool::new(false));
        let (tx, rx) = channel();
        let (q, s) = (queue.clone(), stop.clone());
        let t = std::thread::spawn(move || {
            let decoder = Decoder::new(SAMPLE_RATE).expect("decoder");
            run_loop(decoder, q, s, &tx);
        });
        (queue, stop, rx, t)
    }

    #[test]
    fn announces_itself_then_stops_cleanly() {
        let (queue, stop, rx, t) = spawn_loop(8);
        match rx.recv_timeout(Duration::from_secs(5)) {
            Ok(RxEvent::Info(m)) => assert!(m.contains("12000"), "got {m}"),
            other => panic!("expected the startup Info, got {other:?}"),
        }
        stop.store(true, Ordering::Relaxed);
        queue.close();
        t.join().expect("loop thread panicked");
    }

    #[test]
    fn decodes_a_real_transmission_fed_through_the_queue() {
        use crate::dsp::encode::{self, ENCODE_AMPLITUDE};
        use crate::modes::MODES;

        let mode = &MODES[0];
        let image = vec![0xFF20A0FFu32; mode.pixel_count()];
        let samples = encode::encode(mode, &image, SAMPLE_RATE, ENCODE_AMPLITUDE).expect("encode");

        // A queue large enough for the whole transmission (~37 s in 200 ms
        // blocks) so nothing is ever dropped and the result is independent of
        // how fast the decode loop happens to run.
        let (queue, stop, rx, t) = spawn_loop(512);
        // Feed it in the 200 ms blocks the capture worker produces.
        for block in samples.chunks(2400) {
            queue.push(block.to_vec());
        }
        // Trailing silence: the decoder only reports DONE once audio arrives
        // past the final scan line.
        for _ in 0..5 {
            queue.push(vec![0.0; 2400]);
        }
        assert_eq!(queue.dropped(), 0, "test queue was too small");

        // Collect until the image completes or we run out of patience.
        let mut got_rows = false;
        let mut completed = false;
        let deadline = std::time::Instant::now() + Duration::from_secs(20);
        while std::time::Instant::now() < deadline && !completed {
            match rx.recv_timeout(Duration::from_secs(2)) {
                Ok(RxEvent::Rows { .. }) => got_rows = true,
                Ok(RxEvent::State(RxState::Complete { mode, rows, .. })) => {
                    assert_eq!(mode.id, 0);
                    assert_eq!(rows, 240);
                    completed = true;
                }
                Ok(_) => {}
                Err(_) => break,
            }
        }
        stop.store(true, Ordering::Relaxed);
        queue.close();
        let _ = t.join();

        assert!(got_rows, "no rows streamed to the UI");
        assert!(completed, "the transmission never completed");
    }

    #[test]
    fn reports_a_lagging_decoder_once_drops_start() {
        let queue = Arc::new(AudioQueue::new(2));
        let stop = Arc::new(AtomicBool::new(true)); // one pass, then exit
        let (tx, rx) = channel();
        // Overflow the queue before the loop ever runs.
        for _ in 0..5 {
            queue.push(vec![0.0; 10]);
        }
        let decoder = Decoder::new(SAMPLE_RATE).expect("decoder");
        // stop is already set, so run_loop returns after emitting the startup
        // Info without entering the body — drive one iteration explicitly.
        stop.store(false, Ordering::Relaxed);
        let (q, s) = (queue.clone(), stop.clone());
        let t = std::thread::spawn(move || run_loop(decoder, q, s, &tx));

        let mut saw_overflow = false;
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        while std::time::Instant::now() < deadline {
            match rx.recv_timeout(Duration::from_secs(1)) {
                Ok(RxEvent::Error(m)) if m.contains("overflow") => {
                    saw_overflow = true;
                    break;
                }
                Ok(_) => {}
                Err(_) => break,
            }
        }
        stop.store(true, Ordering::Relaxed);
        queue.close();
        let _ = t.join();
        assert!(saw_overflow, "queue drops were never reported");
    }
}
