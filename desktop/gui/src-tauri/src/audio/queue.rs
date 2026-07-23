//! Bounded audio hand-off between the capture worker and the decode thread.
//!
//! Mirrors `SstvSignalListener`'s `ArrayBlockingQueue` on Android, including
//! its **drop-oldest** overflow policy: if the decoder falls behind, the
//! freshest audio is what matters for finding the next leader, so the stalest
//! buffer is discarded rather than the newest. Drops are counted so the engine
//! can log them instead of degrading silently.

use std::collections::VecDeque;
use std::sync::{Condvar, Mutex};
use std::time::Duration;

pub struct AudioQueue {
    inner: Mutex<Inner>,
    ready: Condvar,
    capacity: usize,
}

struct Inner {
    blocks: VecDeque<Vec<f32>>,
    dropped: u64,
    closed: bool,
}

impl AudioQueue {
    pub fn new(capacity: usize) -> AudioQueue {
        AudioQueue {
            inner: Mutex::new(Inner {
                blocks: VecDeque::with_capacity(capacity.max(1)),
                dropped: 0,
                closed: false,
            }),
            ready: Condvar::new(),
            capacity: capacity.max(1),
        }
    }

    /// Enqueue a block. Never blocks: at capacity the oldest block is dropped.
    /// Returns the running dropped-block count so the caller can rate-limit a
    /// log line.
    pub fn push(&self, block: Vec<f32>) -> u64 {
        if block.is_empty() {
            return self.dropped();
        }
        let mut g = self.inner.lock().unwrap();
        if g.closed {
            return g.dropped;
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
