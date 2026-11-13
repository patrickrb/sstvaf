//! The transmit path: an ARGB image in, audio on the air out.
//!
//! `engine` is the pure playback logic (buffer filling, progress math),
//! `service` the thread that wires it to the codec and a real output device.
//! The waveform is encoded in full at the output device's native sample rate
//! and played exactly as encoded — see `dsp::encode` and CLAUDE.md's "TX audio
//! pipeline" for why the head and tail are sacred.

pub mod engine;
pub mod service;

pub use engine::{ensure_idle, TxEvent};
pub use service::TxService;

/// How often the progress loop reports playback position (~5x/second, matching
/// the RX status cadence closely enough for a smooth bar).
pub const PROGRESS_TICK_MS: u64 = 200;

/// Slept after the final sample has been handed to the device before the
/// stream is dropped, so the device's internal buffer finishes playing and the
/// tail of the transmission is never cut mid-symbol.
pub const DRAIN_MS: u64 = 500;
