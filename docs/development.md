# Build and test SSTVAF

The Android Gradle project is **`sstvaf/`**, not the repository root. The
`com.k1af.ft8af` namespace is an intentional heritage name;
the installed app ID is `radio.ks3ckc.sstvaf`. See [AGENTS.md](../AGENTS.md)
for contribution rules, the code map, and audio invariants.

## Fresh Amp orb / Debian or Ubuntu x86_64

From the repository root:

```bash
./.agents/setup
```

Amp normally runs this automatically before the agent starts. The script is
non-interactive and safe to rerun; it needs network access and root or
passwordless sudo to install missing apt packages. It accepts Google's SDK
licenses. It installs only missing host packages, uses a checksummed pinned
Android command-line-tools archive, asks sdkmanager to reconcile SDK packages,
and warms the Gradle distribution (not the entire Maven dependency cache).
Setup also installs Linux Tauri/GTK/WebKit/audio development libraries, GNU
make for Hamlib, Rust 1.90.0 (minimal profile), and the locked frontend npm
dependencies. It does not compile the app, Hamlib, or Rust dependencies.
First setup downloads over a GB; a warm run reuses the installations.
No account, signing key, API key, backend, or attached radio is needed.

The pinned toolchain matches the build:

| Component | Version / source of truth |
| --- | --- |
| JDK | 17 (`JAVA_HOME`; Debian/Ubuntu OpenJDK) |
| Gradle | 8.9 (`sstvaf/gradle/wrapper/gradle-wrapper.properties`) |
| Android Gradle plugin / Kotlin | 8.7.3 / 2.0.21 (`sstvaf/build.gradle`) |
| Android SDK platform | 36 (`compileSdk` in `sstvaf/app/build.gradle`) |
| Android build tools / NDK | 34.0.0 / 27.0.12077973 (AGP 8.7.3 defaults) |
| Android CMake | 3.22.1 (`externalNativeBuild` in `sstvaf/app/build.gradle`) |
| Android command-line tools | 12.0 (archive 11076708, `.agents/setup`) |
| Rust | 1.90.0 (`rust-toolchain.toml`) |
| Node / npm | Orb-provided Node 18+; npm uses `desktop/gui/package-lock.json` |

Note: Android CI historically pre-installs NDK 25.1, but without `ndkVersion`
AGP actually selects 27.0.12077973. Setup installs that selected version to
avoid an unexpected NDK download at Gradle configuration time. Robolectric
tests emulate API 35 via `src/test/resources/robolectric.properties`, not 36.

The SDK defaults to `~/Android/Sdk` (an existing `ANDROID_HOME` is honored).
Setup writes `~/.config/sstvaf/env.sh` and adds one source line to
`~/.bash_profile`. **Use a new Bash login shell after manual setup**; a child
script cannot change its parent's environment. For example, run the checks
below with `bash -lc '<command>'`. Agents and orb services started later get
the persisted environment automatically. No resume hook or web service is
required. When upgrading AGP/SDK/NDK/CMake, update setup and this table together.

## Automated checks (no device)

All commands here run from the **repository root**. The worker limit keeps
Gradle/Kotlin/native builds practical on small orbs; no system Gradle is needed.
Run Gradle and Cargo serially, not in parallel: concurrent cold builds can
exhaust an 8 GB orb even with each limited to two workers.

```bash
# Fast C11 host tests, also used by Native SSTV tests CI.
bash sstvaf/app/src/main/cpp/sstvaf_glue/run_sstv_host_tests.sh
bash desktop/run_cli_host_tests.sh

# Full JUnit4 / Truth / Robolectric suite (requires SDK, not a device).
sstvaf/gradlew -p sstvaf --no-daemon --max-workers=2 testDebugUnitTest

# Debug APK, including all four native ABIs. No release signing required.
sstvaf/gradlew -p sstvaf --no-daemon --max-workers=2 assembleDebug

# One test class while iterating (full suite still required before delivery).
sstvaf/gradlew -p sstvaf --no-daemon --max-workers=2 testDebugUnitTest \
  --tests radio.ks3ckc.sstvaf.sstv.SstvModeTest

# Coverage; runs JVM tests first.
sstvaf/gradlew -p sstvaf --no-daemon --max-workers=2 jacocoTestReport

# Check bootstrap scripts without installing anything.
bash -n .agents/setup
shellcheck .agents/setup
```

The nine native suites check published mode timing/sample counts, golden
waveforms, VIS detection, encode/decode round trips, slant correction, and
Robot 36 chroma parity, robustness, real off-air recordings, and WEFAX. They
need clang + libc headers + libm, not Java or the
Android SDK; `CC=gcc bash .../run_sstv_host_tests.sh` is an alternative.
Do not regenerate golden values just to make a failing test green.

The JVM suite covers CAT/audio policy and SSTV modes, pipeline, gallery, TX,
and Compose decision logic. Use tests under `src/test/java` or `src/test/kotlin`
with JUnit4 + Truth; Android types need `RobolectricTestRunner`. JVM tests use
fakes for native/device boundaries and do not prove the JNI or radio path.

Outputs:

- APK: `sstvaf/app/build/outputs/apk/debug/app-debug.apk`
- JVM HTML/XML: `sstvaf/app/build/reports/tests/testDebugUnitTest/` and
  `sstvaf/app/build/test-results/testDebugUnitTest/`
- Coverage: `sstvaf/app/build/reports/jacoco/jacocoTestReport/`

The first APK build cross-compiles vendored Hamlib once per ABI using GNU
make and the NDK. CMake invokes this automatically; archives under
`sstvaf/app/src/main/cpp/hamlib/prebuilt/` are ignored, not missing git files.

Optional static analysis uses `lintDebug`, `detekt`, and `ktlintCheck` with
the same Gradle prefix. These currently run warn-only in Android CI; existing
findings are not a reason to reformat unrelated code. Native and JVM tests
are required correctness checks. `.github/workflows/orb-setup.yml` exercises
cold/warm setup, login-shell environment persistence, host tests, JVM tests,
and debug assembly plus desktop tests when the bootstrap or these instructions
change. This path-filtered readiness check is not an always-required gate.

## Desktop checks

From the repository root, after setup:

```bash
npm --prefix desktop/gui test
npm --prefix desktop/gui run build
cargo test --locked -j 2 --manifest-path desktop/gui/src-tauri/Cargo.toml
```

The frontend build must precede Cargo: Tauri's build script requires
`desktop/gui/dist`. Rust tests cover DSP round trips and receive state logic
without an audio device. Frontend Vitest tests cover the pure display helpers.
`npm --prefix desktop/gui run tauri build -- --no-bundle` is the full native
desktop compile check; installers/cross-platform packaging remain separate
(see [desktop GUI guide](../desktop/gui/README.md)). No microphone is required
for these tests. Browser-only preview cannot validate native Tauri IPC/audio.

## Local macOS / Windows and connected testing

Install JDK 17 and Android Studio's SDK packages from the table above. Point
`JAVA_HOME` at JDK 17 and `ANDROID_HOME` at the SDK, or set `sdk.dir` in
untracked `sstvaf/local.properties`. On macOS use the same Gradle commands;
on Windows use `sstvaf\gradlew.bat -p sstvaf ...` (from WSL use
`cmd.exe /c "gradlew.bat ..."` inside `sstvaf/`). Hamlib requires a one-time
WSL/Linux-NDK build for Windows; see [AGENTS.md](../AGENTS.md)
for serial-targeted installation and debug-log paths.

Only on a host with an attached authorized Android device or a supported
local/CI emulator:

```bash
adb devices
sstvaf/gradlew -p sstvaf installDebug
sstvaf/gradlew -p sstvaf connectedDebugAndroidTest
```

Orbs have no KVM and cannot run Android emulators. Do not treat browser device
emulation as native app testing. Use a connected Android runner/device for
rendered UI checks, microphone/USB/CAT behavior, and RF validation. Without
one, report that limitation rather than claiming end-to-end device coverage.

## Troubleshooting

- **Java missing / wrong version:** rerun setup, then check `bash -lc
  'java -version'` and `bash -lc 'printf "%s\n" "$JAVA_HOME"'`. AGP needs 17.
- **SDK location not found:** check `ANDROID_HOME` in a new login shell;
  do not commit machine-specific `local.properties`.
- **Downloads fail (Google SDK / Maven / Gradle):** inspect the URL and error;
  fix network access and rerun setup or the same Gradle command. A cold first
  build resolves Maven and Robolectric artifacts and takes longer. Do not
  change dependency versions or skip tests to hide network failures.
- **NDK ZIP truncated:** rerun sdkmanager for `ndk;27.0.12077973`. Do not remove a
  working shared SDK or wipe unrelated Gradle caches.
- **Gradle killed / memory pressure:** keep `--max-workers=2`, stop idle
  Gradle daemons with `sstvaf/gradlew -p sstvaf --stop`, and run checks serially.
- **No device:** host tests, JVM tests, and `assembleDebug` still work. Never
  use `installDebug` as the default orb build command.
