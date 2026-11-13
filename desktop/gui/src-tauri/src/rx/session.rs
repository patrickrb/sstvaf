//! The decoder interface the RX engine drives.
//!
//! The engine is written against this trait rather than `dsp::Decoder` directly
//! so its state machine can be unit-tested without real audio — the same reason
//! the Android side has `SstvCodec`/`FakeSstvCodec` behind `SstvSignalListener`.

use crate::dsp::decoder::{DecodeStatus, Decoder};
use crate::modes::ModeInfo;

pub trait SstvSession: Send {
    fn push(&mut self, samples: &[f32]);
    fn status(&self) -> DecodeStatus;
    fn mode(&self) -> Option<&'static ModeInfo>;
    fn rows_ready(&self) -> usize;
    fn read_rows(&self, first_row: usize, n_rows: usize, out: &mut [u32]) -> usize;
    fn quality(&self) -> f32;
    fn slant_ppm(&self) -> f32;
    fn reset(&mut self);
}

impl SstvSession for Decoder {
    fn push(&mut self, samples: &[f32]) {
        Decoder::push(self, samples)
    }
    fn status(&self) -> DecodeStatus {
        Decoder::status(self)
    }
    fn mode(&self) -> Option<&'static ModeInfo> {
        Decoder::mode(self)
    }
    fn rows_ready(&self) -> usize {
        Decoder::rows_ready(self)
    }
    fn read_rows(&self, first_row: usize, n_rows: usize, out: &mut [u32]) -> usize {
        Decoder::read_rows(self, first_row, n_rows, out)
    }
    fn quality(&self) -> f32 {
        Decoder::quality(self)
    }
    fn slant_ppm(&self) -> f32 {
        Decoder::slant_ppm(self)
    }
    fn reset(&mut self) {
        Decoder::reset(self)
    }
}
