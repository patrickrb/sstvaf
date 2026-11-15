//! One station: a receiver and a transmitter that know about each other.
//!
//! The receiver must go deaf while the transmitter is on the air, or it
//! decodes and saves the app's own picture (see [`crate::audio::mute`]). That
//! only works if both are given the same on-air flag, so the flag lives here
//! and nowhere else: the app starts its services through a `Station` and
//! never handles the flag itself.

use std::path::PathBuf;
use std::sync::mpsc::Sender;

use crate::audio::{AudioQueue, TxActive};
use crate::rx::{RxEvent, RxService, QUEUE_CAPACITY, SAMPLE_RATE};
use crate::tx::{TxEvent, TxService};

#[derive(Debug, Default)]
pub struct Station {
    on_air: TxActive,
}

impl Station {
    pub fn new() -> Station {
        Station::default()
    }

    /// Is a transmission of this station's on the air right now?
    pub fn is_on_air(&self) -> bool {
        self.on_air.get()
    }

    /// Start receiving on `device` (or the system default). See
    /// [`RxService::start`].
    pub fn start_rx(
        &self,
        device: Option<String>,
        events: Sender<RxEvent>,
        save_dir: Option<PathBuf>,
    ) -> anyhow::Result<RxService> {
        RxService::start(device, events, save_dir, self.rx_queue(QUEUE_CAPACITY))
    }

    /// Start transmitting on `device` (or the system default). See
    /// [`TxService::start`].
    pub fn start_tx(
        &self,
        device: Option<String>,
        mode_id: i32,
        pixels: Vec<u32>,
        events: Sender<TxEvent>,
    ) -> anyhow::Result<TxService> {
        TxService::start(device, mode_id, pixels, events, self.on_air.clone())
    }

    /// The capture hand-off for this station's receiver: it queues silence
    /// while this station transmits.
    fn rx_queue(&self, capacity: usize) -> AudioQueue {
        AudioQueue::with_tx_mute(capacity, self.on_air.clone(), SAMPLE_RATE)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc::channel;
    use std::time::Duration;

    fn peak(block: &[f32]) -> f32 {
        block.iter().fold(0.0f32, |m, v| m.max(v.abs()))
    }

    #[test]
    fn the_receivers_queue_goes_deaf_while_the_station_transmits() {
        let station = Station::new();
        let queue = station.rx_queue(16);
        queue.push(vec![0.5; 2400]);
        {
            let _transmitting = station.on_air.hold();
            assert!(station.is_on_air());
            queue.push(vec![0.5; 2400]);
        }
        assert!(!station.is_on_air());
        let got = queue.drain(Duration::from_millis(10));
        assert_eq!(got.blocks.iter().map(|b| peak(b)).collect::<Vec<_>>(), vec![0.5, 0.0]);
        assert_eq!(got.mute_edges, vec![true]);
    }

    #[test]
    fn a_transmission_that_cannot_start_does_not_put_the_station_on_the_air() {
        // An image of the wrong size fails in the encoder before any stream
        // is opened (with no output device, one step earlier): nothing is
        // played, and the receiver must not be left muted.
        let station = Station::new();
        let (tx, _rx) = channel();
        assert!(station.start_tx(None, 0, vec![0; 4], tx).is_err());
        assert!(!station.is_on_air());
    }
}
