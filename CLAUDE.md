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
├── audio/                  # Mic capture, FFT, foreground streaming service
├── viewmodel/              # Scan, DeviceOverview, MotionConfig, LedConfig, AudioConfig
├── navigation/             # NavRoutes (sealed class), TailAppNavHost
├── ui/{theme,screen,components}
├── di/AppContainer
├── TailApp                 # Application subclass
└── MainActivity            # Single Activity entry point
```

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
  `AudioConfigScreen` requests `RECORD_AUDIO`.

### Navigation

`scan` (start destination), `device/{address}`, and the three config screens
`device/{address}/{motion,led,audio}`. The device address is a nav argument.

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
| FF0A | write-no-response | Direct LED pixel stream — **not yet implemented app-side** |

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
