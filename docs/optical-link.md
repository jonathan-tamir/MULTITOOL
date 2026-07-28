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

The instinct to reach for UART is right, but its one assumption is the thing that breaks here.

**Why UART doesn't survive the trip.** Async serial works because both ends already agree on a baud rate
and their clocks stay within ~2% over a 10-bit frame. Between two phones, over a HAL with tens of
milliseconds of jitter, sampled by a camera whose frame clock is unrelated to either — that agreement
doesn't exist. I2C solves the same problem with a separate clock wire; we have one wire, and it's a lamp.

**So: self-clocking.** Manchester encoding sends every bit as a *transition* — high-to-low is 0,
low-to-high is 1 — so the clock is recoverable from the data itself, and the signal is DC-balanced,
which also makes the adaptive threshold trivial. Cost: half the raw rate. Worth it.

Frame structure, in the spirit of a UART frame but self-clocked:

```
[ preamble: ~16 alternating symbols ][ start delimiter ][ length ][ payload ][ CRC-8 ]
```

- **Preamble** — the receiver measures the symbol period from it and phase-locks (early/late gate:
  compare energy just before and just after each expected edge, nudge the sampling phase). This is the
  step that replaces "both sides agreed on 9600 baud".
- **Start delimiter** — a pattern that can't occur in Manchester data (e.g. a deliberate code violation),
  so the payload boundary is unambiguous.
- **CRC-8** — cheap, catches nearly everything at this frame size.
- **ARQ** — receiver flashes back a short ACK/NAK; sender retransmits on silence or NAK. Half-duplex,
  turn-taking, which also sidesteps both phones flashing at once.

Realistic throughput: 100 ms half-bit → 5 bit/s. Push the unit to 60 ms → ~8 bit/s. Call it **one byte
per second.** A short text message takes a minute. It will feel like 1840, and that's the charm.

**Geometry.** Torch and back camera sit next to each other, so two phones facing each other works: each
watches the other's torch. Half-duplex avoids the case where one phone's torch blinds its own receiver.

## 4. Where the real speed is, if we want it

**Screen instead of torch.** The display is a far better transmitter than the LED: no HAL round trip,
vsync-accurate at 60–120 Hz, and it's a *2-D array* of independent emitters with *three colour channels*.
Split it into a grid of tiles with corner markers for alignment, encode each tile independently, and the
budget changes completely: 4×4 tiles × 3 channels at 15 symbol/s ≈ **700 bit/s**; 8×8 ≈ a few kbit/s
before sync and error-correction overhead. This is the well-trodden screen-to-camera research direction,
and it's within reach. Downside: phones must face screen-to-camera, so it's one-directional per pair.

**Rolling shutter.** A CMOS sensor exposes rows sequentially, ~10–30 µs apart, so a single frame of a
flashing LED filling the view records ~1000+ time slices as visible stripes. Decode the banding and a
single LED becomes a **kbit/s** channel. Very device-dependent, needs manual exposure and a still hand —
a stretch goal, but the most spectacular version of this idea.

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
