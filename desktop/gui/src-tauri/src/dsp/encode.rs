//! Safe wrapper over the SSTV encoder (`sstv_encode*` in `cpp/sstv_lib`).
//!
//! The codec synthesizes a whole transmission — 300 ms leader / 1200 Hz break /
//! VIS code / every scan line — as one phase-continuous buffer. Everything
//! downstream only has to *not break it*: never drop samples from the head, or
//! the receiving station loses the sync it needs (see CLAUDE.md, "TX audio
//! pipeline").

use std::os::raw::c_int;

use crate::dsp::{ffi, SstvError};
use crate::modes::ModeInfo;

/// Encoder output amplitude, matching `NativeSstvCodec.ENCODE_AMPLITUDE` on
/// Android: slightly below full scale so the float→i16 conversion in the audio
/// sink never sits on the clip boundary. TX gain is applied later, in the sink.
pub const ENCODE_AMPLITUDE: f32 = 0.95;

/// Exact sample count `encode` will produce for this mode and rate.
pub fn num_samples(mode: &ModeInfo, sample_rate: u32) -> Result<usize, SstvError> {
    let n = unsafe { ffi::sstv_encode_num_samples(mode.id as c_int, sample_rate as c_int) };
    if n <= 0 {
        return Err(SstvError::from_code(n));
    }
    Ok(n as usize)
}

/// Encode a full transmission. `argb` is 0xAARRGGBB, row-major, exactly the
/// mode's native dimensions. The C codec validates width/height; this wrapper
/// validates the slice length so mismatches get a clear error.
pub fn encode(
    mode: &ModeInfo,
    argb: &[u32],
    sample_rate: u32,
    amplitude: f32,
) -> Result<Vec<f32>, SstvError> {
    encode_with_flags(mode, argb, sample_rate, amplitude, 0)
}

/// `encode` with raw `SSTV_ENCODE_*` flags. Only tests pass a non-zero flag.
pub fn encode_with_flags(
    mode: &ModeInfo,
    argb: &[u32],
    sample_rate: u32,
    amplitude: f32,
    flags: c_int,
) -> Result<Vec<f32>, SstvError> {
    if argb.len() < mode.pixel_count() {
        return Err(SstvError::Invalid(format!(
            "{} needs {}x{} = {} pixels, got {}",
            mode.name,
            mode.width,
            mode.height,
            mode.pixel_count(),
            argb.len()
        )));
    }
    let n = num_samples(mode, sample_rate)?;
    let mut out = vec![0.0f32; n];
    let written = unsafe {
        ffi::sstv_encode_ex(
            mode.id as c_int,
            argb.as_ptr(),
            mode.width as c_int,
            mode.height as c_int,
            sample_rate as c_int,
            amplitude,
            flags,
            out.as_mut_ptr(),
            n as c_int,
        )
    };
    if written < 0 {
        return Err(SstvError::from_code(written));
    }
    if written as usize != n {
        // num_samples is documented to be exact; a short write means the codec
        // and this wrapper disagree, which would truncate the transmission.
        return Err(SstvError::Invalid(format!(
            "sstv_encode wrote {written} of {n} samples"
        )));
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::modes::MODES;

    /// A deterministic test image: a diagonal RGB gradient at the mode's size.
    pub(crate) fn test_image(mode: &ModeInfo) -> Vec<u32> {
        let (w, h) = (mode.width as usize, mode.height as usize);
        let mut px = vec![0u32; w * h];
        for y in 0..h {
            for x in 0..w {
                let r = (x * 255 / w.max(1)) as u32;
                let g = (y * 255 / h.max(1)) as u32;
                let b = ((x + y) * 255 / (w + h).max(1)) as u32;
                px[y * w + x] = 0xFF00_0000 | (r << 16) | (g << 8) | b;
            }
        }
        px
    }

    #[test]
    fn num_samples_tracks_the_mode_duration() {
        // Each mode's sample count must land on its documented TX duration.
        for m in MODES.iter() {
            let n = num_samples(m, 12000).expect("num_samples");
            let seconds = n as f64 / 12000.0;
            assert!(
                (seconds - m.tx_seconds).abs() < 0.05,
                "{}: encoder says {seconds:.3} s, table says {:.3} s",
                m.name,
                m.tx_seconds
            );
        }
    }

    #[test]
    fn num_samples_scales_with_the_sample_rate() {
        let m = &MODES[0];
        let at12k = num_samples(m, 12000).unwrap();
        let at48k = num_samples(m, 48000).unwrap();
        assert!((at48k as f64 / at12k as f64 - 4.0).abs() < 0.01);
    }

    #[test]
    fn num_samples_rejects_a_bad_mode_id() {
        let bogus = ModeInfo { id: 99, ..MODES[0] };
        assert!(num_samples(&bogus, 12000).is_err());
    }

    #[test]
    fn encode_fills_the_whole_buffer() {
        let m = &MODES[0];
        let samples = encode(m, &test_image(m), 12000, ENCODE_AMPLITUDE).expect("encode");
        assert_eq!(samples.len(), num_samples(m, 12000).unwrap());
        // The transmission opens with the 300 ms leader, so the head must be
        // live audio — a silent start is the classic clipped-leader bug.
        let head_peak = samples[..1200].iter().fold(0.0f32, |a, s| a.max(s.abs()));
        assert!(head_peak > 0.5, "leader is silent (peak {head_peak})");
        assert!(samples.iter().all(|s| s.abs() <= ENCODE_AMPLITUDE + 1e-4));
    }

    #[test]
    fn encode_rejects_an_undersized_image() {
        let m = &MODES[0];
        let err = encode(m, &vec![0u32; 10], 12000, ENCODE_AMPLITUDE).unwrap_err();
        assert!(matches!(err, SstvError::Invalid(_)), "got {err:?}");
    }

    #[test]
    fn robot36_parity_flag_changes_the_waveform() {
        let m = &MODES[0];
        let img = test_image(m);
        let normal = encode(m, &img, 12000, ENCODE_AMPLITUDE).unwrap();
        let swapped = encode_with_flags(
            m,
            &img,
            12000,
            ENCODE_AMPLITUDE,
            ffi::SSTV_ENCODE_SWAP_ROBOT36_PARITY,
        )
        .unwrap();
        assert_eq!(normal.len(), swapped.len());
        assert!(normal != swapped, "parity flag had no effect on the waveform");
    }
}
