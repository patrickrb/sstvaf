// Typed bridge to the Rust backend. Every `invoke` the UI makes goes through a
// wrapper here so the command names and payload shapes live in one place and
// stay matched to `src-tauri/src/main.rs`.

import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

import type { RxEvent } from "./rx";

/** Mirrors `modes::ModeInfo` (serde renames nothing — snake_case on both sides). */
export interface ModeInfo {
  id: number;
  name: string;
  short_code: string;
  width: number;
  height: number;
  vis_code: number;
  tx_seconds: number;
}

/** Mirrors `audio::AudioDevice`. */
export interface AudioDevice {
  id: string;
  name: string;
  is_default: boolean;
  default_sample_rate: number;
  channels: number;
}

/** Mirrors `RxStarted` — the device actually opened. */
export interface RxStarted {
  device_name: string;
  device_rate: number;
  codec_rate: number;
}

export function listModes(): Promise<ModeInfo[]> {
  return invoke<ModeInfo[]>("list_modes");
}

export function txDurationSeconds(
  modeId: number,
  sampleRate: number,
): Promise<number> {
  // Tauri v2 converts camelCase JS arg keys to the snake_case Rust params.
  return invoke<number>("tx_duration_seconds", { modeId, sampleRate });
}

export function listAudioInputs(): Promise<AudioDevice[]> {
  return invoke<AudioDevice[]>("list_audio_inputs");
}

/** Start (or restart) the receiver. `device` null means the system default. */
export function startRx(device: string | null): Promise<RxStarted> {
  return invoke<RxStarted>("start_rx", { device });
}

export function stopRx(): Promise<void> {
  return invoke<void>("stop_rx");
}

export function isRxRunning(): Promise<boolean> {
  return invoke<boolean>("is_rx_running");
}

/** Subscribe to the RX event stream the backend emits on "rx-event". */
export function onRxEvent(handler: (e: RxEvent) => void): Promise<UnlistenFn> {
  return listen<RxEvent>("rx-event", (e) => handler(e.payload));
}

/** `mm:ss` for a duration in seconds — used for TX length readouts. */
export function formatDuration(seconds: number): string {
  const total = Math.max(0, Math.round(seconds));
  const mm = Math.floor(total / 60);
  const ss = total % 60;
  return `${mm}:${ss.toString().padStart(2, "0")}`;
}

/** Human-readable device label for the picker. */
export function describeDevice(d: AudioDevice): string {
  const rate =
    d.default_sample_rate > 0 ? `${(d.default_sample_rate / 1000).toFixed(1)} kHz` : "";
  const ch =
    d.channels === 1
      ? "mono"
      : d.channels === 2
        ? "stereo"
        : d.channels > 0
          ? `${d.channels} ch`
          : "";
  const specs = [rate, ch].filter(Boolean).join(", ");
  return specs ? `${d.name} (${specs})` : d.name;
}
