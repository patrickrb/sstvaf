//! End-to-end codec check: encode an image, feed the waveform back through the
//! decoder the way the live audio path will, and compare the result.
//!
//! This is the test that proves the desktop port is wired to the same codec the
//! phone runs — the C sources are compiled by `build.rs` from the Android tree,
//! so a regression in either shows up here.

use sstvaf::dsp::decoder::{DecodeStatus, Decoder};
use sstvaf::dsp::encode::{self, ENCODE_AMPLITUDE};
use sstvaf::modes::{ModeInfo, MODES};

const SAMPLE_RATE: u32 = 12_000;
/// The chunk the live RX path delivers: 200 ms at 12 kHz, matching the Android
/// listener's monitor buffer. Decoding must not depend on the block size.
const CHUNK: usize = 2_400;

/// A deterministic diagonal RGB gradient at the mode's native size.
fn gradient(mode: &ModeInfo) -> Vec<u32> {
    let (w, h) = (mode.width as usize, mode.height as usize);
    let mut px = vec![0u32; w * h];
    for y in 0..h {
        for x in 0..w {
            let r = (x * 255 / w) as u32;
            let g = (y * 255 / h) as u32;
            let b = ((x + y) * 255 / (w + h)) as u32;
            px[y * w + x] = 0xFF00_0000 | (r << 16) | (g << 8) | b;
        }
    }
    px
}

fn channels(argb: u32) -> (i32, i32, i32) {
    (
        ((argb >> 16) & 0xFF) as i32,
        ((argb >> 8) & 0xFF) as i32,
        (argb & 0xFF) as i32,
    )
}

/// Mean absolute per-channel error between two same-size ARGB images (0..255).
fn mean_abs_error(a: &[u32], b: &[u32]) -> f64 {
    assert_eq!(a.len(), b.len());
    let mut sum = 0i64;
    for (x, y) in a.iter().zip(b.iter()) {
        let (ar, ag, ab) = channels(*x);
        let (br, bg, bb) = channels(*y);
        sum += (ar - br).abs() as i64 + (ag - bg).abs() as i64 + (ab - bb).abs() as i64;
    }
    sum as f64 / (a.len() * 3) as f64
}

/// Encode `mode`, push the waveform through a fresh decoder in `CHUNK` blocks
/// with silence either side, and return the decoded frame.
fn roundtrip(mode: &ModeInfo, source: &[u32]) -> Vec<u32> {
    let mut dec = Decoder::new(SAMPLE_RATE).expect("decoder");
    roundtrip_on(&mut dec, mode, source)
}

/// As `roundtrip`, on a caller-supplied decoder — so a single handle can be
/// driven through more than one transmission.
fn roundtrip_on(dec: &mut Decoder, mode: &ModeInfo, source: &[u32]) -> Vec<u32> {
    let samples = encode::encode(mode, source, SAMPLE_RATE, ENCODE_AMPLITUDE).expect("encode");

    // Half a second of silence first: the decoder must find the leader in a
    // live stream, not assume the transmission starts at sample 0.
    dec.push(&vec![0.0f32; SAMPLE_RATE as usize / 2]);
    for block in samples.chunks(CHUNK) {
        dec.push(block);
    }
    // The decoder needs to see the end of the final line; trailing silence is
    // what the live path would deliver next.
    dec.push(&vec![0.0f32; SAMPLE_RATE as usize / 2]);

    assert_eq!(
        dec.status(),
        DecodeStatus::Done,
        "{}: decoder ended in {:?}, rows={} quality={:.2}",
        mode.name,
        dec.status(),
        dec.rows_ready(),
        dec.quality()
    );
    assert_eq!(
        dec.mode().map(|m| m.id),
        Some(mode.id),
        "{}: VIS locked the wrong mode",
        mode.name
    );
    assert_eq!(
        dec.rows_ready(),
        mode.height as usize,
        "{}: incomplete image",
        mode.name
    );

    let mut out = vec![0u32; mode.pixel_count()];
    let copied = dec.read_rows(0, mode.height as usize, &mut out);
    assert_eq!(copied, mode.height as usize, "{}: short read", mode.name);
    out
}

#[test]
fn every_mode_round_trips() {
    for mode in MODES.iter() {
        let source = gradient(mode);
        let decoded = roundtrip(mode, &source);
        let err = mean_abs_error(&source, &decoded);
        // Loopback is noiseless, so the only error is the codec's own lossiness
        // (chroma subsampling in Robot 36 / PD, line-rate quantization). The
        // bound is generous enough for the worst mode but far below what a
        // mis-decoded image (wrong component order, slant, off-by-one line)
        // would produce.
        assert!(
            err < 12.0,
            "{}: mean abs error {err:.2}/255 is too high",
            mode.name
        );
    }
}

#[test]
fn decoder_reports_a_high_quality_on_a_clean_signal() {
    let mode = &MODES[0];
    let samples =
        encode::encode(mode, &gradient(mode), SAMPLE_RATE, ENCODE_AMPLITUDE).expect("encode");
    let mut dec = Decoder::new(SAMPLE_RATE).expect("decoder");
    for block in samples.chunks(CHUNK) {
        dec.push(block);
    }
    assert!(
        dec.quality() > 0.8,
        "clean loopback reported quality {:.2}",
        dec.quality()
    );
    // Encoder and decoder share one clock here, so there is nothing to slant.
    assert!(
        dec.slant_ppm().abs() < 200.0,
        "clean loopback reported {:.0} ppm slant",
        dec.slant_ppm()
    );
}

#[test]
fn done_needs_audio_past_the_final_line() {
    // The decoder only declares DONE once it has seen audio beyond the last
    // scan line — feeding exactly the encoded samples leaves it mid-IMAGE, two
    // rows short. The live RX path always has audio still flowing, so this is
    // never visible there, but any offline "decode this WAV file" path has to
    // pad the tail or it will report a truncated image.
    let mode = &MODES[0];
    let samples =
        encode::encode(mode, &gradient(mode), SAMPLE_RATE, ENCODE_AMPLITUDE).expect("encode");

    let mut dec = Decoder::new(SAMPLE_RATE).expect("decoder");
    for block in samples.chunks(CHUNK) {
        dec.push(block);
    }
    assert_eq!(dec.status(), DecodeStatus::Image);
    assert!(dec.rows_ready() >= mode.height as usize - 4);

    dec.push(&vec![0.0f32; SAMPLE_RATE as usize / 2]);
    assert_eq!(dec.status(), DecodeStatus::Done);
    assert_eq!(dec.rows_ready(), mode.height as usize);
}

#[test]
fn reset_returns_the_decoder_to_hunting_and_it_decodes_again() {
    let mode = &MODES[0];
    let source = gradient(mode);

    let mut dec = Decoder::new(SAMPLE_RATE).expect("decoder");
    let first = roundtrip_on(&mut dec, mode, &source);
    assert!(mean_abs_error(&source, &first) < 12.0);

    // This is what the RX engine does after snapshotting a finished frame.
    dec.reset();
    assert_eq!(dec.status(), DecodeStatus::Idle);
    assert_eq!(dec.rows_ready(), 0);
    assert!(dec.mode().is_none());

    // A second transmission on the same handle must decode just as well — no
    // state may survive the reset and skew the line timing.
    let second = roundtrip_on(&mut dec, mode, &source);
    assert_eq!(first, second, "decode differed before and after reset");
}

#[test]
fn a_truncated_transmission_aborts_and_keeps_the_partial_image() {
    let mode = &MODES[0];
    let source = gradient(mode);
    let samples = encode::encode(mode, &source, SAMPLE_RATE, ENCODE_AMPLITUDE).expect("encode");

    let mut dec = Decoder::new(SAMPLE_RATE).expect("decoder");
    // Cut the signal off just past halfway, then feed silence — the operator
    // losing the signal mid-image.
    let cut = samples.len() / 2;
    for block in samples[..cut].chunks(CHUNK) {
        dec.push(block);
    }
    let rows_at_cut = dec.rows_ready();
    dec.push(&vec![0.0f32; SAMPLE_RATE as usize * 3]);

    assert_eq!(dec.status(), DecodeStatus::Aborted);
    assert!(rows_at_cut > 0, "no rows decoded before the cut");
    assert!(
        dec.rows_ready() >= rows_at_cut,
        "partial image was discarded on abort"
    );
    assert!(dec.rows_ready() < mode.height as usize);
}
