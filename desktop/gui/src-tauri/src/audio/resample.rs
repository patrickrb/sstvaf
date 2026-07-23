//! Streaming sample-rate conversion down to the codec's 12 kHz rate.
//!
//! Desktop hardware rarely offers 12 kHz directly (Android's AudioRecord forced
//! it), so we resample the device's native rate. Decoding at 12 kHz rather than
//! the device rate is deliberate: it is cheaper, and it keeps the desktop and
//! phone decoders running on identical numbers.
//!
//! Uses rubato's `FftFixedIn` with a fixed input chunk; when the rates already
//! match it is a no-op pass-through.

use rubato::{FftFixedIn, Resampler};

const CHUNK_IN: usize = 1024;
const SUB_CHUNKS: usize = 2;

pub struct MonoResampler {
    inner: Option<FftFixedIn<f32>>,
    pending: Vec<f32>,
    in_rate: u32,
    out_rate: u32,
}

impl MonoResampler {
    pub fn new(in_rate: u32, out_rate: u32) -> Self {
        let inner = if in_rate == out_rate {
            None
        } else {
            match FftFixedIn::<f32>::new(
                in_rate as usize,
                out_rate as usize,
                CHUNK_IN,
                SUB_CHUNKS,
                1,
            ) {
                Ok(r) => Some(r),
                Err(e) => {
                    log::error!("failed to create resampler ({in_rate} -> {out_rate}): {e}");
                    None
                }
            }
        };
        MonoResampler {
            inner,
            pending: Vec::with_capacity(CHUNK_IN * 2),
            in_rate,
            out_rate,
        }
    }

    /// Push mono input samples at `in_rate`; returns whatever output the
    /// resampler could produce (it buffers a partial chunk internally).
    pub fn process(&mut self, input: &[f32]) -> Vec<f32> {
        if self.inner.is_none() {
            // Rates match — legitimate identity pass-through.
            if self.in_rate == self.out_rate {
                return input.to_vec();
            }
            // Rates differ but the resampler failed to initialise. Passing the
            // samples through un-resampled would feed wrong-rate audio to the
            // decoder, which reads as a slanted/garbled image rather than an
            // obvious failure. Fail closed instead.
            return Vec::new();
        }
        self.pending.extend_from_slice(input);
        let resampler = self.inner.as_mut().unwrap();
        let mut out = Vec::new();
        let needed = resampler.input_frames_next();
        while self.pending.len() >= needed {
            let chunk: Vec<f32> = self.pending.drain(..needed).collect();
            if let Ok(mut produced) = resampler.process(&[chunk], None) {
                if let Some(ch) = produced.pop() {
                    out.extend_from_slice(&ch);
                }
            }
        }
        out
    }

    pub fn in_rate(&self) -> u32 {
        self.in_rate
    }

    pub fn out_rate(&self) -> u32 {
        self.out_rate
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Feed `secs` seconds of a sine at `in_rate` and return everything the
    /// resampler produced.
    fn run(mut r: MonoResampler, in_rate: u32, secs: f32) -> Vec<f32> {
        let n = (in_rate as f32 * secs) as usize;
        let mut out = Vec::new();
        // 100-sample blocks: the resampler must not care about block size.
        for block in (0..n).collect::<Vec<_>>().chunks(100) {
            let samples: Vec<f32> = block
                .iter()
                .map(|i| {
                    (2.0 * std::f32::consts::PI * 1000.0 * *i as f32 / in_rate as f32).sin()
                })
                .collect();
            out.extend(r.process(&samples));
        }
        out
    }

    #[test]
    fn matching_rates_pass_through_untouched() {
        let mut r = MonoResampler::new(12000, 12000);
        let input = vec![0.1, -0.2, 0.3];
        assert_eq!(r.process(&input), input);
        assert_eq!(r.in_rate(), 12000);
        assert_eq!(r.out_rate(), 12000);
    }

    #[test]
    fn downsamples_48k_to_12k_at_the_right_length() {
        let out = run(MonoResampler::new(48000, 12000), 48000, 1.0);
        // One second in, one second out (minus the resampler's internal
        // buffering of at most one chunk).
        assert!(
            (out.len() as i64 - 12000).abs() < 1200,
            "48k->12k produced {} samples for 1 s",
            out.len()
        );
    }

    #[test]
    fn downsamples_44k1_to_12k_at_the_right_length() {
        // The awkward rate: 44100 -> 12000 is not an integer ratio.
        let out = run(MonoResampler::new(44100, 12000), 44100, 1.0);
        assert!(
            (out.len() as i64 - 12000).abs() < 1200,
            "44.1k->12k produced {} samples for 1 s",
            out.len()
        );
    }

    #[test]
    fn preserves_signal_amplitude() {
        let out = run(MonoResampler::new(48000, 12000), 48000, 0.5);
        // A 1 kHz tone is far below the 6 kHz Nyquist of the output rate, so it
        // must survive the conversion at roughly full amplitude.
        let peak = out.iter().fold(0.0f32, |a, s| a.max(s.abs()));
        assert!(peak > 0.8, "1 kHz tone came out at peak {peak}");
    }

    #[test]
    fn buffers_partial_chunks_instead_of_dropping_them() {
        let mut r = MonoResampler::new(48000, 12000);
        // Less than one input chunk: nothing out yet, but nothing lost either.
        assert!(r.process(&vec![0.0; 10]).is_empty());
        let out = r.process(&vec![0.5; 8192]);
        assert!(!out.is_empty(), "resampler never flushed its buffer");
    }
}
