# SSTVAF Project Instructions

> **This repo is SSTVAF** — an SSTV app built from the FT8AF codebase. The
> transformation is complete: the FT8 engine is gone, replaced by the
> clean-room SSTV codec in `cpp/sstv_lib/` (RX/Gallery/TX/Waterfall/Log/
> Settings tabs). The Android module lives under `sstvaf/` (renamed from
> `ft8af/` in #17). Two heritage names are kept deliberately because they
> are tied to JNI symbol resolution and must not change: the
> `com.k1af.ft8af` package namespace (referenced by `FindClass` strings)
> and the `libft8af.so` native library (`System.loadLibrary("ft8af")`).
> The instructions below (build, test, deploy, debug-log paths) apply as
> written.

> **Placeholders.** This file is written for any contributor's machine.
> Substitute your own values wherever you see `<…>`. The recurring ones:
> `<windows-checkout>` / `<mac-checkout>` — your local clone of this repo;
> `<jdk17-home>` — the home directory of a JDK 17 install;
> `<phone-serial>` — your phone's serial from `adb devices`;
> `<android-sdk>` — your Android SDK root.
> Machine-specific notes (your paths, device serials, rig quirks) belong in an
> untracked `CLAUDE.local.md`, not here.

## Development environments

Contributors work from two kinds of checkout; the commands differ, so figure
out which one you're on first (`uname` / the primary working directory) and
follow the matching path throughout:

- **Windows** (`<windows-checkout>`, possibly driven from WSL) — uses the
  `cmd.exe /c "gradlew.bat …"` wrapper, which picks up the Android Studio JBR.
- **macOS** (`<mac-checkout>`) — builds and runs unit tests natively
  (`./gradlew …`, needs JDK 17).

Either kind of machine can build, test, install, and drive a real device when
an Android phone is attached over USB debugging (an emulator is often attached
alongside it). The gradle invocation is the main difference (`gradlew.bat`
wrapper vs. `./gradlew` with JDK 17); where a section below gives a command for
one, the other's equivalent is called out inline.

On some devices `screencap` can come back all-black during the splash
animation or when a hardware overlay is active; wait for the app to settle and
retry, and use the accessibility tree only as a fallback (`uiautomator dump`
often can't reach idle because the UI animates continuously).

## Workflow

**Every work item requires a pull request — no direct commits to `dev` or
`main`.** Even a one-line docs or config change goes through a feature branch and
a PR.

**All PRs target the `dev` branch.** After finishing the code for any work item,
open a pull request against `dev` (do the work on a feature branch, then
`gh pr create --base dev`). Don't merge straight to `main`.

**Promotion path: feature → `dev` → `staging` → `main`** (details in
`docs/release-pipeline.md`). Merging `dev → staging` cuts an `android-dev.<run#>`
prerelease on the Play **internal** track; Claude picks the semver bump from the
PRs, commits and diff size since the last `android-v*` tag and writes the Play
release notes (`android.yml`, needs the `ANTHROPIC_API_KEY` secret — without it
the run falls back to a patch bump and PR titles). Merging `staging → main`
ships that same version + notes as `android-v<x.y.z>` on the **production**
track. A promotion that touches nothing under `sstvaf/` builds but cuts no
release. The source gates (`staging-gate.yml`, `main-gate.yml`) reject PRs into
`staging`/`main` from anywhere else.

**Use a git worktree for every separate line of work.** Don't switch branches in
your primary checkout — branch-switching there collides with anything else in
flight (a running build, an `adb install`, a different task). Instead spin up an
isolated worktree per task:

```
git worktree add ../<checkout-dir-name>-<short-task-name> -b feat/<task>
```

Notes for a fresh worktree:

- The `sstvaf/app/src/main/cpp/` native sources (`sstv_lib`, `sstvaf_glue`,
  the FFT/resampler glue in `ft8af_glue`, `libusb`, `kissfft`) are
  **tracked** in git, so a fresh worktree builds with no manual copying.
- Build/install from inside the worktree's `sstvaf` dir: Windows uses the wrapper
  (`cmd.exe /c "gradlew.bat installDebug"`); macOS uses `./gradlew` (see Build &
  Deploy for the JDK 17 requirement). Both can install to an attached device.

Remove the worktree when the branch is merged: `git worktree remove <path>`.

When nothing else is in flight in your checkout, a feature branch in place is
fine, but a worktree per task is still the recommended default.

## Testing

**Every new code path requires a new test.** Any branch, helper, or behavior
you add or change must be covered by a unit test in the same PR — this is not
optional, even for small UI helpers.

Compose `@Composable` and `DrawScope` code can't be unit-tested directly, so
extract the decision/geometry logic into a plain top-level `internal` function
or class (e.g. `buildQsoLog`, `QsoPathProjection`) and test that. Keep the
Composable a thin wrapper that just calls the extracted logic.

Tests live in `sstvaf/app/src/test/` (Kotlin under `.../kotlin`, Java under
`.../java`), use JUnit4 + Truth (`assertThat`), and add
`@RunWith(RobolectricTestRunner::class)` when the code under test touches
Android/Play-Services types (e.g. anything reaching `MaidenheadGrid`,
`GeneralVariables`, `LatLng`). Pure math/logic needs no runner.

Run from the `sstvaf` dir.

**Windows:**

```
cmd.exe /c "gradlew.bat testDebugUnitTest"
# or a single class:
cmd.exe /c "gradlew.bat testDebugUnitTest --tests <fully.qualified.ClassName>"
```

**macOS** (needs JDK 17 — see Build & Deploy):

```
export JAVA_HOME=<jdk17-home>
./gradlew testDebugUnitTest
# or a single class:
./gradlew testDebugUnitTest --tests <fully.qualified.ClassName>
```

### UI / responsive-layout testing

Unit tests don't catch layout that breaks by *shape*. Whenever you touch a
screen's layout, add a tab, or change the app shell, run the full
**tab × device × orientation** sweep in **`docs/ui-testing.md`**: for each device
class (compact phone, the Redmi Redpad 2 tablet, a ≥600dp landscape phone, a
resizable Chromebook/desktop window), in **both portrait and landscape**, open
**every** tab (RX/Gallery/TX/Waterfall/Logbook/Settings) and confirm its primary
control isn't pushed off-screen or hidden behind the TX strip. On the emulator,
force each cell with `adb shell wm size 2400x1080` (landscape) / `wm size reset`
rather than trusting portrait alone. The adaptive shell reflows navigation but
not each screen's own content, and a control looks fine in portrait while sitting
past the fold in landscape — that's how the TX pick-image button regressed on
tablets (issue #20). Real hardware (a physical Redmi Redpad 2 on Android 16 at
minimum) is still required before closing a responsive-layout issue.

## Build & Deploy

After making code changes, always build and install on the connected device
when one is attached.

**Windows:** A WSL shell typically has no Linux JDK, so `./gradlew` fails with
`JAVA_HOME is not set`. Use the Windows wrapper instead — it picks up the Android
Studio JBR automatically:

```
cd sstvaf && cmd.exe /c "gradlew.bat installDebug"
```

**macOS:** AGP 8.7.3 / Gradle 8.9 need **JDK 17**; if your system JDK is older,
builds fail without pointing `JAVA_HOME` at a 17. A user-local install (e.g. a
Temurin 17 archive unpacked under your home directory — no sudo needed, unlike
the Homebrew cask) works fine; wherever it lives is your `<jdk17-home>`. The
`installDebug` task installs to *every* attached device (including the
emulator); to target only the phone, build the APK and push it with an explicit
serial:

```
cd sstvaf && JAVA_HOME=<jdk17-home> ./gradlew assembleDebug
adb -s <phone-serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

To drive the UI: `adb -s <phone-serial> exec-out screencap -p > shot.png` and
tap with `adb -s <phone-serial> shell input tap <x> <y>` (coordinates in the
device's real pixel space — check it with `adb -s <phone-serial> shell wm size`).

When an emulator is attached alongside the phone, Gradle's install step prints
`TimeoutException`/`Unknown API Level` warnings for the emulator and still
installs on the phone; `Installed on 1 device.` means the phone got the APK, so
ignore the emulator noise.

When multiple devices are attached, target the phone explicitly with `-s` and
its serial (from `adb devices`). If `adb` isn't on your PATH, it lives at
`<android-sdk>/platform-tools/adb` (on Windows
`<android-sdk>\platform-tools\adb.exe`; Homebrew installs on macOS put it at
`/opt/homebrew/bin/adb`).

## Debug logs

The app writes a structured event log to
`/sdcard/Android/data/radio.ks3ckc.sstvaf/files/debug.log` via `fileLog()` in
`ComposeMainActivity.kt` — CAT serial sends/recvs, USB attach events,
autoConnect attempts, band/frequency changes, etc. This is usually the most
useful source. Pull it with:

```
adb -s <phone-serial> pull /sdcard/Android/data/radio.ks3ckc.sstvaf/files/debug.log /tmp/
```

For runtime detail not in `debug.log` (audio recording loop, system USB events,
crashes), use `adb logcat`. Useful tags: `NativeSstvCodec`, `MicRecorder`,
`UsbAudioDevice`, `CableConnector`, `CableSerialPort`, `UsbHostManager`,
`UsbAlsaManager`, `ComposeMainActivity`. (The Kotlin SSTV pipeline —
`SstvSignalListener`, `SstvTransmitter` — logs through `fileLog()` into
`debug.log` rather than logcat.) The app's `applicationId` is `radio.ks3ckc.sstvaf` (the same
for every contributor — it's set in `sstvaf/app/build.gradle`) — pid-filter with
`adb -s <phone-serial> logcat --pid=$(adb -s <phone-serial> shell pidof radio.ks3ckc.sstvaf)`
when you only want app-internal lines.

## TX audio pipeline

How a transmit call becomes RF. The waveform is generated in full by the
native codec — `NativeSstvCodec`/`SstvTransmitter` encode the whole SSTV
transmission (calibration header + VIS + scan lines) as one buffer, and
everything downstream just has to *not break it*. Two hard-won FT8AF-era
lessons apply verbatim to SSTV; re-introducing either makes TX silently
broken — the rig keys, audio is audible, ALC looks right, and nobody
decodes the image.

**1. Never clip the leading audio.** The sync information a receiver needs
lives at the *start* of the buffer — for SSTV that's the 300 ms leader /
1200 Hz break / VIS code; for FT8 it was the leading Costas array. The
historical bug (FT8AF PR #93): a late-start compensation computed as
`time_into_cycle_ms % cycle` instead of `max(0, time_into_cycle_ms - slack)`
chopped a few hundred ms off the start of every on-time transmission —
audible audio, zero decodes. SSTV has no 15 s cycle, so no start-skipping
logic should exist at all; if playback ever needs to drop samples, drop
them from the tail, never the head. Tell from log: reported play length
shorter than the generated sample count.

**2. `libusb_set_iso_packet_lengths` must use the audio rate, not
`wMaxPacketSize`.** A USB Audio Class device plays back exactly the bytes
per frame the host hands it. For USB FS, that's
`(sampleRate * channels * bytesPerSample) / 1000` — e.g. 192 bytes/frame
at 48 kHz stereo 16-bit. The endpoint's `wMaxPacketSize` (~200 for
C-Media CM108-style chips) is the device's *max*, not the data rate.
Sending that much per frame makes the device clock samples ~4 % faster
than negotiated, shifting every tone up by the same ratio — for SSTV
that skews the pixel-value frequency mapping and slants/garbles the
image at the far end. Tell from log: `UsbAudioNative.nativeWrite`
returns measurably faster than the audio duration. Fixed in FT8AF PR #94
in `cpp/usb_audio_capture.cpp` (still the live code path). The
Android-standard `AudioTrack` path is unaffected because the kernel UAC
driver does this math automatically; the bug only bites the direct-libusb
path used for car-dash kernels and similar.
