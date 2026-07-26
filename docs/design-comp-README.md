# Field Lab — instrument app shell

Android phone prototype (412 × 892) for a scientific/engineering utility app: category home screen,
per-category subspaces, a hidden left toolbar drawer, and a shared cinematic takeover animation for
launching any utility.

## Files

| File | What it is |
|---|---|
| `Instrument.dc.html` | The whole design — opens directly in any browser. Template + logic in one file. |
| `android-frame.jsx` | Device bezel/status bar/gesture nav (starter component, loaded by the DC). |
| `Instrument-standalone.html` | Single self-contained file, no network or sibling files needed. |

## Structure of `Instrument.dc.html`

- **`<helmet>`** — fonts, `@keyframes` (all animation), body reset.
- **Template** — the markup: left brief column, phone (`<x-import AndroidDevice>`), and inside it three
  screens gated by `<sc-if>`: home / category / utility, plus the edge toolbar button, the drawer, and
  the animation overlay mount `{{ overlay }}`.
- **Logic (`class Component`)** — data + state; `renderVals()` returns everything the template reads.

## Data model

`CATS` is the single source of truth. Adding a category or utility is a data edit only:

```js
{ key: 'thermal', name: 'Thermal', code: 'THERMAL', count: 2,
  desc: 'One line describing the domain.',
  utils: [{ name: 'IR histogram', tag: 'IR', meta: 'live · 8–14 µm' }] }
```

Then add a hue in `HUE` (`thermal: 40`) and a backdrop pattern in `motifs()` for that key. The home
grid, subspace list, counts, recents and accents all follow automatically.

## Navigation / state

`state.view` is `'home' | 'category' | 'utility'`.
`state.cat`, `state.util` hold the selection; `state.fromRecent` makes the utility back arrow return
to the home screen when the tool was launched from the drawer's Recent actions (otherwise it returns
to its category). `run(kind, ms)` mounts an overlay animation for `ms` then clears it.

## Animations (locked-in set)

- **Category entry — “zoom grid”** (620 ms): category motif scales up through the screen with the
  category code holding centre, then dissolves. `overlayKind === 'catZoom'`.
- **Utility takeover — “signal ignition”** (1560 ms, shared by every utility): a waveform is drawn
  across the screen by an animated `stroke-dashoffset` while a glowing scan column rips left→right,
  then the screen splits horizontally and slides open onto the utility. `overlayKind === 'signal'`.

Overlays are built in `overlay()` with `React.createElement` so their animation state survives
re-renders. **Hook for per-utility intros:** in `toUtil()` change `this.run('signal', 1560)` to
`this.run(INTRO[name] || 'signal', ms)` and add a branch in `overlay()`.

## Quick settings

Stored in `state.on`. Wired today:

- **Dark theme** — full theming; `theme()` returns every surface/line/text token, accents shift
  lightness for light mode.
- **Grid overlay** — swaps the utility canvas hatch for a measurement grid.
- **Keep awake / Auto-log data / Hi-res render** — reflected in the home status readout.
- **Haptics** — flag only (no browser effect).

## Not built yet

Real mic/camera/IMU capture, permission flow, the utility screens themselves (the canvas is a
placeholder), export/storage screens.
