# Optical link — flashlight communication (design note)

Status: **captured, not built.** Lands in Misc, next to the drone detector.

Two things, in order of difficulty:

1. **Morse** — flash a message on the torch, and decode a flashing light seen through the camera.
2. **Phone-to-phone data link** — two copies of the app agreeing on a protocol and moving actual bytes.

---

## 1. What the hardware actually allows

The honest constraint is not the LED, it's the two software chokepoints either side of it.

| Chokepoint | Reality | Consequence |
|---|---|---|
| `CameraManager.setTorchMode` | Round-trips through the camera HAL: ~10–50 ms, and jittery | Reliable keying below ~10 toggles/s. Anything faster and the *jitter*, not the rate, kills you |
| Camera capture at 30 fps | One luminance sample every 33 ms | Need ≥3 samples per symbol → symbol ≥ 100 ms |
| Auto-exposure | Fights you: a bright flash makes the sensor darken the next frames | Must lock AE/AWB and fix a low exposure, or the signal self-erases |

So the torch is fundamentally a **~10 symbol/s channel**. That's the number everything else follows from,
and it means the interesting part of this project is the protocol, not the throughput.

## 2. Morse

**Transmit** is easy. Standard timing, one unit = 100 ms (≈12 WPM):
dot 1 unit, dash 3, intra-character gap 1, inter-character gap 3, word gap 7. A unit slider (60–250 ms)
covers "as fast as my phone can key" through "as slow as my friend can read".

**Decode** is the fun one, and it's very doable:

1. Camera frames → mean luminance of a small centre ROI (the same brightness-over-time sampler the
   Motion amp tool already runs).
2. Lock exposure, drop it low. The emitter should be the brightest thing in the ROI by a wide margin.
3. Adaptive threshold: track a slow-moving baseline and a peak estimate, threshold at the midpoint —
   survives ambient light changes without recalibrating.
4. Turn the binary stream into a run-length list of on/off durations.
5. Cluster the on-durations into two groups (1-D k-means on log duration, k=2). The ratio between the
   cluster centres should come out near 3:1 — if it doesn't, we're looking at noise, and the UI should
   say so rather than emit garbage. Same trick on the off-durations for the 1 / 3 / 7 gaps.
6. Map to dots and dashes, look up the table, print the text.

This means **the receiver never needs to be told the sender's speed** — it measures it. That property is
the whole basis for step 3 below.

Display: live ROI brightness trace, detected unit length, decoded text, and a confidence readout from
how cleanly the duration clusters separate.

## 3. Phone-to-phone data link

**UART is the right answer, and an earlier draft of this note was wrong to dismiss it.** Both ends run
the same app, so the symbol rate is a shared constant, and a start bit aligns each frame. That works.
The reason it works is worth writing down, because it also shows exactly where it stops working.

### The clock is not the problem

UART's tolerance rule (~2% over a 10-bit frame) is about *drift* — two free-running clocks diverging.
Phone quartz is ±20 ppm. At a 100 ms symbol period, a 100-symbol frame lasts 10 s, over which 20 ppm
accumulates **0.2 ms** of error. Against a 100 ms bit cell that is nothing. Drift is a non-issue here by
three orders of magnitude.

What varies is not the clock but the **actuation latency**: each `setTorchMode` edge lands late by a
variable amount. That splits into two very different quantities:

- **Mean latency** — a constant offset. Harmless: the start bit absorbs it. But if the transmitter loops
  on `sleep(100 ms)`, the mean *period* becomes 100 ms + mean overhead, and a period error accumulates
  exactly like drift. Fix at the source: schedule edges against absolute deadlines
  (`t0 + n × period`), never relative sleeps. Then the mean period is exactly nominal, whatever the HAL does.
- **Jitter** — the random part, roughly ±10–20 ms. It does **not** accumulate. It only has to stay below
  half a bit cell, forever, regardless of frame length.

### The real budget

Per-edge uncertainty at the receiver:

| Source | Magnitude |
|---|---|
| Torch actuation jitter | ±10–20 ms |
| Camera sampling quantisation (30 fps, unsynchronised) | ±16 ms |
| Threshold crossing on a soft edge | a few ms |
| **Total, worst case** | **~±40 ms** |

That must stay under half a symbol period. So the floor is around **100 ms**, and **120–150 ms** buys
real margin. At those rates plain UART framing is correct, simpler than the alternatives, and needs no
phase-locking at all. Above that rate — or on a 60 fps receiver — the budget tightens and self-clocking
starts to earn its keep.

### Frame format

```
idle = dark    start bit = light    8 data bits    stop bit = dark
[ preamble 0xAA × 2 ][ start ][ length ][ payload, bit-stuffed ][ CRC-8 ][ stop ]
```

Three deliberate choices:

- **Idle dark, not idle high.** Textbook UART idles high; over light that means the torch burns
  continuously between frames — battery, LED heat, and a saturated receiver whose auto-exposure has
  nothing to recover from. Invert it.
- **Oversample and vote.** A real UART receiver samples 16× per bit and majority-votes the middle three.
  We get ~3 samples per bit at 30 fps and 100 ms symbols — same trick, thinner margin, still worth doing.
- **Bit-stuffing (or 4b5b).** The one genuine weakness of NRZ over light: a payload byte of `0x00`
  or `0xFF` holds the LED steady for ten symbol periods, during which the adaptive threshold has no
  edges to track and the camera's exposure control drifts. Forcing a transition every ~4 symbols costs
  ~25% overhead, against Manchester's 50%, and removes the failure mode.

The preamble stays, but its job is downgraded from *establishing* the rate to *confirming* it: measure
the observed symbol period, check it against the constant, and reject the lock if it disagrees. Cheap
insurance against decoding ambient flicker as data.

Throughput is unchanged: ~10 symbol/s, 8 data bits per 11-symbol frame, so **roughly one byte per
second**. Half-duplex with ACK/NAK and retransmit.

## 4. Going fast: stop blinking faster, blink wider

The temporal axis is capped and no amount of cleverness moves it:

- **Camera**: 30 fps means a 15 symbol/s Nyquist ceiling. 60 or 120 fps capture helps linearly, but the
  torch can't feed it.
- **Torch**: ~10–20 edges/s through the HAL. Hard stop. Rolling-shutter decoding (kHz from a single LED)
  is real, but it needs a transmitter that can *produce* kHz edges, and `setTorchMode` cannot. Dead end.

So the torch link is a ~5 bit/s channel and always will be. Everything faster has to come from
**spatial and spectral parallelism instead of temporal** — using the camera as the two-megapixel sampler
it is, rather than as a single photodiode.

### Screen as transmitter

The display is vsync-accurate at 60–120 Hz, needs no HAL round trip, and is a 2-D array of independently
addressable emitters with three colour channels. Transmit a grid of tiles; the receiver locates the
screen via corner markers, rectifies the perspective with a homography, and samples tile centres.

Tile size budget, screen filling ~60% of a 1080p camera frame:

| Grid | Camera px per tile | Verdict |
|---|---|---|
| 8×8 | 81 | trivially safe |
| 16×16 | 40 | safe |
| 32×32 | 20 | workable, watch moiré |
| 48×48 | 14 | marginal |

Throughput, being honest about the overheads (frame duplication for capture sync, FEC, sync markers):

| Configuration | Rate |
|---|---|
| 16×16 mono, frames sent twice | ~1.5 kbit/s |
| 32×32 × 2 bits of colour, frames sent twice | ~12 kbit/s |
| 32×32 × 3 channels, no duplication, 30 fps capture | ~74 kbit/s (theoretical ceiling) |

Published phone screen-to-camera systems land in the **1–10 kbit/s** range in practice, so the middle row
is the realistic target and the bottom row is what the physics allows before real-world losses.
Either way it is **three orders of magnitude** past the torch.

### What "both ends are the same app" actually buys

Not the symbol rate — that was free anyway. The valuable parts:

- **A calibration handshake.** Before payload, the transmitter shows a known pattern: colour swatches,
  a tile grid, a timing ramp. The receiver measures real per-channel crosstalk, blur, usable tile size
  and frame-sync behaviour, then picks the operating point. The link adapts to the actual pair of phones
  and the actual lighting instead of to a guess.
- **The slow link as the control channel for the fast one.** The torch link is bidirectional, robust, and
  needs no aiming. Use it to negotiate: receiver reports back "32×32, green channel only, you're tearing,
  slow to 15 fps". Two channels, one carrying metadata about the other.
- **A shared PRNG seed.** Normally every coded packet carries its own index or seed, several bytes of
  pure overhead per frame. With a pre-agreed seed both ends derive the same sequence, so a frame needs
  only a counter.
- **No negotiated formats.** Payload structure, colour space, grid geometry, code rate — all constants.

### What "slight errors are OK" buys, and what it doesn't

It buys the removal of the retransmission protocol, which is the single biggest simplification available.
The right tool is a **fountain code** (LT or Raptor): the transmitter loops emitting encoded droplets
indefinitely, the receiver collects any K(1+ε) of them and reconstructs. No ACKs, no retransmit logic,
no frame-loss bookkeeping — losing frames to tearing, hand shake or someone walking past just means
waiting slightly longer. For a one-way lossy optical broadcast this is close to the ideal design.

It does **not** mean tolerating bit errors in the payload. A flipped bit inside a compressed or
structured file is not a "slight" error, it's a corrupt file. The layering that resolves this:

- **Within a frame** — CRC to detect, Reed–Solomon to correct; a frame that still fails is simply discarded.
- **Across frames** — the fountain code treats those discards as erasures and rides through them.

Bit errors get corrected or the frame gets dropped; erasures get absorbed. The place real tolerance pays
off is the *payload type*: a JPEG or an audio clip degrades gracefully under residual error, a ZIP does not.

## 5. The uncomfortable comparison

The app already has an FFT, a tone generator, and mic capture. An acoustic modem — FSK, or chirps at
18–20 kHz where it's inaudible — would do **hundreds of bit/s** with code we mostly already have, and it
works around corners and in pockets. Light is the more beautiful demo; sound is the better channel.
Worth building both and letting the link layer sit on top of either — same framing, same CRC, same ARQ,
different physical layer. That's the design that makes the protocol work reusable.

## 6. What already exists that helps

- `feature-video` — CameraX pipeline, per-frame luminance sampling, temporal analysis
- `core/dsp/Fft` + `Biquad` — if we go acoustic, or want to detect a carrier frequency in the light
- `core/audio/TonePlayer` — the acoustic transmitter, already written
- `core/drone/DroneEngine` — the pattern for a background thread emitting decoded events into Compose state
