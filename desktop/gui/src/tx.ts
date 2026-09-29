// Pure helpers for the TX panel. Kept out of the component so the image
// geometry and pixel-format logic is unit-testable without a DOM.

/** Mirrors `TxStarted` in `src-tauri/src/main.rs`. */
export interface TxStarted {
  device_name: string;
  sample_rate: number;
  total_samples: number;
  total_seconds: number;
}

/** Mirrors `tx::TxEvent` — adjacently tagged like `RxEvent`. */
export type TxEvent =
  | {
      event: "progress";
      data: { elapsed_seconds: number; total_seconds: number; fraction: number };
    }
  | { event: "complete" }
  | { event: "cancelled"; data: { elapsed_seconds: number } }
  | { event: "error"; data: string };

export interface Rect {
  x: number;
  y: number;
  width: number;
  height: number;
}

/**
 * Where to draw a `srcW`x`srcH` image inside a `dstW`x`dstH` frame so it is
 * scaled as large as possible without cropping or distortion, centered, with
 * letterbox/pillarbox bars filling the rest. The mode's frame is always sent
 * in full — bars are transmitted as black rather than stretching the image.
 */
export function letterboxRect(
  srcW: number,
  srcH: number,
  dstW: number,
  dstH: number,
): Rect {
  if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
    return { x: 0, y: 0, width: dstW, height: dstH };
  }
  const scale = Math.min(dstW / srcW, dstH / srcH);
  const width = Math.round(srcW * scale);
  const height = Math.round(srcH * scale);
  return {
    x: Math.round((dstW - width) / 2),
    y: Math.round((dstH - height) / 2),
    width,
    height,
  };
}

/**
 * Convert canvas `ImageData` RGBA bytes into the 0xAARRGGBB words the codec
 * consumes. Alpha is forced opaque — the encoder reads only RGB, but a
 * translucent pixel from the canvas should still transmit its color rather
 * than whatever the alpha happened to be. `>>> 0` keeps the words unsigned so
 * they serialize as positive numbers for the Rust `Vec<u32>`.
 */
export function rgbaToArgb(bytes: Uint8ClampedArray | number[]): number[] {
  const pixels = Math.floor(bytes.length / 4);
  const out = new Array<number>(pixels);
  for (let i = 0; i < pixels; i++) {
    const r = bytes[i * 4];
    const g = bytes[i * 4 + 1];
    const b = bytes[i * 4 + 2];
    out[i] = ((0xff << 24) | (r << 16) | (g << 8) | b) >>> 0;
  }
  return out;
}

/** 0..100 for the TX progress bar, clamped so it never overshoots. */
export function txPercent(fraction: number): number {
  return Math.max(0, Math.min(1, fraction)) * 100;
}

/** One-line log entry per TX event, or null for events not worth a log line. */
export function describeTxEvent(e: TxEvent): string | null {
  switch (e.event) {
    case "progress":
      return null; // ~5 lines/second would drown the log
    case "complete":
      return "transmission complete";
    case "cancelled":
      return `transmission stopped after ${e.data.elapsed_seconds.toFixed(1)} s`;
    case "error":
      return `⚠ ${e.data}`;
  }
}
