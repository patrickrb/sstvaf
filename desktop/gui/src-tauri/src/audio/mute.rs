//! Keeping the receiver from hearing the app's own transmission.
//!
//! Nothing used to stop the receiver listening while the app transmits. When
//! the input device hears the output (a laptop's speaker and microphone, a
//! rig interface with its monitor on) the picture going out arrives on the
//! capture, is decoded like any other, and is auto-saved as a received one.
//! The Android app had the same fault (issue #153); this is the same fix:
//! `TxMuteGate` there, `SstvSignalListener.setTxActiveCheck`.
//!
//! [`TxActive`] is the one shared fact, "the app is on the air": the
//! transmitter raises it, the capture hand-off ([`AudioQueue`]) reads it for
//! every block and, while [`TxMuteGate`] says so, queues silence of the same
//! length in place of the audio.
//!
//! [`AudioQueue`]: super::queue::AudioQueue

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

/// How much capture audio stays muted after a transmission ends. A block
/// delivered just after the transmitter reports idle was recorded while it
/// was still on the air (capture latency), and the rig's switch back to
/// receive and the room both take a moment to go quiet.
pub const TX_MUTE_TAIL_MS: u32 = 1000;

/// "The app is on the air." Cheap to clone; every clone is the same flag.
///
/// It is a count of the transmissions holding it up, not a bool: one that is
/// still closing its stream must not lower the flag for the next one.
#[derive(Clone, Debug, Default)]
pub struct TxActive(Arc<AtomicUsize>);

impl TxActive {
    pub fn new() -> TxActive {
        TxActive::default()
    }

    pub fn get(&self) -> bool {
        self.0.load(Ordering::Relaxed) > 0
    }

    /// Raise the flag until the returned guard is dropped. Dropping covers
    /// every way out, a panic included: a transmit thread that dies must not
    /// leave the receiver muted for the rest of the session.
    #[must_use = "the flag is lowered as soon as the guard is dropped"]
    pub fn hold(&self) -> OnAir {
        self.0.fetch_add(1, Ordering::Relaxed);
        OnAir(self.0.clone())
    }

    /// Run `f` with the flag raised.
    #[cfg(test)]
    pub fn on_air<T>(&self, f: impl FnOnce() -> T) -> T {
        let _held = self.hold();
        f()
    }
}

/// Keeps [`TxActive`] raised while it lives.
#[derive(Debug)]
pub struct OnAir(Arc<AtomicUsize>);

impl Drop for OnAir {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::Relaxed);
    }
}

/// Decides, capture block by capture block, whether the receiver may hear the
/// audio or must be fed silence.
///
/// The tail is counted in samples delivered, not in wall-clock time. That is
/// deterministic in tests, and it is the safe side when capture stalls: audio
/// the device was holding from the transmission is still muted whenever it
/// finally arrives.
#[derive(Debug)]
pub struct TxMuteGate {
    tail_samples: u64,
    tail_left: u64,
}

impl TxMuteGate {
    pub fn new(sample_rate: u32, tail_ms: u32) -> TxMuteGate {
        TxMuteGate {
            tail_samples: sample_rate as u64 * tail_ms as u64 / 1000,
            tail_left: 0,
        }
    }

    /// Account for one capture block of `samples` samples, delivered while the
    /// app is (`tx_active`) or is not transmitting. True when that block must
    /// be replaced with silence. A block the tail only partly covers is muted
    /// whole.
    pub fn mutes(&mut self, tx_active: bool, samples: usize) -> bool {
        if tx_active {
            self.tail_left = self.tail_samples;
            return true;
        }
        if self.tail_left > 0 {
            self.tail_left = self.tail_left.saturating_sub(samples as u64);
            return true;
        }
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 12 kHz capture in the worker's 200 ms (2400-sample) blocks.
    const BLOCK: usize = 2400;

    #[test]
    fn passes_audio_while_the_app_is_not_transmitting() {
        let mut gate = TxMuteGate::new(12_000, TX_MUTE_TAIL_MS);
        for _ in 0..10 {
            assert!(!gate.mutes(false, BLOCK));
        }
    }

    #[test]
    fn mutes_from_the_first_block_of_a_transmission() {
        let mut gate = TxMuteGate::new(12_000, TX_MUTE_TAIL_MS);
        assert!(!gate.mutes(false, BLOCK));
        for _ in 0..50 {
            assert!(gate.mutes(true, BLOCK));
        }
    }

    #[test]
    fn stays_muted_for_one_second_of_audio_after_the_transmission_ends() {
        let mut gate = TxMuteGate::new(12_000, TX_MUTE_TAIL_MS);
        gate.mutes(true, BLOCK);
        // 1000 ms tail = five 200 ms blocks still muted...
        for _ in 0..5 {
            assert!(gate.mutes(false, BLOCK));
        }
        // ...and the sixth is heard.
        for _ in 0..3 {
            assert!(!gate.mutes(false, BLOCK));
        }
    }

    #[test]
    fn a_block_the_tail_only_partly_covers_is_muted_whole() {
        let mut gate = TxMuteGate::new(12_000, 300); // 3600 samples
        gate.mutes(true, BLOCK);
        assert!(gate.mutes(false, BLOCK)); // 1200 left
        assert!(gate.mutes(false, BLOCK)); // the tail ends inside it
        assert!(!gate.mutes(false, BLOCK));
    }

    #[test]
    fn a_second_transmission_during_the_tail_restarts_it() {
        let mut gate = TxMuteGate::new(12_000, TX_MUTE_TAIL_MS);
        gate.mutes(true, BLOCK);
        for _ in 0..4 {
            assert!(gate.mutes(false, BLOCK));
        }
        assert!(gate.mutes(true, BLOCK));
        for _ in 0..5 {
            assert!(gate.mutes(false, BLOCK));
        }
        assert!(!gate.mutes(false, BLOCK));
    }

    #[test]
    fn the_tail_is_counted_in_audio_not_in_blocks() {
        let mut gate = TxMuteGate::new(12_000, TX_MUTE_TAIL_MS);
        gate.mutes(true, BLOCK);
        // A backlog delivered as one 1 s block uses the whole tail at once.
        assert!(gate.mutes(false, 12_000));
        assert!(!gate.mutes(false, BLOCK));
    }

    #[test]
    fn a_zero_tail_unmutes_on_the_first_block_after_the_transmission() {
        let mut gate = TxMuteGate::new(12_000, 0);
        gate.mutes(true, BLOCK);
        assert!(!gate.mutes(false, BLOCK));
    }

    #[test]
    fn on_air_raises_the_flag_for_the_call_and_lowers_it_after() {
        let tx = TxActive::new();
        let seen_by_a_clone = tx.clone();
        assert!(!tx.get());
        let inside = tx.on_air(|| seen_by_a_clone.get());
        assert!(inside, "every clone is the same flag");
        assert!(!tx.get());
    }

    #[test]
    fn the_flag_stays_up_until_every_holder_has_let_go() {
        // An old transmission still closing its stream while the next one
        // has started: the first to finish must not unmute the receiver.
        let tx = TxActive::new();
        let old = tx.hold();
        let new = tx.hold();
        drop(old);
        assert!(tx.get(), "the flag came down with a transmission still on the air");
        drop(new);
        assert!(!tx.get());
    }

    #[test]
    fn on_air_lowers_the_flag_when_the_transmit_thread_panics() {
        let tx = TxActive::new();
        let t = tx.clone();
        let died = std::thread::spawn(move || t.on_air(|| panic!("tx thread died"))).join();
        assert!(died.is_err());
        assert!(!tx.get(), "a dead transmit thread must not leave the receiver muted");
    }
}
