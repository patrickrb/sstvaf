// SSTVAF desktop — Tauri entry point. Owns the receiver, exposes commands to
// the web UI, and forwards RX events to the webview.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::sync::mpsc::{channel, Sender};
use std::sync::Mutex;

use tauri::{Emitter, Manager, State};

use sstvaf::audio::{self, AudioDevice};
use sstvaf::dsp::encode;
use sstvaf::modes::{ModeInfo, MODES};
use sstvaf::rx::{RxEvent, RxService};
use sstvaf::tx::{TxEvent, TxService};

struct AppState {
    /// The running receiver, if any. `RxService` stops capture when dropped.
    rx: Mutex<Option<RxService>>,
    events: Sender<RxEvent>,
    /// The transmission in flight, if any. `TxService` stops playback when
    /// dropped; a finished one lingers here until the next `start_tx`.
    tx: Mutex<Option<TxService>>,
    tx_events: Sender<TxEvent>,
}

/// What `start_rx` tells the UI about the device it actually opened — which may
/// not be the one requested, since an unplugged device falls back to the system
/// default.
#[derive(serde::Serialize)]
struct RxStarted {
    device_name: String,
    device_rate: u32,
    codec_rate: u32,
}

// --- codec / device enumeration ---------------------------------------------

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
    let mode =
        sstvaf::modes::mode_by_id(mode_id).ok_or_else(|| format!("unknown mode {mode_id}"))?;
    let n = encode::num_samples(mode, sample_rate).map_err(|e| e.to_string())?;
    Ok(n as f64 / sample_rate as f64)
}

#[tauri::command]
fn list_audio_inputs() -> Vec<AudioDevice> {
    audio::list_input_devices()
}

// --- receive ----------------------------------------------------------------

/// Start decoding from `device` (or the system default). Restarts cleanly if a
/// receiver is already running, so switching devices needs no separate stop.
#[tauri::command]
fn start_rx(state: State<AppState>, device: Option<String>) -> Result<RxStarted, String> {
    let mut slot = state.rx.lock().map_err(|e| e.to_string())?;
    // Drop the old service first: it owns the audio device, and some backends
    // refuse a second exclusive open.
    *slot = None;
    let service = RxService::start(device, state.events.clone()).map_err(|e| e.to_string())?;
    let started = RxStarted {
        device_name: service.device_name.clone(),
        device_rate: service.device_rate,
        codec_rate: sstvaf::rx::SAMPLE_RATE,
    };
    *slot = Some(service);
    Ok(started)
}

#[tauri::command]
fn stop_rx(state: State<AppState>) -> Result<(), String> {
    let mut slot = state.rx.lock().map_err(|e| e.to_string())?;
    *slot = None;
    Ok(())
}

#[tauri::command]
fn is_rx_running(state: State<AppState>) -> bool {
    state.rx.lock().map(|slot| slot.is_some()).unwrap_or(false)
}

// --- transmit ---------------------------------------------------------------

#[tauri::command]
fn list_audio_outputs() -> Vec<AudioDevice> {
    audio::list_output_devices()
}

/// What `start_tx` tells the UI about the transmission it just put on the air.
#[derive(serde::Serialize)]
struct TxStarted {
    device_name: String,
    /// The rate the waveform was encoded and is being played at — the output
    /// device's native rate.
    sample_rate: u32,
    total_samples: usize,
    total_seconds: f64,
}

/// Encode `pixels` (0xAARRGGBB, row-major, exactly the mode's dimensions) and
/// play the transmission on `device` (or the system default). One transmission
/// at a time: a Send while on the air is rejected rather than cutting the
/// running one. Progress/completion arrives on the "tx-event" channel.
#[tauri::command]
fn start_tx(
    state: State<AppState>,
    device: Option<String>,
    mode_id: i32,
    pixels: Vec<u32>,
) -> Result<TxStarted, String> {
    let mut slot = state.tx.lock().map_err(|e| e.to_string())?;
    sstvaf::tx::ensure_idle(slot.as_ref().is_some_and(|s| s.is_running()))?;
    // Drop any finished service first — it owns a joined thread, nothing more.
    *slot = None;
    let service = TxService::start(device, mode_id, pixels, state.tx_events.clone())
        .map_err(|e| e.to_string())?;
    let started = TxStarted {
        device_name: service.device_name.clone(),
        sample_rate: service.sample_rate,
        total_samples: service.total_samples,
        total_seconds: service.total_samples as f64 / service.sample_rate as f64,
    };
    *slot = Some(service);
    Ok(started)
}

#[tauri::command]
fn stop_tx(state: State<AppState>) -> Result<(), String> {
    let mut slot = state.tx.lock().map_err(|e| e.to_string())?;
    *slot = None;
    Ok(())
}

#[tauri::command]
fn is_tx_running(state: State<AppState>) -> bool {
    state
        .tx
        .lock()
        .map(|slot| slot.as_ref().is_some_and(|s| s.is_running()))
        .unwrap_or(false)
}

fn main() {
    let (events, evt_rx) = channel::<RxEvent>();
    let (tx_events, tx_evt_rx) = channel::<TxEvent>();

    tauri::Builder::default()
        .manage(AppState {
            rx: Mutex::new(None),
            events,
            tx: Mutex::new(None),
            tx_events,
        })
        .setup(move |app| {
            // Forward RX events to the webview on the "rx-event" channel.
            let handle = app.handle().clone();
            std::thread::Builder::new()
                .name("sstvaf-event-forwarder".into())
                .spawn(move || {
                    while let Ok(ev) = evt_rx.recv() {
                        // Mirror status to the terminal: the app has no logger,
                        // so audio/decode problems would otherwise be invisible
                        // outside the in-app status line and impossible to
                        // diagnose remotely.
                        match &ev {
                            RxEvent::Error(m) => eprintln!("[sstvaf] ERROR: {m}"),
                            RxEvent::Info(m) => eprintln!("[sstvaf] {m}"),
                            _ => {}
                        }
                        let _ = handle.emit("rx-event", ev);
                    }
                })?;
            // Forward TX events to the webview on the "tx-event" channel.
            let tx_handle = app.handle().clone();
            std::thread::Builder::new()
                .name("sstvaf-tx-event-forwarder".into())
                .spawn(move || {
                    while let Ok(ev) = tx_evt_rx.recv() {
                        if let TxEvent::Error(m) = &ev {
                            eprintln!("[sstvaf] TX ERROR: {m}");
                        }
                        let _ = tx_handle.emit("tx-event", ev);
                    }
                })?;
            Ok(())
        })
        .on_window_event(|window, event| {
            // Stop capture before the process tears down, so the audio device
            // is released cleanly rather than by the OS.
            if let tauri::WindowEvent::Destroyed = event {
                if let Some(state) = window.app_handle().try_state::<AppState>() {
                    if let Ok(mut slot) = state.rx.lock() {
                        *slot = None;
                    }
                    if let Ok(mut slot) = state.tx.lock() {
                        *slot = None;
                    }
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            list_modes,
            tx_duration_seconds,
            list_audio_inputs,
            start_rx,
            stop_rx,
            is_rx_running,
            list_audio_outputs,
            start_tx,
            stop_tx,
            is_tx_running,
        ])
        .run(tauri::generate_context!())
        .expect("error running SSTVAF");
}
