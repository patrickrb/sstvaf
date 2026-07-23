import { describe, expect, it } from "vitest";

import type { ModeInfo } from "./ipc";
import {
  argbToRgba,
  describeRxState,
  describeSlant,
  progressPercent,
  qualityPercent,
  stateMode,
  type RxState,
} from "./rx";

const ROBOT36: ModeInfo = {
  id: 0,
  name: "Robot 36",
  short_code: "R36",
  width: 320,
  height: 240,
  vis_code: 8,
  tx_seconds: 36.91,
};

const decoding = (over: Partial<Extract<RxState, { kind: "decoding" }>> = {}): RxState => ({
  kind: "decoding",
  mode: ROBOT36,
  rows: 60,
  total_rows: 240,
  quality: 0.8,
  slant_ppm: 0,
  ...over,
});

describe("argbToRgba", () => {
  it("reorders channels and forces alpha opaque", () => {
    // 0xAARRGGBB -> R,G,B,A
    const out = argbToRgba([0x00112233]);
    expect(Array.from(out)).toEqual([0x11, 0x22, 0x33, 0xff]);
  });

  it("handles a full row without dropping pixels", () => {
    const row = new Array(320).fill(0xffff0000);
    const out = argbToRgba(row);
    expect(out.length).toBe(320 * 4);
    expect(Array.from(out.slice(0, 4))).toEqual([0xff, 0, 0, 0xff]);
  });

  it("returns an empty buffer for no pixels", () => {
    expect(argbToRgba([]).length).toBe(0);
  });
});

describe("describeRxState", () => {
  it("describes each state", () => {
    expect(describeRxState({ kind: "idle" })).toBe("Listening…");
    expect(describeRxState({ kind: "leader" })).toContain("VIS");
    expect(describeRxState(decoding())).toBe("Robot 36 — line 60 of 240");
    expect(
      describeRxState({ kind: "complete", mode: ROBOT36, rows: 240, quality: 0.9 }),
    ).toBe("Robot 36 — image complete");
    expect(describeRxState({ kind: "aborted", mode: ROBOT36, rows: 100 })).toBe(
      "Robot 36 — signal lost after 100 lines",
    );
  });

  it("handles an abort with no locked mode", () => {
    expect(describeRxState({ kind: "aborted", mode: null, rows: 0 })).toBe("Signal lost");
  });
});

describe("stateMode", () => {
  it("returns the mode once one is locked", () => {
    expect(stateMode(decoding())?.short_code).toBe("R36");
    expect(stateMode({ kind: "complete", mode: ROBOT36, rows: 1, quality: 1 })?.id).toBe(0);
  });

  it("returns null before a lock", () => {
    expect(stateMode({ kind: "idle" })).toBeNull();
    expect(stateMode({ kind: "leader" })).toBeNull();
    expect(stateMode({ kind: "aborted", mode: null, rows: 0 })).toBeNull();
  });
});

describe("progressPercent", () => {
  it("tracks decode progress", () => {
    expect(progressPercent(decoding({ rows: 120 }))).toBe(50);
  });

  it("is full on completion and absent otherwise", () => {
    expect(progressPercent({ kind: "complete", mode: ROBOT36, rows: 240, quality: 1 })).toBe(100);
    expect(progressPercent({ kind: "idle" })).toBeNull();
    expect(progressPercent({ kind: "aborted", mode: ROBOT36, rows: 10 })).toBeNull();
  });

  it("clamps a decoder that overshoots and survives a zero total", () => {
    expect(progressPercent(decoding({ rows: 999 }))).toBe(100);
    expect(progressPercent(decoding({ total_rows: 0 }))).toBeNull();
  });
});

describe("qualityPercent", () => {
  it("scales 0..1 to a percentage", () => {
    expect(qualityPercent(decoding({ quality: 0.83 }))).toBe(83);
  });

  it("clamps out-of-range values", () => {
    expect(qualityPercent(decoding({ quality: 1.4 }))).toBe(100);
    expect(qualityPercent(decoding({ quality: -0.2 }))).toBe(0);
  });

  it("is absent when nothing is being decoded", () => {
    expect(qualityPercent({ kind: "idle" })).toBeNull();
    expect(qualityPercent({ kind: "leader" })).toBeNull();
  });
});

describe("describeSlant", () => {
  it("signs the reading", () => {
    expect(describeSlant(decoding({ slant_ppm: 12.34 }))).toBe("+12.3 ppm");
    expect(describeSlant(decoding({ slant_ppm: -4 }))).toBe("-4.0 ppm");
  });

  it("shows nothing at zero, which means 'not yet measured'", () => {
    expect(describeSlant(decoding({ slant_ppm: 0 }))).toBeNull();
  });

  it("shows nothing outside a decode", () => {
    expect(describeSlant({ kind: "idle" })).toBeNull();
    expect(describeSlant({ kind: "complete", mode: ROBOT36, rows: 240, quality: 1 })).toBeNull();
  });
});
