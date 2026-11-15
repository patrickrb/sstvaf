//! Bounded audio hand-off between the capture worker and the decode thread.
//!
//! Mirrors `SstvSignalListener`'s `ArrayBlockingQueue` on Android, including
//! its **drop-oldest** overflow policy: if the decoder falls behind, the
//! freshest audio is what matters for finding the next leader, so the stalest
//! buffer is discarded rather than the newest. Drops are counted so the engine
//! can log them instead of degrading silently.
//!
//! It is also where the receiver is kept from hearing the app's own
//! transmission (see [`super::mute`]), as on Android, where the mute sits in
//! `SstvSignalListener.onAudioBuffer`: the push is the moment a block is
//! delivered from capture, and a block muted here stays muted however long it
//! then waits for a lagging decoder.

use std::collections::VecDeque;
use std::sync::{Condvar, Mutex};
use std::time::Duration;

use super::mute::{TxActive, TxMuteGate, TX_MUTE_TAIL_MS};

pub struct AudioQueue {
    inner: Mutex<Inner>,
    ready: Condvar,
    capacity: usize,
}

struct Inner {
    blocks: VecDeque<Vec<f32>>,
    dropped: u64,
    closed: bool,
    mute: Option<Mute>,
    /// Mute changes not yet collected: true = muting began, false = it ended.
    mute_edges: Vec<bool>,
}

struct Mute {
    tx_active: TxActive,
    gate: TxMuteGate,
    muted: bool,
}

impl AudioQueue {
    pub fn new(capacity: usize) -> AudioQueue {
        AudioQueue {
            inner: Mutex::new(Inner {
                blocks: VecDeque::with_capacity(capacity.max(1)),
                dropped: 0,
                closed: false,
                mute: None,
                mute_edges: Vec::new(),
            }),
            ready: Condvar::new(),
            capacity: capacity.max(1),
        }
    }

    /// A queue that replaces every block pushed while `tx_active` is raised,
    /// and for [`TX_MUTE_TAIL_MS`] of audio afterwards, with silence of the
    /// same length. Silence, not a dropped block: the decoder's timeline stays
    /// continuous, and a picture that was coming in ends as a partial within
    /// a second or so instead of being completed from the app's own audio.
    pub fn with_tx_mute(capacity: usize, tx_active: TxActive, sample_rate: u32) -> AudioQueue {
        let q = AudioQueue::new(capacity);
        q.inner.lock().unwrap().mute = Some(Mute {
            tx_active,
            gate: TxMuteGate::new(sample_rate, TX_MUTE_TAIL_MS),
            muted: false,
        });
        q
    }

    /// Enqueue a block. Never blocks: at capacity the oldest block is dropped.
    /// Returns the running dropped-block count so the caller can rate-limit a
    /// log line.
    pub fn push(&self, mut block: Vec<f32>) -> u64 {
        if block.is_empty() {
            return self.dropped();
        }
        let mut g = self.inner.lock().unwrap();
        if g.closed {
            return g.dropped;
        }
        let mut edge = None;
        if let Some(m) = g.mute.as_mut() {
            let muted = m.gate.mutes(m.tx_active.get(), block.len());
            if muted {
                block.fill(0.0);
            }
            if muted != m.muted {
                m.muted = muted;
                edge = Some(muted);
            }
        }
        if let Some(e) = edge {
            g.mute_edges.push(e);
        }
        while g.blocks.len() >= self.capacity {
            g.blocks.pop_front();
            g.dropped += 1;
        }
        g.blocks.push_back(block);
        let dropped = g.dropped;
        drop(g);
        self.ready.notify_one();
        dropped
    }

    /// Wait up to `timeout` for audio, then drain everything queued. Returns an
    /// empty vec on timeout, or once the queue is closed and empty.
    pub fn drain_blocking(&self, timeout: Duration) -> Vec<Vec<f32>> {
        let mut g = self.inner.lock().unwrap();
        if g.blocks.is_empty() && !g.closed {
            let (next, _) = self.ready.wait_timeout(g, timeout).unwrap();
            g = next;
        }
        g.blocks.drain(..).collect()
    }

    /// Wake any waiter and refuse further pushes — used to stop the decode
    /// thread promptly rather than after one more timeout.
    pub fn close(&self) {
        let mut g = self.inner.lock().unwrap();
        g.closed = true;
        drop(g);
        self.ready.notify_all();
    }

    pub fn is_closed(&self) -> bool {
        self.inner.lock().unwrap().closed
    }

    /// The mute changes since the last call, oldest first: `true` when blocks
    /// started being muted (the app began transmitting), `false` when audio
    /// started being heard again. Always empty for a queue without a mute.
    pub fn take_mute_edges(&self) -> Vec<bool> {
        std::mem::take(&mut self.inner.lock().unwrap().mute_edges)
    }

    pub fn dropped(&self) -> u64 {
        self.inner.lock().unwrap().dropped
    }

    pub fn len(&self) -> usize {
        self.inner.lock().unwrap().blocks.len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    /// One capture block (200 ms at 12 kHz) of a loud signal.
    fn loud() -> Vec<f32> {
        vec![0.5; 2400]
    }

    fn peaks(blocks: &[Vec<f32>]) -> Vec<f32> {
        blocks.iter().map(|b| b.iter().fold(0.0f32, |m, v| m.max(v.abs()))).collect()
    }

    #[test]
    fn blocks_captured_while_the_app_transmits_are_queued_as_silence() {
        let tx = TxActive::new();
        let q = AudioQueue::with_tx_mute(16, tx.clone(), 12_000);
        q.push(loud());
        tx.on_air(|| {
            for _ in 0..3 {
                q.push(loud());
            }
        });
        let got = q.drain_blocking(Duration::from_millis(10));
        // Same number of blocks, same lengths (the decoder's clock keeps
        // running), but only the one from before the transmission has audio.
        assert_eq!(got.iter().map(Vec::len).collect::<Vec<_>>(), vec![2400; 4]);
        assert_eq!(peaks(&got), vec![0.5, 0.0, 0.0, 0.0]);
        assert_eq!(q.take_mute_edges(), vec![true]);
        assert!(q.take_mute_edges().is_empty(), "an edge is reported once");
    }

    #[test]
    fn stays_muted_for_a_second_after_the_transmission_then_hears_again() {
        let tx = TxActive::new();
        let q = AudioQueue::with_tx_mute(16, tx.clone(), 12_000);
        tx.on_air(|| {
            q.push(loud());
        });
        // Five blocks = the 1 s tail; the sixth is live audio again.
        for _ in 0..6 {
            q.push(loud());
        }
        let got = q.drain_blocking(Duration::from_millis(10));
        assert_eq!(peaks(&got), vec![0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.5]);
        assert_eq!(q.take_mute_edges(), vec![true, false]);
    }

    #[test]
    fn a_muted_block_stays_muted_while_it_waits_for_a_lagging_decoder() {
        // The verdict is taken when the block is delivered, not when it is
        // decoded: a backlog captured during a transmission must not be
        // heard just because the transmission has ended by the time the
        // decoder gets to it.
        let tx = TxActive::new();
        let q = AudioQueue::with_tx_mute(64, tx.clone(), 12_000);
        tx.on_air(|| {
            for _ in 0..20 {
                q.push(loud());
            }
        });
        assert!(!tx.get());
        let got = q.drain_blocking(Duration::from_millis(10));
        assert_eq!(peaks(&got), vec![0.0; 20]);
    }

    #[test]
    fn a_queue_without_a_mute_never_mutes() {
        let q = AudioQueue::new(4);
        q.push(loud());
        assert_eq!(peaks(&q.drain_blocking(Duration::from_millis(10))), vec![0.5]);
        assert!(q.take_mute_edges().is_empty());
    }

    #[test]
    fn drains_in_order() {
        let q = AudioQueue::new(4);
        q.push(vec![1.0]);
        q.push(vec![2.0]);
        let got = q.drain_blocking(Duration::from_millis(10));
        assert_eq!(got, vec![vec![1.0], vec![2.0]]);
        assert!(q.is_empty());
    }

    #[test]
    fn drops_the_oldest_block_at_capacity() {
        let q = AudioQueue::new(2);
        q.push(vec![1.0]);
        q.push(vec![2.0]);
        let dropped = q.push(vec![3.0]);
        assert_eq!(dropped, 1);
        // The newest audio survives; the stalest is gone.
        assert_eq!(
            q.drain_blocking(Duration::from_millis(10)),
            vec![vec![2.0], vec![3.0]]
        );
    }

    #[test]
    fn counts_every_drop() {
        let q = AudioQueue::new(1);
        for i in 0..5 {
            q.push(vec![i as f32]);
        }
        assert_eq!(q.dropped(), 4);
        assert_eq!(q.len(), 1);
    }

    #[test]
    fn ignores_empty_blocks() {
        let q = AudioQueue::new(2);
        q.push(vec![]);
        assert!(q.is_empty());
        assert_eq!(q.dropped(), 0);
    }

    #[test]
    fn returns_empty_on_timeout() {
        let q = AudioQueue::new(2);
        let got = q.drain_blocking(Duration::from_millis(5));
        assert!(got.is_empty());
    }

    #[test]
    fn close_wakes_a_waiting_drain() {
        let q = Arc::new(AudioQueue::new(2));
        let q2 = q.clone();
        let t = std::thread::spawn(move || {
            // A long timeout: this must return because of close(), not expiry.
            q2.drain_blocking(Duration::from_secs(30))
        });
        std::thread::sleep(Duration::from_millis(50));
        q.close();
        assert!(t.join().unwrap().is_empty());
        assert!(q.is_closed());
    }

    #[test]
    fn close_refuses_further_pushes() {
        let q = AudioQueue::new(2);
        q.close();
        q.push(vec![1.0]);
        assert!(q.is_empty());
    }

    #[test]
    fn a_waiting_drain_wakes_on_push() {
        let q = Arc::new(AudioQueue::new(2));
        let q2 = q.clone();
        let t = std::thread::spawn(move || q2.drain_blocking(Duration::from_secs(30)));
        std::thread::sleep(Duration::from_millis(50));
        q.push(vec![7.0]);
        assert_eq!(t.join().unwrap(), vec![vec![7.0]]);
    }
}
