# SSTVAF

**SSTV on Android — picture QSOs from your phone.**

Receive slow-scan television images straight off the air into a built-in
gallery (and, optionally, your Photos app), compose and transmit your own
photos with callsign captions, and drive your radio over USB CAT — all from an
Android phone or tablet.

---

## Features

### Receive
- **Live decode view** — watch the image paint line by line as it arrives,
  with VIS auto-detection, slant correction, and signal quality feedback
- **Gallery tab** — every received image is saved automatically with mode,
  frequency, and UTC timestamp; browse, share, or delete from the app
- **Optional Photos export** — received images can also land in your
  device's Photos gallery
- **Background receive** — a foreground service keeps SSTVAF listening for
  SSTV images with the screen off

### Transmit
- **TX composer** — pick any photo, pan/zoom-crop it to the SSTV frame, and
  stamp callsign/caption overlays (color, size, position presets)
- **Pre-transmit confirmation** with mode and duration, plus a live progress
  bar while the image is on the air

### Nine SSTV modes
Scottie 1 / 2 · Martin 1 / 2 · Robot 36 / 72 · PD 50 / 90 / 120

### Radio control
- USB CAT control for the same rig set as FT8AF (Icom, Yaesu, Kenwood,
  Elecraft, Xiegu, FlexRadio, Guohe, truSDX, and more), USB audio, Bluetooth,
  and network rigs
- Band picker with SSTV calling frequencies, per-band TX level, tune carrier

### Logbook
- Manual SSTV QSO entry (callsign, grid, RSV exchange, mode, frequency)
- ADIF export with proper `MODE=SSTV` + `SUBMODE` (e.g. "Scottie 1") fields
- Cloudlog / Wavelog / Nextlog upload, stats, and award tracking

---

## Install

Grab the latest APK from the Releases page, or build it yourself (the Android
module still lives in the `sstvaf/` directory — the heritage name is kept so
history and tooling stay intact):

```bash
cd sstvaf
./gradlew installDebug
```

Windows: `cd sstvaf && gradlew.bat installDebug`. Builds need JDK 17
(AGP 8.7.3 / Gradle 8.9).

---

## Native code

The SSTV codec in `sstvaf/app/src/main/cpp/sstv_lib/` is a **clean-room
implementation** written for this project from published mode specifications
(timing tables, tone frequencies, VIS codes) — no GPL SSTV source was
consulted. See
[`sstvaf/app/src/main/cpp/sstv_lib/SOURCES.md`](sstvaf/app/src/main/cpp/sstv_lib/SOURCES.md)
for the exact specification sources. The codec and its JNI glue
(`cpp/sstvaf_glue/`) are built from source by the NDK/CMake toolchain into
`libsstvaf.so`, so a fresh clone builds with no manual steps. Host-side C
tests (golden waveform vectors, VIS detection, encode→decode round trips,
slant correction) run in CI on every PR.

Because the codec is plain, Android-free C11, it also builds as a small desktop
command-line tool. See [`desktop/`](desktop/README.md) for `sstvaf encode` /
`sstvaf decode` and a **cross-compiled 32-bit Windows MSI** (built from Linux
with the i686 MinGW toolchain + `wixl`).

---

## Heritage

SSTVAF is built from [FT8AF](https://github.com/patrickrb/FT8AF), which is in
turn a fork of [FT8CN](https://github.com/N0BOY/FT8CN) by **BG7YOZ** (hosted
by **N0BOY**). The rig CAT control, USB serial/audio stack, theming, and
logbook all carry over from that lineage; the FT8 engine was replaced by the
clean-room SSTV codec. Massive thanks to the FT8CN authors — none of this
exists without their work.

## License

MIT. See [LICENSE](LICENSE).

## Privacy

No ads, no analytics, no telemetry. See [privacy.md](privacy.md).

---

## About

Built by:
- **Patrick Burns — [K1AF](https://www.qrz.com/db/K1AF)**
- **Reid — [N0RC](https://www.qrz.com/db/N0RC)** (co-pilot, road-trip debugger, all-around enabler)

73.
