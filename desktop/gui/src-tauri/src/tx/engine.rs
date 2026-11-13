//! The transmit engine: pure playback bookkeeping, kept free of threads, audio
//! devices and IPC so every piece is unit-testable.
//!
//! The waveform arrives here fully encoded — leader, VIS code and every scan
//! line as one phase-continuous buffer (see `dsp::encode`). This module's whole
//! job is to hand that buffer to an audio sink *unaltered*: playback starts at
//! sample 0 (a clipped leader loses the receiver's sync — CLAUDE.md, "TX audio
//! pipeline") and runs through the final sample before any silence padding.

use cpal::{FromSample, Sample};
use serde::Serialize;

/// Everything the transmitter reports upward, forwarded to the UI on the
/// "tx-event" channel. Adjacently tagged to match `RxEvent`'s wire shape.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "event", content = "data", rename_all = "lowercase")]
pub enum TxEvent {
    /// Periodic playback position, for the progress bar.
    Progress {
        elapsed_seconds: f64,
        total_seconds: f64,
        /// 0..1, clamped.
        fraction: f64,
    },
    /// The whole waveform was played (and given time to drain).
    Complete,
    /// The operator stopped the transmission early.
    Cancelled { elapsed_seconds: f64 },
    Error(String),
}

/// Fill an interleaved output buffer from the mono waveform, starting at
/// sample index `pos`. Each mono sample is replicated across all `channels`;
/// once the waveform is exhausted the remaining frames are silence. Returns
/// the new position (== `samples.len()` once playback has finished).
///
/// Called on the realtime audio thread — no allocation, no locking. The head
/// of the buffer is never skipped and the tail is never dropped: `pos` only
/// ever advances by exactly the frames written.
pub fn fill_frames<T>(samples: &[f32], pos: usize, out: &mut [T], channels: usize) -> usize
where
    T: Sample + FromSample<f32>,
{
    if channels == 0 {
        return pos;
    }
    let mut p = pos;
    for frame in out.chunks_mut(channels) {
        let v = if p < samples.len() {
            let s = samples[p];
            p += 1;
            s
        } else {
            0.0
        };
        for slot in frame.iter_mut() {
            *slot = T::from_sample(v);
        }
    }
    p
}

/// Playback position as (elapsed seconds, fraction 0..1). `played` counts
/// samples already handed to the device, which can briefly run ahead of what
/// is audible (device buffering), so both values are clamped to the total —
/// the bar must never overshoot 100 %.
pub fn progress(played: usize, total_samples: usize, sample_rate: u32) -> (f64, f64) {
    if total_samples == 0 || sample_rate == 0 {
        return (0.0, 0.0);
    }
    let clamped = played.min(total_samples);
    let elapsed = clamped as f64 / sample_rate as f64;
    let fraction = clamped as f64 / total_samples as f64;
    (elapsed, fraction)
}

/// Guard for `start_tx`: one transmission at a time. Rejecting rather than
/// restarting is deliberate — an accidental second Send must not cut a
/// transmission that is already on the air.
pub fn ensure_idle(tx_running: bool) -> Result<(), String> {
    if tx_running {
        return Err("a transmission is already in progress — stop it first".into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fill_starts_at_sample_zero_and_never_skips_the_head() {
        // The classic clipped-leader bug: playback that starts anywhere but
        // sample 0. The first buffer handed out must be the head verbatim.
        let samples = vec![0.1f32, 0.2, 0.3, 0.4, 0.5];
        let mut out = vec![0.0f32; 3];
        let pos = fill_frames(&samples, 0, &mut out, 1);
        assert_eq!(pos, 3);
        assert_eq!(out, vec![0.1, 0.2, 0.3]);
    }

    #[test]
    fn fill_resumes_where_the_last_callback_stopped() {
        let samples = vec![0.1f32, 0.2, 0.3, 0.4, 0.5];
        let mut out = vec![0.0f32; 2];
        let pos = fill_frames(&samples, 3, &mut out, 1);
        assert_eq!(pos, 5);
        assert_eq!(out, vec![0.4, 0.5]);
    }

    #[test]
    fn fill_replicates_mono_across_all_channels() {
        let samples = vec![0.25f32, -0.5];
        let mut out = vec![0.0f32; 4]; // two stereo frames
        let pos = fill_frames(&samples, 0, &mut out, 2);
        assert_eq!(pos, 2);
        assert_eq!(out, vec![0.25, 0.25, -0.5, -0.5]);
    }

    #[test]
    fn fill_pads_silence_after_the_final_sample_without_dropping_it() {
        // The tail must be played in full; only then may silence follow.
        let samples = vec![0.7f32];
        let mut out = vec![9.0f32; 3];
        let pos = fill_frames(&samples, 0, &mut out, 1);
        assert_eq!(pos, 1);
        assert_eq!(out, vec![0.7, 0.0, 0.0]);
    }

    #[test]
    fn fill_past_the_end_is_pure_silence() {
        let samples = vec![0.7f32];
        let mut out = vec![9.0f32; 2];
        let pos = fill_frames(&samples, 1, &mut out, 1);
        assert_eq!(pos, 1);
        assert_eq!(out, vec![0.0, 0.0]);
    }

    #[test]
    fn fill_converts_to_integer_formats() {
        let samples = vec![1.0f32, -1.0, 0.0];
        let mut out = vec![0i16; 3];
        fill_frames(&samples, 0, &mut out, 1);
        assert_eq!(out[0], i16::MAX);
        assert!(out[1] <= i16::MIN + 1);
        assert_eq!(out[2], 0);
    }

    #[test]
    fn fill_with_zero_channels_is_a_no_op() {
        let samples = vec![0.1f32];
        let mut out = vec![5.0f32; 2];
        assert_eq!(fill_frames(&samples, 0, &mut out, 0), 0);
        assert_eq!(out, vec![5.0, 5.0]);
    }

    #[test]
    fn progress_tracks_elapsed_time_and_fraction() {
        let (elapsed, fraction) = progress(24_000, 48_000, 48_000);
        assert!((elapsed - 0.5).abs() < 1e-9);
        assert!((fraction - 0.5).abs() < 1e-9);
    }

    #[test]
    fn progress_clamps_device_buffering_overshoot() {
        // `played` counts samples handed to the device, which runs slightly
        // ahead of the speaker — never show more than 100 %.
        let (elapsed, fraction) = progress(50_000, 48_000, 48_000);
        assert!((elapsed - 1.0).abs() < 1e-9);
        assert!((fraction - 1.0).abs() < 1e-9);
    }

    #[test]
    fn progress_survives_degenerate_inputs() {
        assert_eq!(progress(100, 0, 48_000), (0.0, 0.0));
        assert_eq!(progress(100, 48_000, 0), (0.0, 0.0));
    }

    #[test]
    fn a_second_send_is_rejected_while_on_the_air() {
        assert!(ensure_idle(false).is_ok());
        let err = ensure_idle(true).unwrap_err();
        assert!(err.contains("already in progress"), "got {err}");
    }

    #[test]
    fn tx_events_serialize_like_rx_events() {
        // The UI switches on {event, data}; pin the wire shape.
        let json = serde_json::to_value(TxEvent::Progress {
            elapsed_seconds: 1.5,
            total_seconds: 3.0,
            fraction: 0.5,
        })
        .unwrap();
        assert_eq!(json["event"], "progress");
        assert_eq!(json["data"]["elapsed_seconds"], 1.5);
        let done = serde_json::to_value(TxEvent::Complete).unwrap();
        assert_eq!(done["event"], "complete");
    }
}
