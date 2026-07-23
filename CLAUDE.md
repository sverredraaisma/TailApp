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
│   └── protocol/           # Wire format: command builders, parsers, UUIDs, CRC
├── repository/DeviceRepository  # Single source of truth for device state
├── model/                  # DeviceState, MotionState, LedState, SystemInfo, Capabilities
├── audio/                  # Capture (Oboe + AudioRecord), FFT, feature extraction, dsp/
├── beat/                   # Beat/downbeat/tempo tracking (BeatLight)
├── drop/                   # Drop detection + section state (BeatLight)
├── genre/                  # Genre classification (BeatLight)
├── effects/                # EffectProfile, controller, renderer, LightingEngine
├── lighting/               # LightingOutput + FF0A/preview implementations
├── led/                    # Kotlin port of the firmware LED render engine
├── viewmodel/              # Scan, DeviceOverview, MotionConfig, LedConfig, AudioConfig, BeatLight
├── navigation/             # NavRoutes (sealed class), TailAppNavHost
├── ui/{theme,screen,components}
├── di/AppContainer
├── TailApp                 # Application subclass
└── MainActivity            # Single Activity entry point

app/src/main/cpp/           # Oboe capture + lock-free ring buffer (JNI)
tools/                      # Python: model download/export, reference data
```

**BeatLight** — the beat/drop/genre-reactive lighting feature — spans
`audio` → `beat`/`drop`/`genre` → `effects` → `lighting`, and renders through the
firmware mirror in `led`. `docs/beatlight.md` is its map; read it before touching
any of those packages.

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
  `AudioConfigScreen` and `BeatLightScreen` request `RECORD_AUDIO`.
- **The LED engine is a firmware port, not a lookalike.** `com.tailapp.led`
  mirrors TailFirmware's `led/` file for file, C++ integer truncation included,
  because both the live preview and the direct-mode renderer have to produce the
  pixels the device would. A divergence there is a preview that quietly lies.
- **Analysis never runs on the main thread, and never twice at once.**
  `LightingEngine` takes its dispatcher as a parameter for exactly this reason —
  its analysis and render loops sharing a `FeatureExtractor` across threads
  corrupts the extractor's ring buffer.

### Navigation

`scan` (start destination), `device/{address}`, and the config screens
`device/{address}/{motion,led,audio,beatlight}`. The device address is a nav
argument.

## BLE protocol (v3)

`Protocol.SUPPORTED_PROTOCOL_VERSION` is the contract version this app targets;
the device reports its own as the **first byte** of the FF06 read. A mismatch is
surfaced as a banner on the overview screen, and it is load-bearing: v2 devices
compute the image CRC with a different polynomial, so uploads to one silently
fail with `BAD_STATE`.

| UUID | Direction | Purpose |
|---|---|---|
| FF01 | write | Motion commands |
| FF02 | read + notify (~20 Hz) | Motion state, 77 bytes |
| FF03 | write | LED commands |
| FF04 | read + notify | LED state |
| FF05 | write-no-response | FFT audio stream |
| FF06 | read/write | System info + capabilities |
| FF07 | read + notify | Events: tap base/tip, config changed |
| FF08 | read/write | Profile slots (occupancy + names) |
| FF09 | read + notify | Command result (ACK/error) for every non-FF05/FF0A write |
| FF0A | write-no-response | Direct LED pixel stream (`DeviceRepository.streamDirectFrame`) |

Things worth remembering when touching this layer:

- **Removing an LED layer does not shift indices.** `LCMD_REMOVE_LAYER` stamps
  `effect_id = 0xFF` in place and leaves `num_layers` alone. Treat cleared slots
  as `LayerConfig.isEmpty`, never as removed list entries.
- **Every write is acknowledged on FF09.** Don't assume a write succeeded; a
  rejected command reports a `CommandResultCode` there.
- **Image uploads use BEGIN → chunks → FINALIZE.** BEGIN (`0x08`) arms a length
  and CRC check that FINALIZE verifies.
- **The image CRC is standard CRC-32 as of v3** (`java.util.zip.CRC32`).
  Firmware ≤ v2 used a non-standard `0xEDB88420` polynomial; `Crc32Test` asserts
  we no longer produce that variant.
- **FF06 read offsets** are all shifted one byte by the leading
  `protocol_version`, and the capability block is appended after the IMU block.
- **`servo_config_t` is a historical name.** The motors are TMC2209 steppers as
  of firmware `d4973bf`; the FF01/FF06 servo payloads were deliberately left
  unchanged, so nothing on this side needed to move.
- **FF0A packets pay for their own header.** `DirectPixelFrame.maxLedsPerPacket`
  computes `((mtu-3)-2)/3`, not the protocol doc's `(mtu-3)/3` — that
  approximation skips subtracting the 2-byte `start_index` header and
  overstates capacity by one LED at MTU 247/517. Direct mode is transient
  session state on both ends: FF03 `0x09` isn't persisted, and the firmware
  auto-reverts it if the app disconnects mid-stream, so `DeviceRepository`
  resets `directModeActive` in `onDisconnected` to match.

## Testing

JVM unit tests live in `app/src/test/`. There are no instrumented tests.

- `testutil/FirmwarePayloads` builds the exact byte payloads the firmware
  publishes (mirroring `app_bridge.cpp::app_update_ble_state`). Wire-format
  changes should be made there first — the parser tests then fail loudly.
- `testutil/FakeBleTransport` records traffic and replays canned reads.
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
- **The DSP suites assert numbers, not shapes.** Tempo within ±2 BPM, beats within
  ±70 ms of the true grid, LED colours equal to the firmware's integer arithmetic.
  A change that makes one of those merely "close" has broken something.
- **Anything driven by a clock takes the clock as a parameter.** The renderer, the
  audio level source and the engine all do; that is what makes their behaviour
  assertable rather than timing-dependent.
