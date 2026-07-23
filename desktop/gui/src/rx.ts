// Pure helpers for the RX screen. Kept out of the component so the pixel and
// formatting logic is unit-testable without a DOM.

import type { ModeInfo } from "./ipc";

export type RxState =
  | { kind: "idle" }
  | { kind: "leader" }
  | {
      kind: "decoding";
      mode: ModeInfo;
      rows: number;
      total_rows: number;
      quality: number;
      slant_ppm: number;
    }
  | { kind: "complete"; mode: ModeInfo; rows: number; quality: number }
  | { kind: "aborted"; mode: ModeInfo | null; rows: number };

export type RxEvent =
  | { event: "state"; data: RxState }
  | { event: "rows"; data: { first_row: number; width: number; pixels: number[] } }
  | { event: "info"; data: string }
  | { event: "error"; data: string };

/**
 * Convert the codec's 0xAARRGGBB pixels into the RGBA byte order canvas
 * `ImageData` expects. Alpha is forced opaque: the decoder leaves it set, but a
 * partially-decoded row that arrives with alpha 0 would paint as an invisible
 * line rather than a visible one.
 */
// The return type is inferred rather than annotated: TypeScript's
// `Uint8ClampedArray` is generic over its buffer, and the bare annotation
// widens to ArrayBufferLike, which `new ImageData(...)` rejects.
export function argbToRgba(pixels: number[]) {
  const out = new Uint8ClampedArray(pixels.length * 4);
  for (let i = 0; i < pixels.length; i++) {
    const p = pixels[i];
    out[i * 4] = (p >> 16) & 0xff;
    out[i * 4 + 1] = (p >> 8) & 0xff;
    out[i * 4 + 2] = p & 0xff;
    out[i * 4 + 3] = 0xff;
  }
  return out;
}

/** The mode the state refers to, if any — the canvas sizes itself from this. */
export function stateMode(state: RxState): ModeInfo | null {
  switch (state.kind) {
    case "decoding":
    case "complete":
      return state.mode;
    case "aborted":
      return state.mode;
    default:
      return null;
  }
}

/** One-line status for the header. */
export function describeRxState(state: RxState): string {
  switch (state.kind) {
    case "idle":
      return "Listening…";
    case "leader":
      return "Signal detected — reading VIS";
    case "decoding":
      return `${state.mode.name} — line ${state.rows} of ${state.total_rows}`;
    case "complete":
      return `${state.mode.name} — image complete`;
    case "aborted":
      return state.mode
        ? `${state.mode.name} — signal lost after ${state.rows} lines`
        : "Signal lost";
  }
}

/** 0..100 for the progress bar, or null when there is nothing to show. */
export function progressPercent(state: RxState): number | null {
  if (state.kind === "decoding") {
    if (state.total_rows <= 0) return null;
    return Math.min(100, (state.rows / state.total_rows) * 100);
  }
  if (state.kind === "complete") return 100;
  return null;
}

/**
 * Signal quality as a percentage, or null when the decoder isn't reporting one.
 * Only meaningful while a transmission is being decoded.
 */
export function qualityPercent(state: RxState): number | null {
  if (state.kind === "decoding" || state.kind === "complete") {
    return Math.round(Math.max(0, Math.min(1, state.quality)) * 100);
  }
  return null;
}

/**
 * Slant readout. The decoder reports 0 ppm until it has tracked enough syncs,
 * which is not the same as "perfectly on frequency", so that case shows nothing
 * rather than a falsely precise 0.
 */
export function describeSlant(state: RxState): string | null {
  if (state.kind !== "decoding") return null;
  if (state.slant_ppm === 0) return null;
  return `${state.slant_ppm > 0 ? "+" : ""}${state.slant_ppm.toFixed(1)} ppm`;
}
