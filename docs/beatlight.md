# BeatLight — beat / drop / genre-reactive lighting

BeatLight turns the phone's mic into a lighting controller for the tail: it
tracks beats, spots drops and build-ups, recognises the genre, and drives the
LED strip accordingly — all on-device, no network calls in the analysis path.

This document is the map. The wire protocol lives in TailFirmware's
`docs/ble-protocol.md`; the app's general architecture lives in `CLAUDE.md`.

## Three tiers, three rates

```
 Mic ──(Oboe)──► FloatRingBuffer ──► FeatureExtractor (shared STFT + log filterbank)
                    │                         │
                    │ (same audio)            │
                    ▼                         │
       BeatNetFeatureExtractor                │
      (1411-pt centred window,                │
       136 unit-area bands)                   │
                    │                         │
                    ▼                         │
        CrnnActivationSource                  │
       (BeatNet CRNN, when installed)         │
                    │                         │
                    │ activation              │
        ┌───────────┴─────────────────────────┼──────────────────────────────┐
        ▼                                     ▼                              ▼
  ActivationSource                    Transient statistics         Discogs-EffNet
  (spectral flux — the                (RMS / bass / centroid         (ONNX, on-device)
   fallback, and the                   / onset density)                      │
   default with no model)                      │                       ┌─────┴─────┐
        ▼                                      ▼                    GenreHead  SectionState
  TempoEstimator ──► BeatDecoder         DropDetector                   │        │
        │                                      │                        │        │
        ▼                                      ▼                        ▼        ▼
    BeatEvent                        DropEvent / SectionStateUpdate   GenreState
        └─────────────────────────────────────┴──────────────────────────┴────────┘
                                              ▼
                                       CompositionScene
                            (builds a ReactiveContext for the frame)
                                              ▼
                                      CompositionRenderer
                     (the user's tree of layers and folders — see composer.md)
                                              ▼
                                        LightingOutput
                     ┌────────────────────────┴────────────────────────┐
                     ▼                                                 ▼
        TailDirectLedOutput (FF0A)                        Compose preview
```

## One microphone, two consumers

The FF05 visualiser stream and a BeatLight session used to open separate mic
captures, which made them mutually exclusive — a second capture generally
returns silence rather than an error, so starting a session silently killed the
device's own audio effects.

They share one capture now. `FeatureFrameFftEncoder` derives the device's FF05
frame from the analysis frames the session already produces: the log-spaced
filterbank is regrouped into the configured bin count over the configured
frequency window (taking each group's **peak**, since a mean washes a narrow
peak out and the device's bar effects are drawing peaks), and the same
`AdaptivePeakNormalizer` the composer uses maps levels onto `0..255` so bars
reach full height at conversational volume rather than only when clipping.

Each frame also carries a **beat trailer** — phase, BPM, and beat/downbeat/drop
flags. The device has no microphone and no beat tracker, so without this its
own effects and motion patterns cannot know where the beat is; three bytes a
frame hand them the phone's tracker's output. It is additive on the wire: the
firmware reads exactly `num_bins` of bin data and ignores anything after, so new
apps work with old firmware and vice versa. Forwarding is decimated to ~30 fps
to match what the firmware's staleness window and render loop are built for.

**Everything above the scene is analysis; everything below it is the composer.**
The three tiers produce beats, transients and a genre label; `CompositionScene`
folds those — plus the loudness and FFT spectrum from the same feature frames —
into one `ReactiveContext` per rendered frame, and every layer in the user's
stack reads it. [composer.md](composer.md) is the map of that half.

**Two front-ends, one beat tier.** The shared `FeatureExtractor` feeds everything
— tempo, transients, timestamps, and the fallback activation. When the BeatNet
CRNN is installed, the same audio *also* goes through `BeatNetFeatureExtractor`,
whose 272-float frames are the only thing the model ever sees; its activation
replaces spectral flux and nothing else changes. The two extractors' frames pair
three apart with a 0.86 ms residual — see [beat-model.md](beat-model.md).

| Tier | Rate | Produces | Drives |
|---|---|---|---|
| Beat | 50 fps (441-sample hop @ 22050 Hz) | `BeatEvent` | per-beat triggers |
| Transient | ~5-10 Hz | `DropEvent`, `SectionStateUpdate` | drop hits, build-up ramps, breakdown dimming |
| Context | every 3 s (one 2.048 s Discogs-EffNet patch) | `GenreState` | an input effects may read; shown on the monitor |

## Module map

| Package | Contents |
|---|---|
| `com.tailapp.audio` | `AudioSource` (Oboe + `AudioRecord` fallback), `FloatRingBuffer`, `FeatureConfig`/`FeatureFrame`, `FeatureExtractor`, `BeatNetFeatureExtractor`/`BeatNetFrame`, `dsp/` (`Fft`, `BluesteinFft`, `LogFilterbank`, `Resampler`) |
| `com.tailapp.beat` | `ActivationSource`, `SpectralFluxActivationSource`, `CrnnActivationSource`, `BeatModelStore`, `TempoEstimator`, `BeatDecoder` (`BeatTracker`, `ParticleFilterBeatDecoder`), `BeatEvent` — see [beat-model.md](beat-model.md) |
| `com.tailapp.drop` | transient detector, section-state tracker, `DropEvent`, `SectionState` |
| `com.tailapp.genre` | `GenreState`, `GenreClassifier`, `EffnetMelSpectrogram`, `OnnxGenreClassifier`, `GenreModelStore` — see [genre-model.md](genre-model.md) |
| `com.tailapp.effects` | `LightingEngine`, `BeatLightSession`, `BeatLightService`, `DeviceAudioStream` |
| `com.tailapp.composer` | the effect graph: `ReactiveContext`, `ReactiveEffect`, `CompositionRenderer`, `CompositionScene`, the 20 effects — see [composer.md](composer.md) |
| `com.tailapp.lighting` | `LightingOutput`, `TailDirectLedOutput`, preview sink |
| `com.tailapp.led` | Kotlin port of the firmware LED engine — coordinates, effects, compositor |
| `app/src/main/cpp` | Oboe capture + lock-free ring buffer |

## The LED engine is a firmware port, not a lookalike

`com.tailapp.led` mirrors TailFirmware's rendering code file for file — the
coordinate map from `led_matrix.cpp`, `transform_coord` from `led_effect.h`, the
integer `hsv_to_rgb` and blend helpers from `color.h`, all six effects from
`led/effects/`, and the compositor. That buys two things:

1. **Live preview.** The app can show what the device is displaying, for the
   device's *own* effect stack, without the device sending pixels back.
2. **Direct mode.** The beat-reactive renderer draws into the same
   `PixelBuffer` layout FF0A expects, so a rendered frame streams with no
   conversion pass.

Where C++ integer truncation or a `uint8_t` cast changes a result, the Kotlin
reproduces it. Divergence here shows up as a preview that quietly lies.

## Deviations from the original project plan

The plan this was built from targeted a standalone app driving WLED. Adapting it
to TailApp changed four things; each was a deliberate call, not a shortcut.

| Plan said | Built instead | Why |
|---|---|---|
| One lighting profile auto-selected per genre | A user-built tree of layers and folders (the **composer**), with genre demoted to one more input effects may read | A profile was knobs on a single hard-coded renderer, so every new look meant a new render path — and only the handful of things that renderer already did were reachable. The composer makes a look an *arrangement* of independent effects instead, and gives every one of them the beat, BPM, loudness and FFT. See [composer.md](composer.md). |
| WLED over UDP/OSC as the lighting output | The tail over BLE FF0A direct pixel streaming | The lighting hardware is the tail. `LightingOutput` stays the seam, so a WLED backend is still a drop-in. |
| BeatNet+ CRNN (ONNX) from the start | DSP onset/tempo tracker first; the CRNN now runs alongside it when installed | Ships a working, fully-tested pipeline without a multi-gigabyte Python toolchain in the critical path. The CRNN replaces only the activation function, and only when its model is on the device. |
| Genre head trained on the owner's labelled library | Essentia's Discogs-EffNet + `genre_discogs400`, unmodified | Requested: no personally-trained model. Its weights are CC BY-NC-ND, so they are fetched and converted by `tools/`, never committed — the app ships the code and the models are installed onto the device. |
| Section head trained on hand-marked timestamps | Heuristic section-state machine on the transient tier | The training data for it was the same labelled set that was dropped. Thresholds are config, not constants. |

Oboe *was* kept as specified: capture runs through `app/src/main/cpp`, with an
`AudioRecord` implementation of the same interface as a fallback for devices and
emulators that cannot open a low-latency input stream.

## Testing

Everything outside the Compose layer and the JNI bridge is plain JVM Kotlin and
covered by `gradlew.bat testDebugUnitTest`:

- `FloatRingBufferTest` pins the capture buffer's contract, including the
  concurrent producer/consumer and overrun cases. The C++ ring buffer in
  `app/src/main/cpp/ring_buffer.h` mirrors it deliberately.
- The DSP suites run synthetic click tracks through the real front-end and assert
  tempo and beat-alignment error against a known grid.
- The LED suites assert parity with the firmware's arithmetic, not just internal
  consistency.
- `EffnetMelSpectrogramTest` asserts parity with the *model's* front-end: it
  diffs against a reference dump from `tools/dump_reference.py` and skips with a
  reason if that dump has not been generated. A neural front-end has no
  self-evident right answer to assert; matching what the model was trained with
  is the only meaningful check.
- `BeatNetFeatureExtractorTest` is the beat tier's equivalent, and the gate the
  project plan puts in front of any model work: every one of 299×272 values
  diffed against madmom's own output, **max 1.1e-6** on a 0..1.954 range.
  `BluesteinFftTest` backs it by checking the arbitrary-size DFT underneath
  against a naive O(n²) one at eleven sizes (worst 2.5e-7).
- `BeatNetFrontEndParityTest` measures the *shared* front-end against the same
  dump and reaches the opposite conclusion: it is **not** BeatNet's, by a mean of
  0.210. That is why there are two extractors, and its assertions now guard
  against the shared one being quietly moved. See [beat-model.md](beat-model.md).

## Status

| Phase | State |
|---|---|
| Contracts + AGPL-3.0 license | done |
| Oboe capture + ring buffer | done |
| Feature extraction (shared front-end) | done |
| Beat / downbeat / tempo (DSP decoder) | done |
| Drop / build-up / breakdown | done |
| FF0A direct pixel streaming | done |
| LED engine port + live preview | done |
| Effect profiles, controller, renderer | **replaced** by the composer — see [composer.md](composer.md) |
| Effect composer (layer/folder graph, 20 effects, editor, persistence) | done |
| LightingEngine + session + service | done |
| BeatLight screen (monitor, calibration, stack selection) | done |
| ONNX genre model (Discogs-EffNet) | done — installed onto the device, not shipped; falls back to `NoGenreClassifier` when absent |
| Arbitrary-size DFT (`BluesteinFft`) | done — 1411-point transform over the radix-2 `Fft`; 2.5e-7 worst relative error against a naive double-precision DFT |
| BeatNet front-end (`BeatNetFeatureExtractor`) | done — madmom's pipeline ported; **max 1.1e-6** against madmom's own output over 81 328 values. ~0.1 ms per frame; both front-ends together ~0.15 ms of the 20 ms hop |
| Front-end parity with BeatNet (the plan's Phase 2 gate) | **passed** — by a second, dedicated extractor. The shared `FeatureConfig` is deliberately unchanged and still 0.210 out, which is why there are two |
| ONNX beat model (BeatNet CRNN) | exported, verified, wired — but **disabled by default** (`USE_CRNN_BEAT_ACTIVATION = false`). On a real phone mic its activation is weak and temporally smeared and the tempo drifts; spectral flux is the default and is stable. Re-enable for a cleaner source. See [beat-model.md](beat-model.md) |
| Particle-filter beat decoder | done — `ParticleFilterBeatDecoder`, selectable alongside `BeatTracker`; see `BeatDecoderComparisonTest`. Confirmed stable on real mic audio via spectral flux |
| Accuracy on real mic audio | measured on device: **spectral flux is stable** (steady BPM), **the CRNN is not** (drifts). Per-frame CRNN inference cost still unmeasured. |
| On-device verification | see [beatlight-manual-checks.md](beatlight-manual-checks.md) — needs a phone and the tail |

### The DSP tracker is still the floor, not a leftover

The plan's two neural beat pieces — BeatNet's CRNN and its particle-filter
decoder — deliberately came after the DSP tracker rather than instead of it, and
the DSP tracker did not go away when they arrived. It is what runs on every
device with no model installed, which is every fresh install: measured on
synthetic grids it holds 90/128/174 BPM to within 1 BPM with over 90% of beats
inside the standard ±70 ms window, and it recovers from a tempo change within a
few bars. It is weaker than a particle filter on sparse percussion, rubato and
half-time feels, and `docs/beatlight-manual-checks.md` asks specifically for
those cases to be reported.

The two neural pieces are independent replacements at two different seams: the
CRNN replaces only the activation function, the particle filter replaces only the
decoder. Neither touches the transient tier or anything downstream, and either
can be absent.

### Why the CRNN needed a second front-end

**`FeatureConfig`'s defaults do not match BeatNet's `log_spect.py`, despite the
comment that used to say they were chosen to.** Measured against BeatNet's own
extractor, sample rate, hop, bands-per-octave and the log compression match; the
window (1411 vs 2048), the band count (136 vs 205), the lowest band centre (46.85
vs 30 Hz), the filter normalisation (unit area vs unit peak), the frame alignment
(centred vs window-end) and the model's input vector (bands ‖ positive difference
vs bands) do not.

Feeding the model our frames anyway was tried and measured: it reports half-time
and the downbeat channel collapses from twelve clean peaks to one.

Moving `FeatureConfig` was not the answer either — the DSP tracker, the tempo
estimator and the transient tier are all calibrated against its 205 bands from a
2048-sample window, and their tests assert numbers, not shapes. So BeatNet's
front-end is a **second** extractor: `BeatNetFeatureExtractor`, on a 1411-point
Bluestein DFT, with madmom's unique-bin unit-area filterbank and centred frames.
It matches madmom's own output to **1.1e-6** across 81 328 values and costs
about 0.1 ms per frame.

`LightingEngine` is where the two meet, because the `ActivationSource` seam hands
out a `FeatureFrame` rather than audio and so cannot choose its own front-end.
The engine pushes each chunk through both extractors, pairs shared frame `i` with
BeatNet frame `i + 3` (a 0.86 ms residual — [beat-model.md](beat-model.md)
derives it), and drives the decoder through `BeatDecoder.process(frame,
activation)`. With no model installed neither the extractor nor the source is
constructed at all, and `BeatLightState.activationSource` — shown on the monitor
card — says which one is live.
