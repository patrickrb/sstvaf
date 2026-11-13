# UI testing checklist

Layout bugs in this app almost never show up on the phone-portrait canvas the
author is looking at — they show up on the *other* shapes: a phone in landscape,
a tablet, a Chromebook window. The shell adapts by width (bottom tab bar below
600dp, side navigation rail at/above it — see `AdaptiveShell`), but an adaptive
shell only reflows *navigation*. Each tab's own content still has to survive a
short, wide viewport, and that is where things break.

**The failure mode to hunt for: a primary control pushed off-screen.** A landscape
viewport is short. Any screen that sizes a child by width and lets its height
follow — `fillMaxWidth().aspectRatio(...)`, a square preview, a media frame — can
produce a child taller than the whole viewport, shoving the button *inside or
below it* past the fold. If that button is the only way forward (pick an image,
transmit, save), the tab is dead on that device even though it looks fine in
portrait. Issue #20 shipped with exactly this on the TX tab.

## The matrix — every tab, every device, both orientations

UI testing is a full **tab × device × orientation** sweep, not a spot check. For
**each device** below, in **both portrait and landscape**, open **every tab** and
run the [per-screen checks](#per-screen-checks). That's 6 tabs × 2 orientations
per device — do the whole grid; the bug that bit issue #20 was invisible until
you looked at one specific cell (TX / tablet / landscape).

**Tabs:** RX · Gallery · TX · Waterfall · Logbook · Settings

**Devices (minimum coverage):**

| Class            | Representative                       | Why it's in the set                          |
|------------------|--------------------------------------|----------------------------------------------|
| Compact phone    | Pixel-class (~411dp portrait)        | Bottom-bar layout, the common case           |
| Tablet           | **Redmi Redpad 2, Android 16**       | The device in issue #20; rail in both orient.|
| Large phone / foldable | any ≥600dp-landscape phone     | Crosses the rail threshold on rotation       |
| Chromebook / desktop window | resizable window          | Free-resize hits widths the presets miss     |

Real hardware is required before closing an issue that touches responsive layout
(a physical Redmi Redpad 2 on Android 16 at minimum). For fast iteration, one
emulator reproduces the *layout* cell of the matrix without a device farm — force
the width and orientation directly:

```
# Landscape / wide (rail + short height) and back:
adb -s <serial> shell wm size 2400x1080
adb -s <serial> shell wm size reset

# A tablet-ish portrait cell (rail, tall):
adb -s <serial> shell wm size 1200x1920
```

(Direct `wm size` is more reliable than `settings put system user_rotation`,
which is flaky and often won't take when a dialog is up.)

## Per-screen checks

Screenshot the tab (`adb -s <serial> exec-out screencap -p > shot.png`) and, for
that tab in that orientation on that device, confirm:

- The tab's **primary action** is visible, or reachable by scrolling. If the
  screen scrolls, actually scroll it and confirm the control appears and is
  tappable — a control centered inside an over-tall box is technically "in the
  scroll range" but is a bug, not a pass.
- Nothing important is hidden **behind the TX strip** (the always-on strip sits
  between content and the bottom edge in both layouts).
- No image/preview frame is taller than the viewport. A mode-aspect SSTV frame
  (~4:3) at full landscape width is ~700dp tall on an ~450dp-tall canvas — cap
  it (`heightIn`/`widthIn`) so the surrounding controls stay on screen.
- All six navigation destinations are reachable. On a short canvas the rail
  scrolls; confirm the bottom entry (Settings) can still be tapped.

## Recording results

Note in the PR which cells of the matrix were exercised and on what
hardware/emulator, and file any off-screen/clipped control as its own issue (as
was done for the TX pick-image regression). **Attach the screencap** to the
issue — a layout bug is far easier to triage from the picture than the prose,
and you already captured it during the sweep. A tab that passes portrait but was
never opened in landscape is **not** tested.
