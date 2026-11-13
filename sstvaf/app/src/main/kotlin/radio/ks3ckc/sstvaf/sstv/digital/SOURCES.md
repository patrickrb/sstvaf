# digital-SSTV modem — design provenance

This package is a **clean-room** implementation of a digital-SSTV
(EasyPal / DigTRX style) modem, written for the MIT-licensed SSTVAF project.
It was implemented from published *specifications and standard textbook DSP /
coding theory*, not from any existing digital-SSTV source. In particular, no
EasyPal, DigTRX, WinDRM, or qsstv source was consulted or reproduced.

## What this is

EasyPal / DigTRX ("digital SSTV") send a compressed image as a file over a
COFDM (OFDM) waveform derived from the DRM / HamDRM broadcast modem: QAM data
subcarriers plus scattered pilots, a cyclic-prefix guard interval, forward
error correction, time interleaving, and a block-oriented file-transfer layer
with an ARQ (retransmit-the-missing-blocks) protocol.

This package implements that architecture as a self-contained subsystem:

| Layer | File | Role |
|---|---|---|
| Field arithmetic | `Gf256.kt` | GF(2^8), primitive poly `0x11d` |
| FEC | `ReedSolomon.kt` | systematic RS(n, n-2t), corrects t byte errors |
| Interleave | `BitInterleaver.kt` | block (matrix) time-interleaver |
| Integrity | `Crc.kt` | CRC-16/CCITT-FALSE (blocks), CRC-32 (payload) |
| Transform | `Fft.kt` | radix-2 FFT/IFFT |
| Modem | `OfdmModem.kt` | real-passband COFDM: QPSK + pilots + guard interval |
| Container/ARQ | `DigitalSstvContainer.kt` | header + block framing, missing-block reports |
| Facade | `DigitalSstvCodec.kt` | image ⇄ audio, `DigitalSstvReceiver` accumulator |
| Modes | `DigitalSstvMode.kt` | robustness modes (guard/interleave trade-offs) |

## Design sources

1. **ETSI ES 201 980 (Digital Radio Mondiale)** and the amateur *HamDRM*
   adaptation: the COFDM structure EasyPal/DigTRX are built on — QAM data
   cells, scattered/reference pilots for coherent channel estimation, a
   cyclic-prefix guard interval sized to the channel's delay spread, and
   robustness modes trading throughput for HF resilience. The subcarrier /
   pilot / guard geometry here follows that published design, adapted to a
   real audio passband (Hermitian-symmetric spectrum) rather than a complex
   IF, so the inverse FFT is directly real.

2. **Reed-Solomon coding** (Wicker & Bhargava, *Reed-Solomon Codes and Their
   Applications*; the classic Berlekamp-Massey / Chien / Forney decoding
   chain), over the standard `0x11d` GF(2^8) with generator element 2 — the
   same field convention as DRM/DVB, so the code is easy to cross-check.

3. **Block interleaving and CRC** are textbook: a write-rows/read-columns
   matrix interleaver and the standard CRC-16/CCITT-FALSE and CRC-32
   polynomials (pinned in `CrcTest` against the `"123456789"` check vectors).

## Interoperability note

Bit-level interop with on-air EasyPal transmissions is the eventual acceptance
bar and is **not** claimed here: it requires captured reference recordings and
matching EasyPal's exact JPEG2000 payload framing and OFDM parameters, which
are follow-up work. What is implemented and tested is a complete, internally
consistent digital-SSTV modem whose encode → (noisy channel) → decode round
trip recovers the image and drives the block-ARQ retransmit loop.
