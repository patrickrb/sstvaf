# Using SSTVAF with handheld radios (HTs)

SSTVAF works with any HT through a plain audio interface — no CAT control,
no rig driver. This covers the fleet you'll actually meet at a DEF CON-style
event: Anytone, Baofeng, Quansheng UV-K5, TYT, Wouxun, BTECH, and Kenwood
handhelds all share the **Kenwood K1 two-pin accessory jack**, so one cable
covers the lot. Yaesu/Icom HTs use different jacks but the same audio-only
model applies with the matching cable.

## How it works

The app's **VOX control mode** (the default) never commands PTT — the audio
itself keys the radio. Two cable families make that happen:

- **BTECH APRS-K1 PRO** (USB-C): a USB Audio Class sound card with a
  **hardware auto-PTT** circuit — it keys the radio when it detects outgoing
  audio and has a sensitivity dial. The app's USB audio device scan finds any
  UAC device regardless of vendor, so it shows up in the audio pickers with
  no special support.
- **BTECH APRS-K1** (original, TRRS): a passive cable into the phone's
  headset jack. The *radio's own VOX* does the keying.

Either way there is an attack time between "audio starts" and "transmitter
is actually up" — and SSTV front-loads everything a receiver needs
(300 ms calibration leader → VIS code). If keying eats the header, the far
end decodes nothing. The **VOX pre-tone** setting (Settings → Advanced,
default 300 ms) prepends sacrificial 1900 Hz leader ahead of the real
transmission so the keying chain swallows tone the decoder never needed.
It is strictly additive — the encoded transmission is never trimmed
(see CLAUDE.md "Never clip the leading audio").

## Setup: APRS-K1 PRO (USB-C, e.g. Anytone / any K1 HT)

1. Plug the cable's K1 plug into the HT, USB-C into the phone; grant the
   USB permission prompt.
2. Settings → Radio & Audio:
   - **Control mode: VOX** (leave rig model unset — no CAT exists).
   - **Audio input** and **Audio output**: pick the USB audio device. Prefer
     the plain `AudioManager` USB entry; the "(USB direct)" entry is the
     libusb path for kernels whose UAC driver is broken (car dashes).
3. Settings → Advanced: leave **VOX pre-tone** at 300 ms. If the start of
   your images is still clipped on air (slanted/garbled top rows at the far
   end), raise it; if the cable false-triggers, adjust the cable's
   sensitivity dial rather than the app.
4. On the radio: **disable the radio's own VOX** (the cable drives PTT
   electrically), disable battery save / APO if TX drops, and set volume to
   roughly mid-scale for RX audio into the cable.
5. TX level: start with the app's TX volume ~50 % and back off if the far
   end reports over-deviation (FM HTs clip hard).

## Setup: passive TRRS cable (original APRS-K1)

Same as above, except:

- Audio input/output: pick the **wired headset** entries (the phone routes
  through its TRRS jack).
- On the radio: **enable VOX**, mid sensitivity. The pre-tone covers the
  radio's VOX attack the same way it covers the PRO's auto-PTT.

## Frequencies

FM SSTV on VHF/UHF is wideband-FM audio — just tune both radios to the same
simplex frequency (check the event's ham-village schedule for the SSTV
calling channel; 2 m simplex around 146.4–146.58 MHz is typical in the US).
Robot 36 is the usual mode for FM HT work: short airtime, tolerant of FM
noise.

## Troubleshooting

- **Radio keys but nothing decodes at the far end** — the classic clipped
  header. Raise the VOX pre-tone; verify with a second receiver that the
  1900 Hz leader is audible *before* the "beep-boop" VIS.
- **Radio never keys (PRO cable)** — sensitivity dial too low, or the app's
  TX volume so low the detector never trips.
- **RX shows nothing** — confirm the *input* device picker actually shows
  the USB/wired device (Android silently reverts a preferred device that
  disappears), and open the radio's squelch or check its volume.
- The structured log at
  `/sdcard/Android/data/radio.ks3ckc.sstvaf/files/debug.log` records every
  TX (`SSTV TX: start … voxPreToneMs=…`) — a reported play length shorter
  than the sample count means audio is being cut off downstream.
