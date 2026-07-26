# MULTITOOL

An Android field lab: one app, many engineering instruments. Categories on the home screen, each
opening into its own subspace, each utility launching through a shared cinematic takeover.

Built by merging two earlier projects into one shell:

- **JSA** (Jonathan's Spectrum Analyzer) — audio / image / video FFT tooling
- **SRADD / AcousticDroneNode** — single-node acoustic drone detector + its Python training pipeline

## Modules

| Module | Contains |
|---|---|
| `:app` | Launcher activity only — theme wiring, keep-awake, hands off to the shell |
| `:shell` | Navigation (home → category → utility), drawer, quick settings, **tool registry** |
| `:core` | All non-UI engines: FFT, biquads, mic capture, tone/WAV, image + video maths, drone model, mic ownership |
| `:core-ui` | Design system: OkLCH accents, shell tokens, IBM Plex type, motifs, the two takeover animations, shared plots |
| `:feature-audio` | Spectrogram, filter bench, record & clean, tone & tuner |
| `:feature-image` | FFT2, filter lab, spectrum eraser, hybrid images |
| `:feature-video` | Live FFT2, filtered view, motion amplifier, file transform |
| `:feature-drone` | Drone detector screen + `assets/model.json` |
| `:screenshots` | Off-device Paparazzi renders (opt-in, see below) |
| `ml/` | Python: feature extraction, training, on-device export, FPV scoring |

## Adding a tool

`shell/…/Registry.kt` is the single source of truth. One entry gives you the home-grid count, the
subspace row, the recents entry, the accent and the launch animation:

```kotlin
Tool("Level meter", "SPL", "A / C weighting", listOf("FS 48 kHz", "dBA")) {
    LevelMeterScreen(it.settings, it.audio)
}
```

Categories are the same shape — `hue` is an OkLCH hue angle, `motif` the backdrop pattern.

## Design invariants

- One accent hue per category, equal chroma: `accentFor(hue, dark)` = `oklch(0.80 0.12 h)` dark,
  `oklch(0.55 0.15 h)` light. Tool screens read `LocalAccent`, so they inherit the category's hue.
- Two surface elevations only (`card`, `bg`), lines as alpha over the surface.
- IBM Plex Sans for prose, IBM Plex Mono for every numeric, code and metadata string.
- Category entry = zoom-grid (620 ms). Utility entry = signal ignition (1560 ms), shared by all tools.

## Mic ownership

Two `AudioRecord` clients in one process is undefined behaviour, and the shell can move straight
from a spectrum tool into the drone detector. `core/mic/MicOwner` makes the claim explicit: capture
engines acquire before opening the device and release on stop.

## The drone detector's self-test

`Featurizer.kt` is a line-for-line mirror of `ml/features.py`. On entering the tool it extracts
features from a fixed synthetic signal and compares the sum against the Python reference
(`-1176.30`). A `FAIL` line in the event log means the DSP port has drifted — treat detection
numbers as void until it passes.

On-device model quality (leakage-free split, public camera-drone data):
**PR-AUC 0.984, 95 % recall at 91 % precision, 1.55 % FPR.**

## Builds

Every push to `main` builds a debug APK in CI and commits it to `apk/Multitool.apk`, with the
build log at `ci/build-log.txt`.

Screen renders are opt-in so screenshot tooling can never break the APK:

```bash
ENABLE_SCREENSHOTS=1 gradle :screenshots:recordPaparazziDebug
```

CI runs that in a separate non-blocking job and commits the PNGs to `screenshots-out/`.
