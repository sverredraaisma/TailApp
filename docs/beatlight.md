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
  ActivationSource                    Transient statistics            Embedding model
  (spectral flux now,                 (RMS / bass / centroid           (ONNX, later)
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
| Context | every 1-3 s | `GenreState` | which effect profile is active |

## Module map

| Package | Contents |
|---|---|
| `com.tailapp.audio` | `AudioSource` (Oboe + `AudioRecord` fallback), `FloatRingBuffer`, `FeatureConfig`/`FeatureFrame`, `FeatureExtractor`, `dsp/` |
| `com.tailapp.beat` | `ActivationSource`, `TempoEstimator`, `BeatTracker`, `BeatEvent` |
| `com.tailapp.drop` | transient detector, section-state tracker, `DropEvent`, `SectionState` |
| `com.tailapp.genre` | `GenreState`, classifier wrapper |
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
| Genre head trained on the owner's labelled library | Generalised pretrained classifier only | Requested: no personally-trained model. |
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

## Status

| Phase | State |
|---|---|
| Contracts + license | done |
| Oboe capture | in progress |
| Feature extraction | in progress |
| Beat / downbeat / tempo (DSP) | in progress |
| FF0A direct streaming | in progress |
| LED engine port | in progress |
| Drop / build-up / breakdown | not started |
| Effect controller + profiles | not started |
| UI (monitor, preview, calibration) | not started |
| ONNX beat + genre models | not started |
