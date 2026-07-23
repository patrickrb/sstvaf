// SSTVAF desktop — Tauri entry point. Exposes the codec's capabilities to the
// web UI; the audio engine, gallery, TX and rig control land in later PRs.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use sstvaf::dsp::encode;
use sstvaf::modes::{ModeInfo, MODES};

/// The SSTV mode table (ids, dimensions, VIS codes, TX durations) as the codec
/// defines it — the UI never hardcodes mode metadata.
#[tauri::command]
fn list_modes() -> Vec<ModeInfo> {
    MODES.to_vec()
}

/// How long a transmission in `mode_id` will take at `sample_rate`, straight
/// from the encoder rather than the table, so the UI's countdown can't drift
/// from what will actually be played.
#[tauri::command]
fn tx_duration_seconds(mode_id: i32, sample_rate: u32) -> Result<f64, String> {
    let mode = sstvaf::modes::mode_by_id(mode_id).ok_or_else(|| format!("unknown mode {mode_id}"))?;
    let n = encode::num_samples(mode, sample_rate).map_err(|e| e.to_string())?;
    Ok(n as f64 / sample_rate as f64)
}

fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![list_modes, tx_duration_seconds])
        .run(tauri::generate_context!())
        .expect("error running SSTVAF");
}
