# SSTVAF Desktop CLI (32-bit Windows build)

A small, dependency-free command-line front end over the same clean-room SSTV
codec that powers the SSTVAF Android app (`ft8af/app/src/main/cpp/sstv_lib`).
It exists so the codec can be used on a plain desktop PC — including **32-bit
Windows** systems, cross-compiled from Linux and shipped as an **MSI** — with no
Android device involved.

> Issue #40: *"There are still some of us that have 32 bit windows systems.
> Cross compile the source to create a 32 bit Desktop windows MSI file."*

## What it does

```
sstvaf encode <mode> <in.ppm> <out.wav> [--rate HZ] [--amp A]
sstvaf decode <in.wav> <out.ppm>
sstvaf list          # list the nine SSTV modes with dimensions + VIS codes
sstvaf help
```

- **Images** are binary PPM (`P6`, maxval 255) — trivially produced/consumed by
  ImageMagick (`convert photo.jpg -resize 320x256! image.ppm`), GIMP, Python
  (Pillow), etc.
- **Audio** is 16-bit mono PCM WAV at the chosen sample rate (default 12 kHz) —
  play it into your rig, or decode a recording of a received transmission.

Modes: Robot 36 / 72, Martin 1 / 2, Scottie 1 / 2, PD 50 / 90 / 120. `encode`
requires the input image to match the mode's native dimensions (e.g. 320×256 for
Scottie 1); `sstvaf list` prints them.

The tool pulls in **no third-party libraries** — PPM and WAV are read/written by
hand — which keeps the frozen 32-bit Windows target buildable with just a C
compiler.

## Layout

| File | Purpose |
|------|---------|
| `sstvaf_cli.h` / `sstvaf_cli.c` | Reusable, unit-tested logic (mode lookup, WAV/PPM serialize+parse, sub-commands). |
| `main.c` | Thin `main()` that forwards to `sstvaf_cli_run()`. |
| `test_sstvaf_cli.c` | Host test suite (JUnit-style C, matching `cpp/sstvaf_glue`). |
| `run_cli_host_tests.sh` | Build + run the tests on any POSIX host. |
| `build_win32_msi.sh` | Cross-compile `sstvaf.exe` for 32-bit Windows and package the MSI. |
| `packaging/sstvaf.wxs` | WiX source for the MSI (built with `wixl`). |

## Build the 32-bit Windows MSI (from Linux)

Install the cross toolchain and the MSI packager (Debian/Ubuntu):

```bash
sudo apt-get install -y gcc-mingw-w64-i686 wixl
```

Then:

```bash
desktop/build_win32_msi.sh 1.2.3      # version string is optional (default 0.1.0)
```

Outputs land in `desktop/build/win32/`:

- `sstvaf.exe` — a self-contained i386 PE (statically linked libgcc), runs on
  stock 32-bit Windows.
- `sstvaf-1.2.3-win32.msi` — installs the exe into `Program Files\SSTVAF` and
  adds it to the system `PATH`.

A ready-to-use GitHub Actions workflow that does exactly this on every push/PR
touching `desktop/` and on `v*` tags — uploading the MSI as a build artifact and
attaching it to the Release for tags — is provided at
[`desktop/ci/windows-msi.yml`](ci/windows-msi.yml). It lives here rather than
under `.github/workflows/` only because the token that opened the introducing PR
lacked the `workflow` scope; a maintainer enables it with:

```bash
git mv desktop/ci/windows-msi.yml .github/workflows/
```

## Run the tests

```bash
desktop/run_cli_host_tests.sh          # uses clang by default
CC=gcc desktop/run_cli_host_tests.sh   # or another compiler
```

Every new code path here is covered: mode-name resolution, the WAV/PPM
serializers (including malformed-input rejection), an in-memory
encode→WAV→decode round trip, and a full `argv`-dispatched encode/decode through
real files.

## Why the codec compiles unchanged

`cpp/sstv_lib` is pure C11 with no Android or JNI dependencies (see its
`SOURCES.md`), so the desktop build simply compiles the same `.c` files the NDK
does. No codec source was modified for this tool.
