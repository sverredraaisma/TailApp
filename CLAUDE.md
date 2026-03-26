# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

TailApp is an Android application (Kotlin, Jetpack Compose) that communicates with Bluetooth Low Energy (BLE) devices to control their settings and features.

## Build & Run

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew installDebug            # Build and install on connected device/emulator
./gradlew assembleRelease         # Build release APK
./gradlew clean                   # Clean build outputs
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

## Version Matrix

- Gradle 8.4, AGP 8.2.2, Kotlin 1.9.22
- Compose BOM 2024.02.00, Compose Compiler 1.5.10
- compileSdk/targetSdk 34, minSdk 26
- JVM target: 17

## Architecture

**MVVM with manual DI** — no Hilt/Dagger. Dependencies are created in `AppContainer` (instantiated by `TailApp` Application class) and accessed via `(application as TailApp).container`.

### Layer structure

```
com.tailapp/
├── ble/                  # BLE layer: scanner, connection manager, state enums
│   ├── BleScanner        # Wraps BluetoothLeScanner, exposes StateFlow<List<BleDevice>>
│   ├── BleConnectionManager  # Single GATT connection: connect/disconnect/read/write/notify
│   └── ConnectionState   # DISCONNECTED | CONNECTING | CONNECTED
├── model/                # Data classes (BleDevice)
├── viewmodel/            # ScanViewModel, DeviceViewModel
├── navigation/           # NavRoutes (sealed class), TailAppNavHost (NavHost composable)
├── ui/
│   ├── theme/            # Material3 theme, colors, typography
│   ├── screen/           # ScanScreen, DeviceScreen (full-screen composables)
│   └── components/       # DeviceListItem, CharacteristicCard (reusable composables)
├── di/AppContainer       # Manual DI container
├── TailApp               # Application subclass
└── MainActivity          # Single Activity entry point
```

### Key patterns

- **BLE operations are mutex-guarded** — Android BLE only supports one GATT operation at a time. `BleConnectionManager` uses a coroutine `Mutex` to serialize reads/writes.
- **StateFlow everywhere** — BLE scanner and connection manager expose `StateFlow` properties collected in Compose via `collectAsStateWithLifecycle()`.
- **Characteristic updates via SharedFlow** — `BleConnectionManager.characteristicUpdate` emits `CharacteristicUpdate(uuid, value)` for both reads and notifications.
- **Runtime permissions** — `ScanScreen` requests `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` on API 31+ and `ACCESS_FINE_LOCATION` on API 26-30.

### Navigation

Two screens: `scan` (start destination) and `device/{address}`. Device address is passed as a navigation argument.
