import { describe, expect, it } from "vitest";

import {
  describeTxEvent,
  letterboxRect,
  rgbaToArgb,
  txPercent,
  type TxEvent,
} from "./tx";

describe("letterboxRect", () => {
  it("fills the frame exactly when aspect ratios match", () => {
    expect(letterboxRect(640, 480, 320, 240)).toEqual({
      x: 0,
      y: 0,
      width: 320,
      height: 240,
    });
  });

  it("pillarboxes a tall image", () => {
    // 100x200 into 320x240: height-limited, scale 1.2 → 120x240 centered.
    expect(letterboxRect(100, 200, 320, 240)).toEqual({
      x: 100,
      y: 0,
      width: 120,
      height: 240,
    });
  });

  it("letterboxes a wide image", () => {
    // 640x100 into 320x240: width-limited, scale 0.5 → 320x50 centered.
    expect(letterboxRect(640, 100, 320, 240)).toEqual({
      x: 0,
      y: 95,
      width: 320,
      height: 50,
    });
  });

  it("upscales a small image rather than leaving it tiny", () => {
    const r = letterboxRect(32, 24, 320, 240);
    expect(r).toEqual({ x: 0, y: 0, width: 320, height: 240 });
  });

  it("never overflows the destination frame", () => {
    for (const [sw, sh] of [
      [1, 1000],
      [1000, 1],
      [317, 253],
      [641, 495],
    ]) {
      const r = letterboxRect(sw, sh, 320, 256);
      expect(r.x).toBeGreaterThanOrEqual(0);
      expect(r.y).toBeGreaterThanOrEqual(0);
      expect(r.x + r.width).toBeLessThanOrEqual(320);
      expect(r.y + r.height).toBeLessThanOrEqual(256);
    }
  });

  it("falls back to the full frame on degenerate input", () => {
    expect(letterboxRect(0, 0, 320, 240)).toEqual({
      x: 0,
      y: 0,
      width: 320,
      height: 240,
    });
  });
});

describe("rgbaToArgb", () => {
  it("repacks RGBA bytes into ARGB words", () => {
    const bytes = new Uint8ClampedArray([0x12, 0x34, 0x56, 0xff]);
    expect(rgbaToArgb(bytes)).toEqual([0xff123456]);
  });

  it("forces alpha opaque and stays unsigned", () => {
    // A translucent white pixel must transmit as opaque white, and the word
    // must be a positive number (0xFF... would be negative with plain <<).
    const bytes = new Uint8ClampedArray([0xff, 0xff, 0xff, 0x10]);
    const [p] = rgbaToArgb(bytes);
    expect(p).toBe(0xffffffff);
    expect(p).toBeGreaterThan(0);
  });

  it("converts every pixel of a multi-pixel buffer in order", () => {
    const bytes = new Uint8ClampedArray([
      255, 0, 0, 255, // red
      0, 255, 0, 255, // green
      0, 0, 255, 255, // blue
    ]);
    expect(rgbaToArgb(bytes)).toEqual([0xffff0000, 0xff00ff00, 0xff0000ff]);
  });
});

describe("txPercent", () => {
  it("maps a fraction to 0..100", () => {
    expect(txPercent(0)).toBe(0);
    expect(txPercent(0.5)).toBe(50);
    expect(txPercent(1)).toBe(100);
  });

  it("clamps overshoot from device buffering and bad input", () => {
    expect(txPercent(1.02)).toBe(100);
    expect(txPercent(-0.1)).toBe(0);
  });
});

describe("describeTxEvent", () => {
  it("silences the high-rate progress events", () => {
    const e: TxEvent = {
      event: "progress",
      data: { elapsed_seconds: 1, total_seconds: 2, fraction: 0.5 },
    };
    expect(describeTxEvent(e)).toBeNull();
  });

  it("logs terminal events", () => {
    expect(describeTxEvent({ event: "complete" })).toContain("complete");
    expect(
      describeTxEvent({ event: "cancelled", data: { elapsed_seconds: 12.34 } }),
    ).toContain("12.3 s");
    expect(describeTxEvent({ event: "error", data: "boom" })).toContain("boom");
  });
});
