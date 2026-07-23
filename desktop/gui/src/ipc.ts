// Typed bridge to the Rust backend. Every `invoke` the UI makes goes through a
// wrapper here so the command names and payload shapes live in one place and
// stay matched to `src-tauri/src/main.rs`.

import { invoke } from "@tauri-apps/api/core";

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

export function listModes(): Promise<ModeInfo[]> {
  return invoke<ModeInfo[]>("list_modes");
}

export function txDurationSeconds(
  modeId: number,
  sampleRate: number,
): Promise<number> {
  return invoke<number>("tx_duration_seconds", {
    modeId,
    sampleRate,
  });
}

/** `mm:ss` for a duration in seconds — used for TX length readouts. */
export function formatDuration(seconds: number): string {
  const total = Math.max(0, Math.round(seconds));
  const mm = Math.floor(total / 60);
  const ss = total % 60;
  return `${mm}:${ss.toString().padStart(2, "0")}`;
}
