# The effect composer — app-driven layered lighting

The composer is how lighting looks are built in this app: a **tree of layers and
folders**, each layer an independent effect, combined with the firmware's blend
modes and streamed to the tail over FF0A direct drive.

It replaced the genre-selected `EffectProfile` system. A profile was a fixed set
of knobs on one hard-coded renderer, so a new look meant a new render path; a
composition is an arbitrary arrangement of independent effects, so a new look is
a new *arrangement*. `docs/beatlight.md` is the map of the analysis that feeds
it; this document is the map of what happens after.

## The one idea

Every effect reads the same [`ReactiveContext`](../app/src/main/java/com/tailapp/composer/ReactiveContext.kt):
one snapshot of the analysis, rebuilt each rendered frame.

```
 beat tier ──► beats, BPM, beat phase, bar phase, downbeats ┐
 transient ──► drops, section, build-up ramp                │
 audio     ──► loudness, bass/mid/high, FFT spectrum        ├──► ReactiveContext ──► every layer
 context   ──► genre                                        │
 the tail  ──► taps, gravity, deflection, wag speed         ┘
```

That is the whole point of the design: there is no such thing as an effect that
*cannot* react. A rainbow can step its hue on the beat, a plasma can dim with the
volume, a solid colour can breathe with loudness — because all of them get the
same data, and none of them has to know where it came from.

**The tail is one of those inputs.** Taps from the IMUs (FF07) and the live
motion state (FF02, ~20 Hz) go into the same context as the audio, so an effect
can react to the device's own body: tap it and a ripple starts from the end you
touched, swing it and it glows, tilt the wearer and the downhill side lights up.
Raw degrees are normalised into `-1..1` of the *configured* travel by
`TailTelemetryTracker`, so a look behaves the same on a tail limited to ±30° as
on one with ±90°; wag speed is a derivative it computes from the real notify
interval, because FF02's nominal 20 Hz jitters with BLE scheduling and dividing
by an assumed period would turn connection hiccups into phantom wags. With no
device connected the context carries `TailTelemetry.AT_REST`, so these effects
still render in the desk preview instead of special-casing "no tail".

Effects never touch the microphone, the clock or BLE. They read the context and
write pixels, which is what makes all 20 of them testable with a hand-built
context and no device (`ReactiveEffectsTest` renders every one of them against
silence, a loud downbeat, and a predicted-but-not-yet-arrived beat).

## The tree

```
Composition
├── EffectLayer      solid colour        OVERWRITE
├── GroupLayer  "Analyser"               ADD          ← a folder
│   ├── EffectLayer  spectrum bars       OVERWRITE
│   └── EffectLayer  beat mask           MULTIPLY     ← gates the folder only
└── EffectLayer      drop flash          ADD
```

- **Order is bottom-to-top**, matching the firmware's layer indices: `layers[0]`
  is drawn first and everything after blends over it.
- **A folder composites twice.** Its children blend among themselves into the
  folder's own buffer; that finished result is then blended into the parent as
  one unit. This is what lets a single modulator gate a whole group, and it is
  the property `CompositionRendererTest` pins hardest — a modulator inside a
  folder must not reach the layers beneath the folder.
- **Nesting is unbounded.** Scratch buffers are pooled per depth and reused
  across frames, mirroring `LayerCompositor`'s `temp_buffer_` discipline.
- Every node carries `blendMode`, `opacity`, `enabled` and the four flip/mirror
  flags. All but `opacity` mirror the firmware's own per-layer controls;
  `opacity` is an app-side addition, because app layers are not wire-constrained
  and a per-layer mix is what makes deep stacks tractable.

Blending is [`ColorMath.blend`](../app/src/main/java/com/tailapp/led/ColorMath.kt)
— the integer-exact transcription of the firmware's `color.h` — so a stack built
here behaves the way someone who has built a firmware stack expects.

## Modulators, and why folders exist

Effects in the `MODULATOR` category emit **neutral grey**, not a look. On their
own they are invisible. Multiplied over a folder they modulate everything inside
it at once:

| Modulator | Multiplied over a folder, it… |
|---|---|
| `beat_mask` | pulses the whole group on every beat |
| `volume_dimmer` | makes the group breathe with loudness (or ducks it, inverted) |
| `section_dimmer` | strobes the group through a build-up, dims it through a breakdown |
| `tap_gate` | opens the whole group on a tap and closes it again as it decays |

`section_dimmer` is the old `ReactiveRenderer.sectionGain` recovered as a
composable layer. In the profile system that behaviour was welded into the one
renderer and applied to the entire frame, take it or leave it; here it can
modulate one folder and not another, so a build-up can strobe the beat layers
while an ambient base underneath stays steady.

`ReactiveEffectsTest` asserts every modulator is actually grey — a tinted one
would recolour whatever it modulates rather than dimming it.

## The effect library

25 effects, in five categories. Adding one is **one class with a `SPEC` and one
line in `ReactiveEffects.ALL`**; the compositor, the editor, persistence and the
registry-wide tests all work off `EffectSpec` and need no change.

| Category | Effects |
|---|---|
| **Base** | `solid`, `gradient`, `rainbow`, `plasma`, `fire`, `breathe` |
| **Beat** | `beat_flash`, `beat_ripple`, `ring_chase`, `strobe`, `sparkle`, `bar_sweep`, `drop_flash` |
| **Audio** | `vu_meter`, `spectrum_bars`, `bass_pulse`, `energy_scroll` |
| **Tail** | `tap_ripple`, `motion_glow`, `wag_trail`, `gravity_level` |
| **Modulator** | `beat_mask`, `volume_dimmer`, `section_dimmer`, `tap_gate` |

The **Tail** category is the one that cannot be reproduced on any other lighting
hardware, because its input is the device's own body rather than sound. Those
four are also the only effects that do something in silence, which is what the
`Alive` built-in stack is for.

`spectrum_bars` is the app-side counterpart to the firmware's `audio_freq_bars`,
and a strictly better one: the firmware reads a 128-bin buffer shipped to it over
BLE, while this reads the analysis front-end's own log-spaced filterbank at full
resolution with no round trip.

### Two conventions worth knowing

- **Effects are pure functions of the context where they can be.** Position is
  derived from `timeSeconds` or `secondsSinceBeat` rather than integrated frame
  to frame, so a dropped or late frame skips the animation *ahead* to where it
  should be instead of stalling it. `energy_scroll` is the deliberate exception
  and says so in its own docs: its speed varies with loudness, so its phase has
  no closed form and must be integrated.
- **Nothing random.** Sparkle and fire use a hash of the LED index and a tick,
  never an RNG. A beat is drawn across many frames, and re-rolling per frame
  would shimmer a constellation into mush — as well as making the effect
  impossible to assert on.

## The same analysis can drive the motors

`MotionChoreography` reads the identical `ReactiveContext` and produces four
half-axis targets, streamed to the device over FF0B. The device has motion
patterns of its own, but no microphone — it cannot know where the beat is, how
loud the room is, or that a drop just landed.

It follows the same rules as the effects. Targets are a **pure function of the
context**: position comes from `barPhase` and `secondsSinceDrop` rather than
being integrated, so a late frame moves the tail where it should be instead of
leaving it behind, and the whole thing is testable against a hand-built context.

Two decisions worth knowing:

- **One sweep per bar, not per beat.** At 128 BPM a per-beat wag would be four
  sweeps a second — the mechanism cannot follow that, and it reads as vibration
  rather than dance.
- **Motion is a separate opt-in from lighting**, off by default and persisted
  separately, and it crosses a separate seam (`DeviceMotionStream`). A stack that
  only changes colour must never start a worn tail swinging on its own.

Nothing here knows the mechanism's travel or top speed. The device clamps every
target to its own axis limits and shapes it through the jerk-limited profiles,
which is what makes it safe to drive a physical mechanism from a phone that is
not real-time.

## Parameters

Each effect declares a schema of `EffectParam`s — `Scalar`, `Color`, `Choice`,
`Toggle` — and the editor renders the right control for each with **no
per-effect UI code**.

Values are all stored as a single `Float`: colours as their packed `0xRRGGBB`
int (exact in a Float up to `0xFFFFFF`, well inside its 24-bit mantissa), choices
as the option index, toggles as `0`/`1`. That keeps storage and the saved format
uniform — the same trick as the firmware's all-`float` 8-slot parameter block,
only keyed by name and typed by the schema.

> The subclasses are named `Scalar`/`Choice`/`Toggle` rather than the obvious
> `Float`/`Enum`/`Bool` because a nested class called `Float` shadows
> `kotlin.Float` throughout the sealed class's own body.

## Threading, and why edits are safe mid-session

`LightingEngine` confines its analysis, render and layout loops to a **single**
thread. The composer's rule follows from that:

- The tree is **immutable**. The editor rebuilds it on every change and hands the
  new snapshot to `CompositionScene.setComposition`.
- `setComposition` is the only cross-thread entry point. It parks the tree in a
  `@Volatile` field; the scene swaps it in at the top of the next `render`, on
  the render thread. The loop only ever sees a finished tree, never a
  half-applied edit.
- **Running state survives edits.** `CompositionRenderer.setComposition` diffs
  the incoming tree against its live effect instances by layer id: a layer whose
  effect id is unchanged keeps its instance — and therefore its decay envelopes,
  phase and counters — and merely takes the new parameters. Only a changed effect
  id builds a new instance. This is exactly `LedStackRenderer.setState`'s rule
  for the firmware stack. Without it every slider drag would restart every
  animation in the stack.

Layer ids must therefore be unique across the whole tree; two layers sharing one
would share an instance and its animation state. `duplicateNode` reassigns ids
throughout a copied subtree for this reason, and both `CompositionEditsTest` and
`CompositionLibraryTest` assert uniqueness.

## Normalising the audio

Raw `rms` and band energies are tiny, level-dependent numbers. `CompositionScene`
smooths each (fast attack, slow release, so an effect sees a musical envelope
rather than the waveform's jitter) and then divides by a slow-decaying recent
peak — [`AdaptivePeakNormalizer`](../app/src/main/java/com/tailapp/beat/AdaptivePeakNormalizer.kt),
reused from the beat tier.

That is what makes a threshold like "fire above 0.15" mean the same thing on a
quiet phone mic and a loud line input.

The **spectrum is normalised against one shared peak**, not per band.
Normalising each band against its own peak would flatten the spectrum into noise
— every band, however quiet, would reach 1 eventually, and a spectrum analyser
would show a full-height wall on silence.

## Persistence

Compositions are saved as JSON in a `composer_config` `SharedPreferences`, via a
**hand-written** JSON reader/writer (`Json.kt`). That is not stubbornness:
`unitTests.isReturnDefaultValues = true` turns every `org.json` call into a stub
returning zeros, so a round-trip could not be tested through the platform library
at all — the same reason `GenreLabels` and the test-side `BeatReference` parse by
hand. No third-party JSON dependency exists in this project either.

`CompositionSerializer` is **forgiving on read, strict on write**. A saved
composition outlives the build that wrote it, so every field is optional on read,
unknown parameter keys are dropped by `ParamBag`, and a layer naming an effect
this build no longer has still loads — it simply renders nothing. Only a
document that is not JSON at all fails, and then the caller falls back to a
built-in. Blend modes are stored **by name**, not by their wire id, because the
ids belong to the BLE protocol and are free to change there without invalidating
everything a user has saved.

`CompositionLibrary` ships six built-in stacks and holds the user's. A user entry
**shadows** a built-in with the same id, which makes the built-ins editable
without being destructible: saving over "Pulse" stores a copy under the same id,
and resetting deletes that copy so the original reappears. Nothing the user can
do leaves them with no compositions at all.

## Where it lives

| File | Contents |
|---|---|
| `composer/ReactiveContext.kt` | the per-frame analysis snapshot + its envelopes |
| `composer/TailState.kt`, `TailTelemetryTracker.kt` | the tail's own body as an input: taps, gravity, deflection, wag speed |
| `composer/ReactiveEffect.kt` | effect base class, flip/mirror transform |
| `composer/EffectParam.kt` | parameter schema + `ParamBag` storage |
| `composer/EffectSpec.kt`, `ReactiveEffects.kt` | registry |
| `composer/LayerNode.kt`, `Composition.kt` | the immutable tree |
| `composer/CompositionRenderer.kt` | recursive compositor |
| `composer/CompositionScene.kt` | analysis → context → render → output |
| `composer/CompositionEdits.kt` | pure tree operations for the editor |
| `composer/MotionChoreography.kt` | the same context, turned into motor targets |
| `composer/FirmwareExport.kt` | mapping a stack onto the device's own layers |
| `composer/CompositionSerializer.kt`, `Json.kt`, `CompositionLibrary.kt` | persistence |
| `composer/effects/` | the 20 effects |
| `ui/screen/EffectComposerScreen.kt`, `viewmodel/EffectComposerViewModel.kt` | the editor |

## Testing

Everything outside the Compose layer is plain JVM Kotlin and runs under
`gradlew.bat testDebugUnitTest`, with no mic and no device.

- `CompositionRendererTest` asserts **exact pixels**, worked out by hand from the
  firmware's blend arithmetic rather than recorded from the code. A test that
  asserts whatever the code already does cannot catch the code being wrong.
- `ReactiveEffectsTest` is the blanket check every new effect inherits for free:
  unique ids, defaults inside their declared ranges, no throwing or clipping on
  any pipeline state, determinism, and greyness for modulators.
- `CompositionLibraryTest` validates the built-ins as data — every parameter key
  exists in its effect's schema and every value is in range. A typo'd key would
  otherwise silently render as the schema default with nothing to indicate why.
  It also asserts every built-in renders *something* on a loud beat.
- `CompositionSceneTest` covers the analysis→context bridge: the calibration
  offset, beat phase, and the audio normalisation above.
