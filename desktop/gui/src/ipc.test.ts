import { describe, expect, it } from "vitest";

import { formatDuration } from "./ipc";

describe("formatDuration", () => {
  it("renders sub-minute durations", () => {
    expect(formatDuration(36.91)).toBe("0:37");
    expect(formatDuration(0)).toBe("0:00");
  });

  it("renders minutes and pads the seconds", () => {
    expect(formatDuration(72.91)).toBe("1:13");
    expect(formatDuration(127.01304)).toBe("2:07");
    expect(formatDuration(120)).toBe("2:00");
  });

  it("clamps negatives rather than printing a negative clock", () => {
    expect(formatDuration(-5)).toBe("0:00");
  });
});
