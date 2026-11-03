# SSTVAF shared-core integration

SSTVAF vendors **ham-radio-core 0.1.0**, whose canonical source is
`patrickrb/FT8AF/ham-radio-core` (extracted from FT8AF baseline `c2f63e8`).
The entire module, MIT license, contract README, and 44 public-contract tests
are unchanged; there is no published artifact or app-local forwarding layer.

- Source archive SHA256:
  `02e632964a4e619438bdc300abac7b45bb924cd2a7e1e3bc00695878c2c6dcba`
- Vendored `ham-radio-core/SHA256SUMS` SHA256:
  `3628580eedbd202565ea12666da426d3f1e3e429d640db1b6ef05b9b3782c84d`

## Boundary and comparison

Compared against SSTVAF `origin/dev` baseline `5ccaa86`: all four Java
implementations and their tests match after package/documentation changes.
No algorithm, existing public API, or unique SSTVAF test was lost.

`sstvaf/settings.gradle` includes the repository-root module via `projectDir`;
the app uses `implementation project(':ham-radio-core')`.
`UsbAudioDevice` imports core FIR/rational resampling and UAC1 parsing;
`TxUpsampler` imports the rational resampler; `MainViewModel` and
`CatLivenessTracker` import the core CAT predicates. The four duplicated app
classes and test suites are removed; app-specific USB, upsampling, CAT tracker,
and rig polling tests remain. JNI/native DSP, Android USB I/O, PTT, codecs,
mode behavior, logging, and UI remain app-owned and unchanged.

`android.yml` detects `ham-radio-core/**`, explicitly invokes
`:ham-radio-core:test` alongside app tests, and includes core test report paths
in the existing upload. Its release collection filter and diff statistics
include the core too, so future core-only updates are not silently excluded.
No release was triggered as part of this integration.

## Validation (2026-10-30, Linux orb, JDK 17)

Commands are from the repository root unless noted:

| Check | Result |
| --- | --- |
| `sha256sum -c SHA256SUMS` inside `ham-radio-core/` | All 13 manifest entries pass |
| `bash sstvaf/gradlew -p ham-radio-core clean test jar` | PASS; 44 tests, no failures/errors/skips; JAR built |
| `bash gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m -Pkotlin.compiler.execution.strategy=in-process :ham-radio-core:test testDebugUnitTest --stacktrace` inside `sstvaf/` | PASS; 2,637 app tests and 44 core tests, no failures/errors/skips |
| `bash gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m -Pkotlin.compiler.execution.strategy=in-process assembleDebug --stacktrace` inside `sstvaf/` | PASS; debug APK built for arm64-v8a, armeabi-v7a, x86, x86_64 |
| Inspect APK DEX class descriptors and native library entries | PASS; all four core classes present, removed legacy class descriptors absent, native libraries packaged for all four ABIs |
| `CC=gcc bash sstvaf/app/src/main/cpp/sstvaf_glue/run_sstv_host_tests.sh` | PASS; all nine suites, including real recordings and WEFAX |
| Parse `android.yml` and exercise its detect/release gates | PASS; core-only/app-only included, docs-only/desktop-only excluded; explicit core and app unit tasks present |
| `git diff --check` | PASS |

The initial combined unit/build run was killed with exit 137. Unit tests and
APK assembly passed when run separately with the resource flags above; no
tracked build settings were changed.
No Android device is attached; installation, instrumented tests, and USB/RF
interoperability are not verified by these host checks.

Future core updates must use the complete, tested canonical payload and update
the version/checksums above; do not patch the vendored module independently.

## Combined readiness integration

Readiness PR #126 is merged into `dev` at `dffa86f`; this port was applied on
that current baseline, not on the worker's earlier checkout. The transferred
archive was verified as SHA256
`d2017adbac2cdb4a619857b21a96f4f4a2152bd7a49af5b4ca5a52164e8afcb9`.
Cold and warm `.agents/setup` runs and persisted login-shell tool discovery
passed. Combined validation again passed 44 core tests (4 suites), 2,637 app
tests (253 suites), all nine native suites, and desktop CLI tests.

Both Android CI and the readiness workflow detect `ham-radio-core/**` and
explicitly run the core tests. SSTVAF has no separate `pr-debug-apk` workflow.
Readiness Gradle checks run separately with a 1536 MiB heap, a single worker,
and in-process Kotlin. The existing JaCoCo upload covers the app, not the
new Java module; core test execution is verified but core coverage is not
reported. The canonical module is unchanged.

Delivery is **internal testing only**. Feature PRs and `dev` are build-only;
`staging` creates signed prereleases and Play internal drafts using repository
signing and Play service-account secrets. `main` and `android-v*` tags publish
to production and must not be used until the user has tested all five apps
and subsequently explicitly approved production. No production automation or
safeguards are changed here.

Without authorized signing/Play credentials, distribute a debug-signed APK
instead. Its package is `radio.ks3ckc.sstvaf`, the same as the Play app: a
debug key cannot update a release-signed install. Use a spare device/profile
or back up app data before uninstalling an existing install; uninstalling
deletes local app data. Never describe host tests as device/USB/CAT/PTT/RF
validation.
