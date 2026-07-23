//! SSTVAF desktop — library crate.
//!
//! The Tauri binary (`main.rs`) is a thin shell over this crate so the logic
//! stays unit-testable with plain `cargo test` (and reachable from the
//! integration tests in `tests/`).

pub mod dsp;
pub mod modes;
