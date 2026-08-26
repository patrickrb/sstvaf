# SSTVAF Desktop (GUI)

A cross-platform (Windows / macOS / Linux) desktop port of the SSTVAF Android
app, built with **Tauri** (Rust backend + React/TypeScript frontend). It reuses
the exact clean-room SSTV codec the phone runs — `sstvaf/app/src/main/cpp/sstv_lib`,
compiled from source by `build.rs`, no JNI and no vendored copy.

This is the **GUI**. The sibling [`desktop/`](../README.md) CLI is a separate,
dependency-free C tool that ships a 32-bit Windows MSI (issue #40); the two
share only the codec sources and are built and released independently.

Target scope is parity with the Android app: RX, Gallery, TX composer,
Waterfall, Log and Settings, with **Hamlib** rig control for PTT and frequency.
See "Status" below for what has landed so far.

## Layout

```
desktop/gui/
  src/              React + TypeScript frontend (Vite)
    ipc.ts          typed command/event bridge to Rust
    rx.ts           pure RX helpers (ARGB->RGBA, status formatting)
    App.tsx         the UI
  src-tauri/        Rust backend
    build.rs        compiles the C sstv_lib core (cc crate) + Tauri context
    src/
      dsp/          safe wrappers over the codec
        ffi.rs      raw extern "C" declarations (cpp/sstv_lib/sstv.h)
        encode.rs   whole-transmission encoder
        decoder.rs  handle-based push-model decoder
      audio/        cpal capture, mono downmix, resample to 12 kHz, queue
      rx/           the receiver
        engine.rs   the state machine (pure: samples in, events out)
        session.rs  the decoder interface the engine drives
        service.rs  the thread wiring capture to the engine
      modes.rs      the nine SSTV modes, mirrored from sstv_modes.c / SstvMode.kt
      main.rs       Tauri commands + event forwarding
    tests/
      roundtrip.rs  encode -> decode -> compare, every mode
```

## How receive works

Audio is captured continuously with cpal at whatever rate the device offers,
downmixed to mono, and resampled to **12 kHz** — the rate the Android pipeline
uses (`SstvSignalListener.SAMPLE_RATE_HZ`), so both decoders run on identical
numbers. Blocks of 200 ms go into a bounded queue with a **drop-oldest** policy:
if the decoder falls behind, the freshest audio is what matters for finding the
next leader, and drops are counted and reported rather than swallowed.

One thread owns both capture and decode, because `cpal::Stream` is `!Send` on
Windows and has to be created and dropped on the thread that owns it. It drains
the queue, feeds `RxEngine`, and forwards the resulting events to the webview on
the `rx-event` channel.

`RxEngine` itself is pure — samples in, events out, no threads or IPC — so every
transition is unit-tested against a fake session. Two behaviours matter to the
UI:

- **Rows stream incrementally.** Each poll emits only the rows decoded since the
  last one, so the image paints line by line as it arrives.
- **Terminal states are held.** After DONE/ABORTED the decoder is reset back to
  hunting, but the resulting IDLE must not overwrite the Complete/Aborted the
  operator is looking at — the next transmission clears it.

## Prerequisites

- **Rust** (stable; MSVC toolchain on Windows): `winget install Rustlang.Rustup`
- **Node.js 18+** and npm
- **Linux only:** Tauri's system dependencies plus the libs the audio and serial
  crates link against:
  ```
  sudo apt-get install -y libwebkit2gtk-4.1-dev libgtk-3-dev librsvg2-dev \
    libayatana-appindicator3-dev libudev-dev libasound2-dev patchelf
  ```

No LLVM/clang-cl is needed on Windows: `sstv_lib` is pure C11 with no VLAs and
no POSIX-only functions, so MSVC's `cl.exe` compiles it as-is. (The FT8AF-era
`ft8_lib` did need clang-cl — that requirement does not carry over.)

## Develop / run

```
cd desktop/gui
npm install
npm run tauri dev      # starts Vite + builds the Rust app + opens the window
```

## Build / test

```
cd desktop/gui
npm test                          # frontend unit tests (vitest)
npm run build                     # frontend only -> desktop/gui/dist
npm run tauri build -- --no-bundle # compile check, what CI runs on PRs

cd src-tauri
cargo test                        # codec wrappers, mode table, round trip
cargo build --release
```

> `cargo test` compiles `build.rs`, which runs `tauri_build::build()`; that
> reads `tauri.conf.json` and requires `frontendDist` (`../dist`) to exist.
> Cargo does *not* run the configured `beforeBuildCommand`, so run
> `npm run build` at least once before `cargo test` in a fresh checkout.

### Codec notes worth knowing

- **The decoder needs audio past the final scan line.** It reports `Image` (two
  rows short) until samples arrive after the last line, then flips to `Done`.
  The live RX path always has audio flowing so this is invisible there, but any
  offline "decode this file" path must pad the tail. Pinned by
  `done_needs_audio_past_the_final_line`.
- **Never clip the leading audio on TX.** The 300 ms leader / 1200 Hz break /
  VIS code at the head of the buffer is what the receiving station syncs on. If
  playback ever has to drop samples, drop them from the tail. See CLAUDE.md,
  "TX audio pipeline", for the FT8AF-era bug this rule comes from.
- `wefax.c` sits in `sstv_lib` but is a separate codec with its own API and no
  cross-references from the SSTV sources; `build.rs` omits it until the GUI
  grows a WeFax receive path.

## CI & releases

`.github/workflows/desktop-gui.yml` — path-filtered on `desktop/gui/**` and
`sstvaf/app/src/main/cpp/sstv_lib/**`. PRs and `dev` pushes run a 3-OS compile
check; `staging` cuts a `desktop-dev.N` prerelease and `main` / `desktop-v*`
tags publish native installers (msi/nsis, dmg, deb/appimage). The CLI's MSI lane
owns the `v*` tags, so the two release namespaces never collide.
`desktop-gui-gate` is the required status check.

## Status

Landed:

- Tauri + Vite scaffold, 3-OS CI, release lane.
- The codec bound and unit-tested: mode table, encoder, push-model decoder,
  and an encode→decode round trip over all nine modes.
- Live receive: audio device picker, continuous capture, incremental image
  paint, and mode/progress/quality/slant status.

Next, in order: gallery + SQLite, TX composer, Hamlib rig control, waterfall,
log/ADIF, settings.
