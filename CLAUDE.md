# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

TailApp is an Android application (Kotlin, Jetpack Compose) that controls a
wearable tail over Bluetooth Low Energy. The device firmware lives in a separate
repository, **TailFirmware** (`C:\Users\Sverr\CLionProjects\TailFirmware`);
`docs/ble-protocol.md` there is the authoritative wire-format reference and
`main/ble/ble_protocol.h` is the authoritative list of command ids.

## Build & Run

```bash
gradlew.bat assembleDebug          # Build debug APK
gradlew.bat installDebug           # Build and install on a connected device/emulator
gradlew.bat testDebugUnitTest      # Run the JVM unit test suite
gradlew.bat lintDebug              # Android lint (lint errors fail the build)
gradlew.bat clean                  # Clean build outputs
```

On macOS/Linux use `./gradlew` instead of `gradlew.bat`.

The build includes a native step (Oboe capture, `app/src/main/cpp`), so a first
build on a fresh checkout downloads the NDK and CMake and takes several minutes.
`testDebugUnitTest` does not need them — the JVM suite never touches the native
library, which is why almost everything is testable without a device.

## Version Matrix

- Gradle 9.3, AGP 8.13.2, Kotlin 1.9.22
- Compose BOM 2024.02.00, Compose Compiler 1.5.10
- compileSdk/targetSdk 34, minSdk 26
- JVM target: 17

## Architecture

**MVVM with manual DI** — no Hilt/Dagger. Dependencies are created in
`AppContainer` (instantiated by the `TailApp` Application class) and accessed via
`(application as TailApp).container`.

### Layer structure

```
com.tailapp/
├── ble/
│   ├── BleTransport        # Interface: the GATT ops the repository needs
│   ├── BleConnectionManager# Real BleTransport over a single BluetoothGatt
│   ├── BleScanner          # Wraps BluetoothLeScanner, exposes StateFlow<List<BleDevice>>
│   ├── ConnectionState     # DISCONNECTED | CONNECTING | CONNECTED
│   ├── VirtualTailTransport# In-process simulator: answers the real wire protocol, no radio
│   ├── RoutingBleTransport # Picks real vs virtual per connect, by address
│   └── protocol/           # Wire format: command builders, parsers, UUIDs, CRC (24 files)
├── repository/             # DeviceRepository (single source of truth) + CommandAckTracker,
│                           #   FirmwareUpdate, SequenceUpload, BehaviorTableStore
├── model/                  # Ble/Led/Motion/System/Diagnostics/Keyframe/Behavior/Ota/Param models
├── audio/                  # Capture (Oboe + AudioRecord), FftProcessor, feature extraction, dsp/
├── beat/                   # Beat/downbeat/tempo tracking (BeatLight), TempoRange
├── drop/                   # Transient detection, RollingStats, section state (BeatLight)
├── genre/                  # Genre classification (BeatLight)
├── effects/                # LightingEngine + session/service (mic → analysis → frames)
├── composer/               # The effect graph: ReactiveContext, layer/folder tree, 25 effects
├── lighting/               # LightingOutput + FF0A/preview/composite implementations
├── led/                    # Kotlin port of the firmware LED render engine (18 effects)
├── viewmodel/              # One per screen: Scan, DeviceOverview, MotionConfig, LedConfig,
│                           #   AudioConfig, BeatLight, EffectComposer, BehaviorConfig,
│                           #   KeyframeEditor, FirmwareUpdate, Diagnostics
├── navigation/             # NavRoutes (sealed class), TailAppNavHost
├── ui/{theme,screen,components}
├── di/AppContainer
├── TailApp                 # Application subclass
└── MainActivity            # Single Activity entry point

app/src/main/cpp/           # Oboe capture + lock-free ring buffer (JNI)
tools/                      # Python: model download/export, reference data
```

**BeatLight** — the beat/drop-reactive lighting feature — spans
`audio` → `beat`/`drop`/`genre` → `effects` → `composer` → `lighting`, and
renders through the firmware mirror in `led`. `docs/beatlight.md` maps the
analysis half; `docs/composer.md` maps the rendering half. Read both before
touching any of those packages.

The seam between them is `composer/ReactiveContext`: one snapshot of the analysis
(beat timing, BPM, beat/bar phase, loudness, the FFT spectrum, drops, section,
genre) rebuilt each frame, which **every** effect reads. There is deliberately no
such thing as a non-reactive effect.

### Key patterns

- **`BleTransport` is the seam.** `DeviceRepository` depends on the interface, not
  on `BleConnectionManager` (whose constructor touches the Android Bluetooth
  stack). Unit tests drive it with `FakeBleTransport`.
- **BLE operations are mutex-guarded** — Android BLE allows one GATT operation at
  a time, so `BleConnectionManager` serializes reads/writes behind a coroutine
  `Mutex`. FF05 FFT frames use write-without-response and deliberately bypass it.
- **StateFlow everywhere** — `DeviceRepository.deviceState` is the one state
  object screens observe via `collectAsStateWithLifecycle()`. One-shot things
  (taps, command ACKs) are `SharedFlow`s.
- **Connection setup runs in its own job.** The connection-state collector must
  never block, or a disconnect during setup stays invisible to the UI.
- **Optimistic updates + reconcile.** Command methods update the cached state
  immediately, because the device only rebuilds its read buffers at 1 Hz.
- **Runtime permissions** — `ScanScreen` requests `BLUETOOTH_SCAN` +
  `BLUETOOTH_CONNECT` on API 31+ and `ACCESS_FINE_LOCATION` on API 26-30;
  `AudioConfigScreen` and `BeatLightScreen` request `RECORD_AUDIO`. In the
  manifest, `BLUETOOTH_SCAN` carries `neverForLocation` — without that assertion
  the platform withholds every `ScanResult` unless `ACCESS_FINE_LOCATION` is also
  granted, which the app deliberately does not request above API 30. It is honest:
  nothing here derives a location from a scan result. The legacy `BLUETOOTH`,
  `BLUETOOTH_ADMIN` and both location permissions are bounded with
  `maxSdkVersion="30"`; `POST_NOTIFICATIONS` covers the two microphone foreground
  services on API 33+. `allowBackup` is off with a `dataExtractionRules` file:
  saved stacks and paired-device state are local session data, and restoring them
  onto another handset resurrects devices the user no longer owns.
- **The LED engine is a firmware port, not a lookalike.** `com.tailapp.led`
  mirrors TailFirmware's `led/` file for file, C++ integer truncation included,
  because both the live preview and the direct-mode renderer have to produce the
  pixels the device would. A divergence there is a preview that quietly lies.
  That extends to the **output stage** (`LedOutputStage`): master brightness,
  the current limiter, then gamma, in that order. A preview that skipped the
  limiter would show a look that browns out on hardware as if it were fine.
  `LedStackRenderer` runs that stage over every composited frame (and passes
  per-layer opacity through the compositor), which makes a frame out of
  `renderFrame` a **display** frame, not a frame to stream: the device runs its
  own `push` over whatever FF0A delivers, so streaming these pixels would apply
  brightness, the limiter and gamma twice. Today the only caller is the preview
  (`LedPreviewClock`); a direct-mode streamer would want the composite from
  before the stage ran.
- **One render loop, several sinks.** `CompositeLightingOutput` fans a frame out
  to the tail and the on-screen preview at once, in list order, so the hardware
  sink is not waiting behind UI work — and so the preview shows the frame the LEDs
  actually got rather than a second, subtly different render of the same instant.
- **The transient tier measures against its own recent history, never absolutes.**
  `drop/RollingStats` keeps mean and standard deviation over a trailing window in
  O(1) per sample, so a quiet living room and a loud club need the same code and
  no per-genre threshold table. Its running sums are re-summed periodically rather
  than trusted for the length of a whole set.
- **The composer reads the tail, not just the microphone.** Taps (FF07) and the
  motion state (FF02) reach `ReactiveContext` alongside the audio, so an effect
  can react to the device's own body. `TailTelemetryTracker` normalises raw
  degrees against the *configured* travel and differentiates wag speed from the
  real notify interval — FF02's nominal 20 Hz jitters, and dividing by an
  assumed period turns connection hiccups into phantom wags.
- **`beat/TempoRange` is the single source of tempo bounds, and it has two.**
  `MIN_BPM`/`MAX_BPM` (55-215) are what the tier can *represent* — BeatNet's
  bounds, where 215 is load-bearing because it makes 128 BPM's double
  unrepresentable. `SEARCH_MIN_BPM`/`SEARCH_MAX_BPM` (60-200) are the narrower
  band `TempoEstimator` can actually *resolve*: its three-harmonic comb and
  log-normal prior are tuned for it, and widening the search to the representable
  bounds measurably loses the lock — the whole of `BeatTrackerTest` fails at
  55-215, including a clean 128 BPM grid, because the extra lags reshape the
  peak-to-average ratio the acquisition gate reads. That difference is measured,
  not an oversight; widening it is a comb-and-prior redesign with the ±2 BPM
  suites re-derived, not a constant change.
- **`FftProcessor` normalises against a real peak tracker.** Fast attack, slow
  release, with an absolute floor so a quiet room reads quiet instead of being
  normalised back to full scale — the old recurrence converged on three times the
  signal level, so a steady tone reported 85/255 whatever its actual loudness and
  FF05 never used its top half. Two more things there are load-bearing: the frame
  is **zero-padded** to the next power of two rather than truncated to the
  previous one (a 1470-sample frame used to lose 30% of itself and half its
  frequency resolution), and **DC is removed and bin 0 never read** — a mic's DC
  offset lands entirely in bin 0, and at this resolution bin 0 *was* the bottom
  bars, which therefore never moved with the bass.
- **`SectionStateTracker` gates on audio presence.** Its energy statistics are
  z-scores, and a z-score of silence against silence is not small — it is
  meaningless. Without an absolute-ish presence envelope (raw RMS against a
  minutes-long decaying peak) the tracker latches into DROP in an empty room and
  stays there. A breakdown is still audio; an empty room is not.
- **Analysis never runs on the main thread, and never twice at once.**
  `LightingEngine` takes its dispatcher as a parameter for exactly this reason —
  its analysis and render loops sharing a `FeatureExtractor` across threads
  corrupts the extractor's ring buffer, so both are confined to one
  `limitedParallelism(1)` view. **Genre inference is the exception**, on its own
  `genreDispatcher`: an EffNet pass is tens of milliseconds and would otherwise
  stall the render loop for whole frames. It touches nothing the loops touch, and
  its result is published back through `workDispatcher`, which is the only place
  the scene's genre field is written.
- **A composition is immutable; the renderer's effect instances are not.** The
  editor rebuilds the whole tree per edit and hands it to
  `CompositionScene.setComposition`, which parks it in an `AtomicReference` and
  claims it on the render thread at the top of the next frame with a single
  `getAndSet` — so the loop never sees a half-applied edit. The atomic is not
  decoration: a plain `@Volatile` read-then-null loses an edit that lands between
  the read and the write, which is exactly when the user is dragging a slider.
  `CompositionRenderer` then diffs the tree against its live effect
  instances **by layer id**, reusing any layer whose effect id is unchanged so
  decay envelopes and animation phase survive a slider drag. That is
  `LedStackRenderer.setState`'s rule, generalised to a tree. Layer ids must be
  unique across the whole tree, or two layers share one instance —
  `CompositionEdits` mints fresh ids in-app, and `CompositionSerializer` re-mints
  on decode (first occurrence keeps the id), because a hand-edited file can carry
  anything, including duplicates and none at all.
- **Adding an effect is one file and one registry row.** A new
  `ReactiveEffect` with a `SPEC` plus a line in `ReactiveEffects.ALL`; the
  compositor, the editor (which builds its controls from the declared
  `EffectParam` schema), persistence and the registry-wide tests all follow.
  Never rename an existing `EffectSpec.id` — it is persisted inside saved stacks.
  **Parameters are clamped centrally**, in `ParamBag` against the declared
  schema, so no effect has to defend itself against an out-of-range value and a
  `Choice` index is always a legal option. NaN is handled explicitly: every
  comparison against it is false, so `coerceIn` would have passed it straight
  through into the render maths.
- **Saved compositions parse with a hand-written JSON reader**
  (`composer/Json.kt`), for the same reason `GenreLabels` does:
  `unitTests.isReturnDefaultValues = true` stubs every `org.json` call to return
  zeros, so a round-trip could not be tested through the platform library. It is
  a parser for untrusted input — an imported file arrives through the share sheet
  — so it caps nesting at 64 (both readers recurse, and a `StackOverflowError` is
  an `Error`, escaping every `catch (JsonException)` around it), rejects unknown
  escapes and malformed numbers rather than guessing, and refuses to *write* a
  non-finite number instead of emitting a document nothing can read back.

### Navigation

`scan` (start destination), `device/{address}`, and nine **addressless** config
routes under their own prefix: `config/led`, `config/motion`, `config/audio`,
`config/beatlight`, `config/composer`, `config/keyframes`, `config/behavior`,
`config/firmware`, `config/diagnostics`.

Only `device/{address}` carries the address, and only because it is the screen
you arrive at from a scan result — the one destination that has to say *which*
tail it was opened for. Everything else drives the singleton `DeviceRepository`,
which holds one connection at a time, so an address in those routes named a
device nothing downstream ever read: it looked like per-device scoping while
providing none. Real per-device scoping (a repository per address, ViewModels
scoped to it) is a different design and is deliberately not attempted. The
`config/` prefix exists so none of those routes can be matched against the
`device/{address}` pattern.

The composer (the layer/folder editor) is reached from the BeatLight screen's
"Edit" button rather than from the overview; the keyframe and behavior editors
hang off motion config, and firmware update and diagnostics off the overview.

## BLE protocol (v6)

`Protocol.SUPPORTED_PROTOCOL_VERSION` is the contract version this app targets;
the device reports its own as the **first byte** of the FF06 read. A mismatch is
surfaced as a banner on the overview screen, and it is load-bearing: v2 devices
compute the image CRC with a different polynomial, so uploads to one silently
fail with `BAD_STATE`.

Version history that still matters here: **v3** fixed the image CRC polynomial;
**v4** added the stall event, motor enable/disable, per-motor motion limits and
the FF06 motion block; **v5** added the FF09 sequence byte and readable last
result, the readable FF07 event ring, and `RESULT_BUSY`. **v6** is a bundled
break: it retired the vestigial PID (FF01 `0x04` now answers `UNKNOWN_CMD`, and
the FF06 servo record shrank from 16 bytes to 4) and framed the FF06 trailing
blocks with a `[tag][len]` prefix, so a v5 device is now *unsupported* rather
than parsed on a best-effort basis.

| UUID | Direction | Purpose |
|---|---|---|
| FF01 | write | Motion commands |
| FF02 | read + notify (~20 Hz) | Motion state, 97 bytes (`MOTION_STATE_SIZE`). 77 is the v5-era minimum a parse needs; the behavior block (+4) and the logical-position block (+16) were **appended**, never inserted, so `Protocol.MOTION_STATE_SIZE` is the floor and the longer sizes are named constants beside it |
| FF03 | write | LED commands |
| FF04 | read + notify | LED state |
| FF05 | write-no-response | FFT audio stream |
| FF06 | read/write | System info + capabilities. The read is a fixed preamble (4-byte servo record, no PID) then framed `[tag][len]` blocks, located by tag |
| FF07 | read + notify | Events, 16 decoded (`SystemEvent`, `0x01`-`0x10`): tap base/tip, config changed, stall, eight paired TMC2209 driver-health appear/clear events (overtemp warning, thermal shutdown, coil short, open load), three battery-policy crossings, behavior-state change. The read returns a recent-event ring (`[count][evt]...`) |
| FF08 | read/write | Profile slots (occupancy + names) |
| FF09 | read + notify | Command result (ACK/error) for every non-FF05/FF0A write |
| FF0A | write-no-response | Direct LED pixel stream (`DeviceRepository.streamDirectFrame`) |
| FF0B | write-no-response | Live motion targets (`DeviceRepository.streamMotionTargets`) |
| FF0C | read + notify (~1 Hz) | Diagnostics snapshot (`DiagnosticsParser`) |
| FF0D | read + notify | Parameter descriptors for one pattern or effect at a time — name/min/max/default/unit. Which entity it publishes is chosen by writing `SystemCommands.selectDescriptors` to FF06, which keeps every read a fixed single-packet payload. **Parsed (`ParamDescriptorParser`, `model/ParamModels`) and tested, but not yet consumed** — see below |
| FF0E | write-no-response + read/notify | OTA firmware image and its offset echo |

Alongside FF00 the app also reads two **standard SIG services**, deliberately not
tail-specific equivalents: Battery (`0x180F` / `0x2A19`, `BatteryLevelParser`) and
Device Information (`0x180A` — manufacturer, model, firmware and hardware
revision). A phone's own settings screen, a smartwatch or any generic BLE tool
already knows how to read a battery level from `0x2A19`, and none of them will
ever learn a private characteristic.

Things worth remembering when touching this layer:

- **FF0D is protocol-only so far.** The parser, the model types, the FF06
  selector command and the UUID all exist and are covered by
  `ParamDescriptorParserTest`, but nothing in `repository/`, `viewmodel/` or
  `ui/` reads them: the built-in `LedEffect`/`MotionPattern` tables are still the
  only source of parameter names and ranges the UI has. The forward-compatibility
  benefit — rendering usable controls for a pattern the app was not built to know
  — is not yet delivered. Wiring it is a repository flow plus a fallback in the
  parameter editors, not new wire work.

- **Removing an LED layer does not shift indices.** `LCMD_REMOVE_LAYER` stamps
  `effect_id = 0xFF` in place and leaves `num_layers` alone. Treat cleared slots
  as `LayerConfig.isEmpty`, never as removed list entries.
- **Every write is acknowledged on FF09.** Don't assume a write succeeded; a
  rejected command reports a `CommandResultCode` there. The payload carries a
  device-side sequence byte (v5) so two identical in-flight commands are
  distinguishable and a dropped notify shows up as a gap; the last result is
  also readable, because a notify is best-effort.
- **A stall latches every motor off.** `SYS_EVENT_STALL` on FF07 means
  StallGuard tripped and the firmware released the shared enable line. Nothing
  moves again until `MCMD_ENABLE_MOTORS`, a calibrate, or a pattern select. The
  FF06 motion block's `motors_enabled` byte mirrors the latch — but only v4+
  firmware publishes that block, so `SystemInfo.motorsStalled` is deliberately
  false when it is absent rather than treating "unknown" as "stalled".
- **PID is retired (v6).** The motors are open-loop steppers; `MCMD_SET_MOTION_LIMITS`
  (velocity/acceleration/jerk + StallGuard threshold) is what shapes motion. The
  `MCMD_SET_PID` command (`0x04`) now answers `UNKNOWN_CMD` and its id is never
  reused; the gains left `ServoConfig` and the FF06 servo record, which is a
  4-byte assignment record. There is no longer a legacy PID section in the UI.
- **Image uploads use BEGIN → chunks → FINALIZE.** BEGIN (`0x08`) arms a length
  and CRC check that FINALIZE verifies.
- **The image CRC is standard CRC-32 as of v3** (`java.util.zip.CRC32`).
  Firmware ≤ v2 used a non-standard `0xEDB88420` polynomial; `Crc32Test` asserts
  we no longer produce that variant.
- **FF06 read is framed (v6).** A fixed preamble (`protocol_version`, firmware
  version, the 4-byte servo records, the IMU records) then `[tag][len]` blocks to
  the end of the payload. `SystemInfoParser` finds each block by tag and skips an
  unknown one by its `len`, so **block order is not part of the contract** and a
  future block the app does not know is passed over, not fatal.
- **`servo_config_t` is a historical name.** The motors are TMC2209 steppers as
  of firmware `d4973bf`; the FF01/FF06 servo payloads were deliberately left
  unchanged, so nothing on this side needed to move.
- **Two streams take over from the device, and both must time out.** FF0A pixels
  suspend the compositor; FF0B targets suspend the motion pattern. The device
  ages both out (2 s for pixels, 500 ms for targets) and falls back to its own
  rendering, so a phone that walks away never leaves the tail holding a frame or
  a pose. `TailDirectLedOutput`'s 1 s keepalive must stay *below* the pixel
  timeout, or an idle-but-live stream is mistaken for an abandoned one.
- **FF05 carries a beat trailer.** Three bytes after the bins — phase, BPM,
  beat/downbeat/drop flags. The device has no microphone and no beat tracker, so
  this is the only way its own effects and motion patterns can know the beat.
  Additive: the firmware reads exactly `num_bins` of bin data and ignores the
  rest, so old and new interoperate in both directions.
- **FF0A packets pay for their own header.** `DirectPixelFrame.maxLedsPerPacket`
  computes `((mtu-3)-2)/3`, not the protocol doc's `(mtu-3)/3` — that
  approximation skips subtracting the 2-byte `start_index` header and
  overstates capacity by one LED at MTU 247/517. Direct mode is transient
  session state on both ends: FF03 `0x09` isn't persisted, and the firmware
  auto-reverts it if the app disconnects mid-stream, so `DeviceRepository`
  resets `directModeActive` in `onDisconnected` to match.
- **`Protocol.OTA_SLOT_BYTES` is a conservative upper bound, not the truth.** The
  two shipped partition tables define different slot sizes, so the constant is
  the largest any of them offers and is used only to reject a file that cannot fit
  *any* tail before spending a transfer on it. The device is the authority: an
  image between the smallest slot and this bound passes the local check and may
  still be refused, which costs one packet and is reported — whereas the reverse
  mistake costs an owner of the larger board the ability to update at all.

## Testing

JVM unit tests live in `app/src/test/` (103 files). There are no instrumented
tests, and the suite needs no device, no emulator and no NDK — nothing under test
touches the native library. `.github/workflows/ci.yml` runs
`testDebugUnitTest` and `lintDebug` on every push to `main` and every PR, for
exactly that reason: it costs a couple of minutes and there is no reason for it
to only ever run on one laptop.

- `testutil/FirmwarePayloads` builds the exact byte payloads the firmware
  publishes (mirroring `app_bridge.cpp::app_update_ble_state`). Wire-format
  changes should be made there first — the parser tests then fail loudly.
- `testutil/FakeBleTransport` records traffic and replays canned reads.
- **`ble/VirtualTailTransport` is a whole simulated device**, not a stub: it
  answers the real wire protocol in-process, so every screen can be exercised
  with no radio and it ships in the app as the "Virtual tail (testing)" scan
  entry. `RoutingBleTransport` chooses it or the real stack per connect, by
  address. What keeps it honest is `VirtualTailConformanceTest`, which replays
  the firmware's own exported conformance vectors (`testdata/`) through it — the
  QA-5 answer to the standing two-repository drift problem, since a simulator
  that agrees only with itself is worse than no simulator.
- `testutil/BeatReference.kt` parses the madmom/BeatNet reference dumps by hand
  (same reason `Json.kt` exists); `testutil/FakeSharedPreferences.kt` stands in
  for the platform store so persistence round-trips are testable at all.
- **`runTest` gotcha:** `advanceUntilIdle()` stops as soon as no *foreground*
  work remains, so coroutines launched in `backgroundScope` are never dispatched.
  `DeviceRepositoryTest` gives the repository its own
  `CoroutineScope(StandardTestDispatcher(testScheduler))` and cancels it in
  `@After`.
- `unitTests.isReturnDefaultValues = true` is set so `android.util.Log` calls in
  tested classes don't throw.
- `testutil/SyntheticAudio` generates click tracks, sines, noise and energy steps;
  `testutil/PlaybackAudioSource` replays a buffer as an `AudioSource`;
  `testutil/RecordingLightingOutput` records everything the renderer emits. Between
  them the whole BeatLight pipeline runs offline, with no mic and no device.
- `composer/ComposerTestSupport` builds a `ReactiveContext` with everything
  defaulted to "nothing is happening", so an effect test states only the inputs it
  cares about. Effects read the context and nothing else, so a hand-built one is a
  complete stand-in for the whole analysis pipeline.
- **The DSP suites assert numbers, not shapes.** Tempo within ±2 BPM, beats within
  ±70 ms of the true grid, LED colours equal to the firmware's integer arithmetic.
  A change that makes one of those merely "close" has broken something.
  `CompositionRendererTest` holds the same line for blending: every expected pixel
  is worked out by hand from `ColorMath`, never recorded from a run.
- **Registry-wide tests mean a new effect is covered the day it lands.**
  `ReactiveEffectsTest` renders *every* registered effect against silence, a loud
  downbeat and a predicted-but-unarrived beat, and asserts no throwing, no
  out-of-range channel, determinism, and that modulators emit neutral grey.
  `CompositionLibraryTest` validates the built-in stacks as data — every parameter
  key exists in its effect's schema and every value is inside its declared range,
  because a typo'd key would otherwise silently fall back to the default.
- **Anything driven by a clock takes the clock as a parameter.** The renderer, the
  audio level source and the engine all do; that is what makes their behaviour
  assertable rather than timing-dependent.
