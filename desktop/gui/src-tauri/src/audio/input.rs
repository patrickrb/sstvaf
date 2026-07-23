//! Continuous audio capture: open an input device, downmix to mono, resample to
//! 12 kHz on a worker thread, and hand blocks to an `AudioQueue`.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::JoinHandle;

use cpal::traits::{DeviceTrait, StreamTrait};
use cpal::{FromSample, Sample, SampleFormat};
use ringbuf::traits::{Consumer, Producer, Split};
use ringbuf::HeapRb;

use super::devices::find_input_device;
use super::queue::AudioQueue;
use super::resample::MonoResampler;
use crate::rx::SAMPLE_RATE;

/// Samples handed to the decoder per block: 200 ms at 12 kHz, matching the
/// Android listener's monitor buffer, so the decode thread polls status at a
/// familiar cadence.
const BLOCK_SAMPLES: usize = (SAMPLE_RATE as usize / 1000) * 200;

/// An active capture session. Dropping it stops capture.
pub struct AudioInput {
    _stream: cpal::Stream, // kept alive; !Send on Windows so the owner stays put
    stop: Arc<AtomicBool>,
    worker: Option<JoinHandle<()>>,
    pub device_name: String,
    pub device_rate: u32,
}

impl AudioInput {
    pub fn start(device_name: Option<&str>, queue: Arc<AudioQueue>) -> anyhow::Result<AudioInput> {
        let device = find_input_device(device_name)
            .ok_or_else(|| anyhow::anyhow!("no input audio device available"))?;
        let dev_name = device.name().unwrap_or_else(|_| "unknown".into());
        let supported = device.default_input_config()?;
        let sample_format = supported.sample_format();
        let config: cpal::StreamConfig = supported.config();
        let channels = config.channels as usize;
        let device_rate = config.sample_rate.0;

        // Ring buffer between the realtime callback and the resampling worker.
        // The callback must never block or allocate, so it only pushes here.
        let rb = HeapRb::<f32>::new(device_rate as usize * 2); // ~2 s headroom
        let (mut prod, mut cons) = rb.split();

        let err_fn = |e| log::error!("audio input stream error: {e}");

        // One arm per sample format cpal may hand us; each downmixes
        // interleaved frames to mono f32.
        let stream = match sample_format {
            SampleFormat::F32 => device.build_input_stream(
                &config,
                move |data: &[f32], _| push_mono(data, channels, &mut prod),
                err_fn,
                None,
            )?,
            SampleFormat::I16 => device.build_input_stream(
                &config,
                move |data: &[i16], _| push_mono(data, channels, &mut prod),
                err_fn,
                None,
            )?,
            SampleFormat::U16 => device.build_input_stream(
                &config,
                move |data: &[u16], _| push_mono(data, channels, &mut prod),
                err_fn,
                None,
            )?,
            other => anyhow::bail!("unsupported input sample format: {other:?}"),
        };
        stream.play()?;

        // Resampling worker: device_rate mono -> 12 kHz -> AudioQueue.
        let stop = Arc::new(AtomicBool::new(false));
        let stop_w = stop.clone();
        let worker = std::thread::Builder::new()
            .name("sstvaf-audio-resample".into())
            .spawn(move || {
                let mut resampler = MonoResampler::new(device_rate, SAMPLE_RATE);
                let mut scratch = vec![0.0f32; 4096];
                let mut block: Vec<f32> = Vec::with_capacity(BLOCK_SAMPLES);
                while !stop_w.load(Ordering::Relaxed) {
                    let n = cons.pop_slice(&mut scratch);
                    if n == 0 {
                        std::thread::sleep(std::time::Duration::from_millis(5));
                        continue;
                    }
                    let out = resampler.process(&scratch[..n]);
                    block.extend_from_slice(&out);
                    while block.len() >= BLOCK_SAMPLES {
                        let rest = block.split_off(BLOCK_SAMPLES);
                        queue.push(std::mem::replace(&mut block, rest));
                    }
                }
                // Hand over whatever is left so the tail of a transmission
                // isn't stranded in the worker on stop.
                if !block.is_empty() {
                    queue.push(block);
                }
            })?;

        Ok(AudioInput {
            _stream: stream,
            stop,
            worker: Some(worker),
            device_name: dev_name,
            device_rate,
        })
    }
}

impl Drop for AudioInput {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(w) = self.worker.take() {
            let _ = w.join();
        }
    }
}

/// Downmix interleaved `T` frames to mono f32 and push into the ring producer.
/// Called on the realtime audio thread — no allocation, no locking.
fn push_mono<T, P>(data: &[T], channels: usize, prod: &mut P)
where
    T: Sample,
    f32: FromSample<T>,
    P: Producer<Item = f32>,
{
    if channels == 0 {
        return;
    }
    if channels == 1 {
        for &s in data {
            let _ = prod.try_push(f32::from_sample(s));
        }
        return;
    }
    for frame in data.chunks_exact(channels) {
        let mut acc = 0.0f32;
        for &s in frame {
            acc += f32::from_sample(s);
        }
        let _ = prod.try_push(acc / channels as f32);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ringbuf::traits::Observer;

    #[test]
    fn block_size_is_200ms_at_the_codec_rate() {
        assert_eq!(BLOCK_SAMPLES, 2400);
    }

    #[test]
    fn mono_input_passes_straight_through() {
        let rb = HeapRb::<f32>::new(16);
        let (mut prod, mut cons) = rb.split();
        push_mono(&[0.25f32, -0.5, 1.0], 1, &mut prod);
        let mut out = vec![0.0; 3];
        assert_eq!(cons.pop_slice(&mut out), 3);
        assert_eq!(out, vec![0.25, -0.5, 1.0]);
    }

    #[test]
    fn stereo_is_averaged_to_mono() {
        let rb = HeapRb::<f32>::new(16);
        let (mut prod, mut cons) = rb.split();
        // Two frames: (1.0, 0.0) and (0.5, -0.5).
        push_mono(&[1.0f32, 0.0, 0.5, -0.5], 2, &mut prod);
        let mut out = vec![0.0; 2];
        assert_eq!(cons.pop_slice(&mut out), 2);
        assert_eq!(out, vec![0.5, 0.0]);
    }

    #[test]
    fn integer_formats_are_converted_to_float() {
        let rb = HeapRb::<f32>::new(16);
        let (mut prod, mut cons) = rb.split();
        push_mono(&[i16::MAX, 0, i16::MIN], 1, &mut prod);
        let mut out = vec![0.0; 3];
        assert_eq!(cons.pop_slice(&mut out), 3);
        assert!((out[0] - 1.0).abs() < 0.001);
        assert!(out[1].abs() < 0.001);
        assert!((out[2] + 1.0).abs() < 0.001);
    }

    #[test]
    fn a_partial_trailing_frame_is_ignored() {
        let rb = HeapRb::<f32>::new(16);
        let (mut prod, cons) = rb.split();
        // Three samples on a stereo stream: one whole frame plus a stray.
        push_mono(&[1.0f32, 1.0, 0.5], 2, &mut prod);
        assert_eq!(cons.occupied_len(), 1);
    }

    #[test]
    fn zero_channels_is_a_no_op_rather_than_a_divide_by_zero() {
        let rb = HeapRb::<f32>::new(16);
        let (mut prod, cons) = rb.split();
        push_mono(&[1.0f32, 2.0], 0, &mut prod);
        assert_eq!(cons.occupied_len(), 0);
    }

    #[test]
    fn a_full_ring_drops_samples_instead_of_blocking() {
        // The realtime callback must never block; try_push failing is the
        // designed behaviour when the worker stalls.
        let rb = HeapRb::<f32>::new(2);
        let (mut prod, cons) = rb.split();
        push_mono(&[0.1f32, 0.2, 0.3, 0.4], 1, &mut prod);
        assert_eq!(cons.occupied_len(), 2);
    }
}
