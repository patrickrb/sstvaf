//! The receive path: audio in, decoded images out.
//!
//! `engine` is the pure state machine, `session` the decoder interface it
//! drives, and `service` the thread that wires them to a real audio device.

pub mod engine;
pub mod service;
pub mod session;

pub use engine::{RxEngine, RxEvent, RxState};
pub use service::RxService;
pub use session::SstvSession;

/// The rate the decoder runs at. Fixed at 12 kHz to match the Android pipeline
/// (`SstvSignalListener.SAMPLE_RATE_HZ`) — device audio is resampled to it, so
/// desktop and phone decode on identical numbers.
pub const SAMPLE_RATE: u32 = 12_000;

/// How long the decode loop waits for audio before polling status anyway, so
/// the UI still refreshes (~4x/second) on a quiet channel.
pub const POLL_WAIT_MS: u64 = 250;

/// Queued audio blocks before the drop-oldest policy kicks in. 64 blocks of
/// 200 ms is ~13 s of headroom.
pub const QUEUE_CAPACITY: usize = 64;

/// Rate limit for the "audio queue overflow" log line.
pub const DROP_LOG_EVERY: u64 = 50;
