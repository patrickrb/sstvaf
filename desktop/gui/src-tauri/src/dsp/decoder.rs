//! Safe wrapper over the push-model SSTV decoder (`sstv_decoder_*`).
//!
//! Mirrors `NativeSstvCodec.NativeDecoderSession` on Android: one handle, fed
//! arbitrary-size blocks of mono float samples at the rate it was created with,
//! polled for status and rows. The C decoder is *not* thread-safe, so the
//! handle is owned by exactly one `Decoder` and moved between threads rather
//! than shared.

use std::os::raw::c_int;

use serde::{Deserialize, Serialize};

use crate::dsp::{ffi, SstvError};
use crate::modes::{mode_by_id, ModeInfo};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum DecodeStatus {
    /// Hunting for the 1900 Hz leader.
    Idle,
    /// Leader seen; hunting for the VIS start bit.
    Leader,
    /// Start bit seen; sampling the VIS cells.
    Vis,
    /// VIS accepted; decoding image lines.
    Image,
    /// Full image decoded.
    Done,
    /// Signal lost mid-image; the partial image is retained.
    Aborted,
}

impl DecodeStatus {
    fn from_code(code: c_int) -> DecodeStatus {
        match code {
            ffi::SSTV_STATUS_LEADER => DecodeStatus::Leader,
            ffi::SSTV_STATUS_VIS => DecodeStatus::Vis,
            ffi::SSTV_STATUS_IMAGE => DecodeStatus::Image,
            ffi::SSTV_STATUS_DONE => DecodeStatus::Done,
            ffi::SSTV_STATUS_ABORTED => DecodeStatus::Aborted,
            // SSTV_STATUS_IDLE and anything unexpected: hunting is the safe
            // reading — it neither claims a lock nor discards a frame.
            _ => DecodeStatus::Idle,
        }
    }

    /// True once the decoder has finished with a frame (complete or partial),
    /// i.e. the caller should snapshot the image and then `reset`.
    pub fn is_terminal(self) -> bool {
        matches!(self, DecodeStatus::Done | DecodeStatus::Aborted)
    }
}

pub struct Decoder {
    handle: *mut ffi::sstv_decoder_t,
    sample_rate: u32,
}

// The handle is a plain heap allocation with no thread-affine state, so it can
// move between threads. It is deliberately NOT Sync: the C decoder has no
// internal locking, and `Decoder` hands out `&self` methods that mutate it.
unsafe impl Send for Decoder {}

impl Decoder {
    /// Create a decoder for `sample_rate` Hz mono float audio.
    pub fn new(sample_rate: u32) -> Result<Decoder, SstvError> {
        let handle = unsafe { ffi::sstv_decoder_create(sample_rate as c_int) };
        if handle.is_null() {
            return Err(SstvError::NoMem);
        }
        Ok(Decoder { handle, sample_rate })
    }

    pub fn sample_rate(&self) -> u32 {
        self.sample_rate
    }

    /// Feed a block of mono float samples. Any block size is fine.
    pub fn push(&mut self, samples: &[f32]) {
        if samples.is_empty() {
            return;
        }
        unsafe { ffi::sstv_decoder_push(self.handle, samples.as_ptr(), samples.len() as c_int) };
    }

    pub fn status(&self) -> DecodeStatus {
        DecodeStatus::from_code(unsafe { ffi::sstv_decoder_status(self.handle) })
    }

    /// The locked mode, or `None` before the VIS code is accepted.
    pub fn mode(&self) -> Option<&'static ModeInfo> {
        mode_by_id(unsafe { ffi::sstv_decoder_mode(self.handle) })
    }

    pub fn rows_ready(&self) -> usize {
        let n = unsafe { ffi::sstv_decoder_rows_ready(self.handle) };
        n.max(0) as usize
    }

    /// Copy decoded rows into `argb` (row-major, mode width). Returns the rows
    /// actually copied; a negative C return (no mode yet / bad range) becomes 0,
    /// matching the Android session's "nothing to read" handling.
    pub fn read_rows(&self, first_row: usize, n_rows: usize, argb: &mut [u32]) -> usize {
        if n_rows == 0 {
            return 0;
        }
        let Some(mode) = self.mode() else { return 0 };
        let needed = n_rows * mode.width as usize;
        if argb.len() < needed {
            return 0;
        }
        let copied = unsafe {
            ffi::sstv_decoder_read_rows(
                self.handle,
                first_row as c_int,
                n_rows as c_int,
                argb.as_mut_ptr(),
            )
        };
        copied.max(0) as usize
    }

    /// Sample-clock slant in ppm (+ = lines arriving slower than nominal).
    /// 0 until enough syncs have been tracked.
    pub fn slant_ppm(&self) -> f32 {
        unsafe { ffi::sstv_decoder_slant_ppm(self.handle) }
    }

    /// 0..1 blend of sync-hit-rate and in-band signal coherence.
    pub fn quality(&self) -> f32 {
        unsafe { ffi::sstv_decoder_quality(self.handle) }
    }

    /// Back to hunting. Every read (rows, mode, quality, slant) must happen
    /// before this — reset discards the frame.
    pub fn reset(&mut self) {
        unsafe { ffi::sstv_decoder_reset(self.handle) };
    }
}

impl Drop for Decoder {
    fn drop(&mut self) {
        unsafe { ffi::sstv_decoder_destroy(self.handle) };
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_fresh_decoder_is_hunting() {
        let d = Decoder::new(12000).expect("create");
        assert_eq!(d.status(), DecodeStatus::Idle);
        assert_eq!(d.sample_rate(), 12000);
        assert!(d.mode().is_none());
        assert_eq!(d.rows_ready(), 0);
    }

    #[test]
    fn silence_never_locks() {
        let mut d = Decoder::new(12000).expect("create");
        d.push(&vec![0.0f32; 12000]);
        assert_eq!(d.status(), DecodeStatus::Idle);
    }

    #[test]
    fn pushing_an_empty_block_is_a_no_op() {
        let mut d = Decoder::new(12000).expect("create");
        d.push(&[]);
        assert_eq!(d.status(), DecodeStatus::Idle);
    }

    #[test]
    fn read_rows_without_a_lock_reads_nothing() {
        let d = Decoder::new(12000).expect("create");
        let mut buf = vec![0u32; 320];
        assert_eq!(d.read_rows(0, 1, &mut buf), 0);
    }

    #[test]
    fn read_rows_rejects_an_undersized_buffer() {
        let d = Decoder::new(12000).expect("create");
        let mut buf = vec![0u32; 4];
        assert_eq!(d.read_rows(0, 100, &mut buf), 0);
        assert_eq!(d.read_rows(0, 0, &mut buf), 0);
    }

    #[test]
    fn status_codes_map_to_the_c_enum() {
        assert_eq!(DecodeStatus::from_code(0), DecodeStatus::Idle);
        assert_eq!(DecodeStatus::from_code(1), DecodeStatus::Leader);
        assert_eq!(DecodeStatus::from_code(2), DecodeStatus::Vis);
        assert_eq!(DecodeStatus::from_code(3), DecodeStatus::Image);
        assert_eq!(DecodeStatus::from_code(4), DecodeStatus::Done);
        assert_eq!(DecodeStatus::from_code(5), DecodeStatus::Aborted);
        // An unknown code must read as "still hunting", never as a decode.
        assert_eq!(DecodeStatus::from_code(42), DecodeStatus::Idle);
    }

    #[test]
    fn terminal_states_are_done_and_aborted() {
        assert!(DecodeStatus::Done.is_terminal());
        assert!(DecodeStatus::Aborted.is_terminal());
        assert!(!DecodeStatus::Image.is_terminal());
        assert!(!DecodeStatus::Idle.is_terminal());
    }
}
