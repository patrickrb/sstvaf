# Real SSTV recordings (decoder regression fixtures)

12 kHz mono 16-bit WAV clips of real SSTV audio, the exact format the RX
chain hands the native decoder (`HamRecorder` → `SstvSignalListener`). They
are run by:

- `test_sstv_recordings.c` on the host (part of `run_sstv_host_tests.*`,
  so CI runs them on every PR), and
- `SstvRealRecordingTest` on a device/emulator (the same WAVs are bundled as
  androidTest assets and streamed through the real Kotlin listener + JNI).

Synthetic encode→decode round trips cannot catch what real audio does; every
clip below guards something that was first seen to break on a recording.

| File | Mode | What it guards |
|------|------|----------------|
| `robot36_colorbars_yt.wav` | Robot 36 | Clean baseline (test card). |
| `robot36_quiet_yt.wav` | Robot 36 | Peaks at 7 % of full scale — level must not matter. |
| `robot36_iss_yt.wav` | Robot 36 | ISS SpaceCam picture, ~100 ppm clock slant. |
| `robot36_hot_yt.wav` | Robot 36 | Peaks at 96 % through a limiter (near clipping). |
| `robot36_glitch_14230_yt.wav` | Robot 36 | Sender dropped ~15 ms of audio 0.4 s into the image; every later sync is off-grid. Needs sync re-acquisition — without it the image shears and the chroma pairing inverts while the decoder still reports "complete". |
| `scottie2_midimage_20m_yt.wav` | Scottie 2 | Weak, fading 20 m signal recorded from mid-image: no VIS in the clip. Needs the header-less sync-train lock (and noise-gap bridging in the pulse detector). |

## Provenance

All clips were extracted from publicly posted YouTube videos of SSTV
receptions (audio only, trimmed, resampled to 12 kHz mono) on 2026-10-09 and
are included solely as engineering test data for this decoder:

- `robot36_colorbars_yt.wav` — "Slow-scan television (SSTV) - Robot 36 Color" (`3_s-ERJjh8k`), 1.5–40.5 s
- `robot36_quiet_yt.wav` — "Robot 36 Test Image (SSTV)" (`H9q4hh0JPtU`), 0–39 s
- `robot36_iss_yt.wav` — "SSTV Video From The International Space Station" (`tuyEwI9fJGk`), 5.5–44 s
- `robot36_hot_yt.wav` — "Portal 2 SSTV Image" (`Oano8dIeksk`), 0.5–40 s
- `robot36_glitch_14230_yt.wav` — "Transmitting analog SSTV image on 14.230 MHz" (`uuJ25HmXiL8`), 1–40.5 s
- `scottie2_midimage_20m_yt.wav` — "Amateur radio Slow Scan TV received on 20 meters." (`MH44mv_QkG4`), 12–40 s

If a rights holder objects to a clip being here, replace it with an own
recording of the same failure class and update the table.

## Adding a fixture

1. Convert to the RX format: `ffmpeg -i in.ext -ac 1 -ar 12000 -sample_fmt s16 out.wav`
   (trim with `-ss`/`-t`; keep clips under ~1 MB).
2. Run it through the debugging CLI first and look at what happens:

   ```
   ./run_sstv_host_tests.ps1 -Tool      # or: ./run_sstv_host_tests.sh --tool
   sstv_wav_decode out.wav --out out.ppm --trace out.csv
   ```

   It prints every decoder state transition with a timestamp, the final
   mode/rows/quality/slant, writes the decoded image (PPM; `ffmpeg -i out.ppm
   out.png` to view) and, with `--trace`, the demodulated frequency track
   per sample (CSV) so the exact moment the decoder loses the signal can be
   inspected. `--gain G` scales the input, `--chunk N` changes the push
   size, `--rate-override HZ` lies about the sample rate.
3. Add a row to `kFixtures` in `test_sstv_recordings.c` (mode, expected
   lock type, rows, quality/slant/coherence floors — set the floors a little
   under what the clean run prints) and, if the clip adds a new failure
   class, a case to `SstvRealRecordingTest`.
