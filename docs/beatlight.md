# BeatLight — beat / drop / genre-reactive lighting

BeatLight turns the phone's mic into a lighting controller for the tail: it
tracks beats, spots drops and build-ups, recognises the genre, and drives the
LED strip accordingly — all on-device, no network calls in the analysis path.

This document is the map. The wire protocol lives in TailFirmware's
`docs/ble-protocol.md`; the app's general architecture lives in `CLAUDE.md`.

## Three tiers, three rates

```
 Mic ──(Oboe)──► FloatRingBuffer ──► FeatureExtractor (shared STFT + log filterbank)
                                              │
        ┌─────────────────────────────────────┼──────────────────────────────┐
        ▼                                     ▼                              ▼
  ActivationSource                    Transient statistics         Discogs-EffNet
  (spectral flux now,                 (RMS / bass / centroid         (ONNX, on-device)
   ONNX CRNN later)                    / onset density)                      │
        ▼                                     ▼                        ┌─────┴─────┐
  TempoEstimator ──► BeatTracker        DropDetector                GenreHead  SectionState
        │                                     │                          │        │
        ▼                                     ▼                          ▼        ▼
    BeatEvent                        DropEvent / SectionStateUpdate   GenreState
        └─────────────────────────────────────┴──────────────────────────┴────────┘
                                              ▼
                                      EffectController
                                    (profile per genre, debounced)
                                              ▼
                                        ReactiveRenderer
                                     (renders into a PixelBuffer)
                                              ▼
                                        LightingOutput
                     ┌────────────────────────┴────────────────────────┐
                     ▼                                                 ▼
        TailDirectLedOutput (FF0A)                        Compose preview
```

| Tier | Rate | Produces | Drives |
|---|---|---|---|
| Beat | 50 fps (441-sample hop @ 22050 Hz) | `BeatEvent` | per-beat triggers |
| Transient | ~5-10 Hz | `DropEvent`, `SectionStateUpdate` | drop hits, build-up ramps, breakdown dimming |
| Context | every 3 s (one 2.048 s Discogs-EffNet patch) | `GenreState` | which effect profile is active |

## Module map

| Package | Contents |
|---|---|
| `com.tailapp.audio` | `AudioSource` (Oboe + `AudioRecord` fallback), `FloatRingBuffer`, `FeatureConfig`/`FeatureFrame`, `FeatureExtractor`, `dsp/` |
| `com.tailapp.beat` | `ActivationSource`, `SpectralFluxActivationSource`, `CrnnActivationSource`, `BeatModelStore`, `TempoEstimator`, `BeatTracker`, `BeatEvent` — see [beat-model.md](beat-model.md) |
| `com.tailapp.drop` | transient detector, section-state tracker, `DropEvent`, `SectionState` |
| `com.tailapp.genre` | `GenreState`, `GenreClassifier`, `EffnetMelSpectrogram`, `OnnxGenreClassifier`, `GenreModelStore` — see [genre-model.md](genre-model.md) |
| `com.tailapp.effects` | `EffectProfile`, `EffectController`, `ReactiveRenderer` |
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
| WLED over UDP/OSC as the lighting output | The tail over BLE FF0A direct pixel streaming | The lighting hardware is the tail. `LightingOutput` stays the seam, so a WLED backend is still a drop-in. |
| BeatNet+ CRNN (ONNX) from the start | DSP onset/tempo tracker first, behind `ActivationSource` | Ships a working, fully-tested pipeline without a multi-gigabyte Python toolchain in the critical path. The CRNN swaps in behind the same interface. |
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
- `BeatNetFrontEndParityTest` does the same for the beat tier and reaches the
  opposite conclusion: our shared front-end is **not** BeatNet's. It pins each of
  the six divergences with its measured size, so every one of those assertions
  fails the day someone closes the gap. See [beat-model.md](beat-model.md).

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
| Effect profiles, controller, renderer | done |
| LightingEngine + session + service | done |
| BeatLight screen (monitor, calibration, profiles) | done |
| ONNX genre model (Discogs-EffNet) | done — installed onto the device, not shipped; falls back to `NoGenreClassifier` when absent |
| ONNX beat model (BeatNet CRNN) | exported and verified; `CrnnActivationSource` written and tested — **not switched on**: the shared front-end is not the one BeatNet was trained on. See [beat-model.md](beat-model.md) |
| Front-end parity with BeatNet (the plan's Phase 2 gate) | **failed, measured** — mean \|diff\| 0.210 on a 0..1.954 range; six of eight properties differ. `FeatureConfig`'s defaults deliberately unchanged |
| Particle-filter beat decoder | the intended replacement for `BeatTracker`; not yet implemented |
| On-device verification | see [beatlight-manual-checks.md](beatlight-manual-checks.md) — needs a phone and the tail |

### What is deliberately not done

The plan's two neural beat pieces — BeatNet's CRNN and its particle-filter
decoder — deliberately came after the DSP tracker rather than instead of it.
The DSP tracker in `beat/` is honest about being the MVP the plan asks for
first: measured on synthetic grids it holds 90/128/174 BPM to within 1 BPM with
over 90% of beats inside the standard ±70 ms window, and it recovers from a
tempo change within a few bars. It is weaker than a particle filter on sparse
percussion, rubato and half-time feels, and `docs/beatlight-manual-checks.md`
asks specifically for those cases to be reported.

Because `ActivationSource` is the seam, the CRNN replaces only the activation
function, and the decoder replaces only `BeatTracker` — neither touches the
front-end, the transient tier or anything downstream.

### The CRNN is built but not switched on

BeatNet's CRNN is now exported to ONNX (`tools/export_beatnet.py`, verified to
3.6e-7 against PyTorch both whole-sequence and frame-by-frame) and
`CrnnActivationSource` runs it one frame at a time with the LSTM state carried
explicitly. It is inert anyway, for a reason worth stating in the map rather than
only in [beat-model.md](beat-model.md):

**`FeatureConfig`'s defaults do not match BeatNet's `log_spect.py`, despite the
comment saying they were chosen to.** Measured against BeatNet's own extractor,
sample rate, hop, bands-per-octave and the log compression match; the window
(1411 vs 2048), the band count (136 vs 205), the lowest band centre (46.85 vs
30 Hz), the filter normalisation (unit area vs unit peak), the frame alignment
(centred vs window-end) and the model's input vector (bands ‖ positive difference
vs bands) do not.

Feeding the model our frames anyway was tried and measured: it reports half-time
and the downbeat channel collapses from twelve clean peaks to one. So
`CrnnActivationSource.create` validates the geometry of the frames it will be fed
and returns null on the shipped configuration, and `AppContainer` falls back to
`SpectralFluxActivationSource`.

`FeatureConfig`'s defaults were left alone on purpose — the DSP tracker, the
tempo estimator and the transient tier are all calibrated against them, and their
tests assert numbers, not shapes. A BeatNet front-end has to be a *second*
extractor (a 1411-point DFT, madmom's unique-bin unit-area filterbank, centred
frames), chosen where `LightingEngine` builds the extractor — the seam hands an
`ActivationSource` a `FeatureFrame`, not audio, so it cannot choose its own.
