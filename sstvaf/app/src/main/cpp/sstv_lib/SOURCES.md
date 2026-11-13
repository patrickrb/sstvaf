# sstv_lib — specification sources

This directory is a **clean-room** SSTV codec implementation, written for the
MIT-licensed SSTVAF project. It was implemented directly from published mode
*specifications* (timing tables, tone frequencies, VIS code assignments) — not
from any existing SSTV codec source code. In particular, no GPL SSTV source
(QSSTV, slowrx, MMSSTV, or derivatives) was consulted or reproduced.

## Specification sources

1. **JL Barber, N7CXI, "Proposal for SSTV Mode Specifications", presented at
   the Dayton SSTV forum, May 20 2000.**
   The primary source. Provides, for each mode implemented here (Robot 36/72,
   Martin M1/M2, Scottie S1/S2/DX, PD-50/90/120/160/180/240/290):
   - the per-line segment structure and all segment durations in µs,
   - sync (1200 Hz), black (1500 Hz), white (2300 Hz) reference frequencies,
   - the calibration header layout (300 ms 1900 Hz leader, 10 ms 1200 Hz
     break, 300 ms 1900 Hz leader, VIS code),
   - the VIS format (30 ms cells, 1200 Hz start/stop bits, 1100 Hz = bit 1,
     1300 Hz = bit 0, 7 data bits LSB first, even parity), and
   - the 7-bit VIS code assignments (Robot36=8, Robot72=12, Martin2=40,
     Martin1=44, Scottie2=56, Scottie1=60, PD50=93, PD120=95, PD90=99).

   Additional modes added from this same paper (issue #16):
   - **Scottie DX** (VIS 76, 320×256, GBR): identical line structure to
     Scottie 1/2 (starting 9 ms sync; regular sync between the Blue and Red
     scans) with the paper's stated 1.0800 ms/pixel → 345.600 ms per color
     scan (line = 3×345600 + 3×1500 sep/porch + 9000 sync = 1 050 300 µs).
   - **PD 160/180/240/290** (VIS 98/96/97/94): the PD family's 20.000 ms sync +
     2.080 ms porch + four Y/R-Y/B-Y/Y scans of the paper's tabulated color
     scan times — PD160 195.584 ms @512×400, PD180 183.040 ms @640×496,
     PD240 244.480 ms @640×496, PD290 228.800 ms @800×616. (Each equals
     width × pixel-time: 512×0.382, 640×0.286, 640×0.382, 800×0.286 ms.)

2. **Martin Bruchanov, OK2MNM, "Image Communication on Short Waves"
   (sstv-handbook.com), ch. 5 "List of SSTV modes".**
   Basis for **Martin M3** (VIS 36, 320×128) and **Martin M4** (VIS 32,
   320×128). The handbook lists M3 with the same line rate (lpm 134.395) as
   M1 and M4 with the same line rate (lpm 264.553) as M2 — i.e. M3/M4 use the
   identical horizontal timing of M1/M2 (source 1) transmitted for 128 lines
   instead of 256. It also independently confirms the VIS codes, dimensions
   and durations of Scottie DX and the PD-160/180/240/290 modes above.
   (Robot 24/12 Color are listed there too, but with no per-line segment
   breakdown, and the N7CXI paper documents only Robot 36/72 Color — so
   Robot 24 was deliberately NOT implemented rather than guessed.)

3. **ITU-R Recommendation BT.601** (studio-swing YCbCr conversion constants,
   as tabulated in standard references, e.g. C. Poynton, "Digital Video and
   HD"): the forward luma/chroma matrix used by the Robot and PD family:

   ```
   Y  =  16 + ( 65.738 R + 129.057 G +  25.064 B) / 256
   Cr = 128 + (112.439 R -  94.154 G -  18.285 B) / 256   (the "R-Y" scan)
   Cb = 128 + (-37.945 R -  74.494 G + 112.439 B) / 256   (the "B-Y" scan)
   ```

   The decoder uses the exact numeric inverse of this matrix (computed to
   double precision in `sstv_color.c`), with the result clamped to [0, 255].

4. **Pixel value ↔ frequency mapping** (from source 1): a pixel value
   v ∈ [0, 255] maps to f = 1500 + v · (800 / 255) Hz.

Everything else here (oscillator, FM demodulator, sync tracker, slant
regression) is original signal-processing code written for this project.

## WeFax / HF radiofax (`wefax.h` / `wefax.c`)

WeFax is a **distinct modulation** from the VIS+scanline SSTV family above —
no VIS code, no per-mode segment table; a transmission is a continuous stream
of scan lines whose left margin is set by a *phasing* signal. It was
implemented clean-room from the published marine/aviation radiofax
specifications, not from any existing decoder source.

1. **ITU-R Recommendation M.1171 / M.633 and the WMO "Manual on the Global
   Telecommunication System" (radiofacsimile / HF WEFAX)**, as summarised in
   standard amateur-radio references (e.g. the operating notes distributed
   with NOAA/DWD/marine HF fax schedules). These give:

   - **Modulation:** frequency-shift keying, F3C, with an **800 Hz shift**
     around a **1900 Hz** center — **black = 1500 Hz, white = 2300 Hz**. This
     is deliberately the same band the SSTV pixel scan uses, so the SSTV FM
     discriminator (`sstv_demod.c`) is reused verbatim and a WeFax pixel maps
     to the identical `f = 1500 + v·(800/255)` Hz used by the SSTV scan
     (source 4 above).

   - **Line rate:** given in lines per minute (LPM); the common HF value is
     **120 lpm** (⇒ 0.5 s per line, 2 lines/s). Samples per line at a given
     audio rate is therefore `sample_rate · 60 / lpm`.

   - **Index Of Cooperation (IOC):** the horizontal resolution parameter. The
     number of picture elements per line is `IOC · π`, so the standard
     **IOC 576** ⇒ `round(576·π) = 1810` px/line and IOC 288 ⇒ 905 px/line.
     (IOC is historically the drum-diameter × line-density product; `× π`
     converts it to pixels across one scan.)

   - **APT start/stop tones:** the automatic-picture-transmission start signal
     is a black/white keying at **300 Hz for IOC 576** (675 Hz for IOC 288),
     letting a receiver auto-select the IOC; the stop signal keys at
     **450 Hz**. Both run ~5 s in practice.

   - **Phasing signal:** a run of lines that are black except for a short
     white pulse at the left margin (~5% of the line here); the pulse's
     leading edge marks column 0, which the decoder uses to lock the line
     phase before picture content begins.

   The phasing lock, line slicer, box-average line renderer, and line
   classifier in `wefax.c` are original signal-processing code written for
   this project.
