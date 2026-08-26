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

struct AppState {
    /// The running receiver, if any. `RxService` stops capture when dropped.
    rx: Mutex<Option<RxService>>,
    events: Sender<RxEvent>,
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

fn main() {
    let (events, evt_rx) = channel::<RxEvent>();

    tauri::Builder::default()
        .manage(AppState {
            rx: Mutex::new(None),
            events,
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
        ])
        .run(tauri::generate_context!())
        .expect("error running SSTVAF");
}
