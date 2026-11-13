//! Cross-platform audio I/O (cpal): device enumeration, continuous capture
//! resampled to the codec's 12 kHz mono rate, and output enumeration for the
//! TX path (`tx::service` owns the playback stream itself).

pub mod devices;
pub mod input;
pub mod queue;
pub mod resample;

pub use devices::{list_input_devices, list_output_devices, AudioDevice};
pub use input::AudioInput;
pub use queue::AudioQueue;
