//! Cross-platform audio I/O (cpal): device enumeration and continuous capture
//! resampled to the codec's 12 kHz mono rate. TX playback lands with the TX
//! composer.

pub mod devices;
pub mod input;
pub mod queue;
pub mod resample;

pub use devices::{list_input_devices, AudioDevice};
pub use input::AudioInput;
pub use queue::AudioQueue;
