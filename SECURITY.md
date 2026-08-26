# Security Policy

SSTVAF is an Android amateur-radio application that decodes slow-scan television
(SSTV) images off the air, transmits images over the air, drives radios over
CAT, and uploads contact logs to third-party services (Cloudlog, Wavelog,
Nextlog). We take security and privacy seriously and appreciate reports that
help keep operators and their stations safe.

## Supported Versions

Security fixes are applied to the latest release only. Please make sure you can
reproduce an issue on the most recent release (or a current build of the `dev`
branch) before reporting.

| Version            | Supported          |
| ------------------ | ------------------ |
| Latest release     | :white_check_mark: |
| Older releases     | :x:                |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues,
pull requests, or the Discord server.**

Instead, use GitHub's private vulnerability reporting:

1. Go to the [Security tab](https://github.com/patrickrb/sstvaf/security) of this
   repository.
2. Click **Report a vulnerability** to open a private advisory.

This routes your report privately to the maintainers. If you are unable to use
GitHub's reporting flow, email [k1af@ft8af.app](mailto:k1af@ft8af.app) to
arrange a private channel.

### What to include

To help us triage quickly, please include as much of the following as you can:

- The affected component (Android app UI, native SSTV codec / JNI glue, CAT /
  audio device handling, build/CI workflows).
- Version or commit hash, plus device/OS and radio model if relevant.
- A description of the vulnerability and its potential impact.
- Step-by-step reproduction instructions, proof-of-concept, or logs.

### Our commitment

- We will acknowledge your report within **5 business days**.
- We will provide an assessment and expected timeline within **10 business days**.
- We will keep you informed as we work on a fix and will credit you in the
  release notes and advisory unless you prefer to remain anonymous.

## Scope

Areas of particular interest:

- Handling of untrusted, attacker-controlled audio in the native SSTV codec
  (`ft8af/app/src/main/cpp/sstv_lib/`, `sstvaf_glue/`) — memory-safety issues in
  demodulating and decoding received signals into images (VIS parsing, line
  decode, color conversion).
- Image handling on the receive path: decoding, saving to the in-app gallery,
  and optional export to the device Photos gallery (path handling, storage
  permissions).
- CAT / audio device handling — the USB CAT and direct-libusb path, plus
  Bluetooth and network rig control.
- Storage and transmission of credentials for logging services (Cloudlog,
  Wavelog, Nextlog).
- Any code that reads, writes, or uploads user data.

The SSTV codec in `sstv_lib/` is a clean-room implementation (see
`ft8af/app/src/main/cpp/sstv_lib/SOURCES.md`); the FFT comes from the vendored
[kissfft](https://github.com/mborgerding/kissfft). If a vulnerability originates
in a vendored dependency, please also consider reporting it upstream; we will
coordinate on picking up the fix.

## Out of Scope

- Vulnerabilities in third-party services (Cloudlog, Wavelog, Nextlog)
  themselves — report those to the respective service.
- Issues requiring a rooted device, physical access plus an unlocked bootloader,
  or a compromised host already under attacker control.
- Reports from automated scanners without a demonstrated, exploitable impact.

Thank you for helping keep SSTVAF and the amateur-radio community safe. 73.
