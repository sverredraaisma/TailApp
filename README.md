# TailApp

The Android companion app for the **Tail** — a wearable animatronic tail driven by
[TailFirmware](#the-firmware) over Bluetooth Low Energy. TailApp scans for the tail,
configures every subsystem, and turns the phone's microphone into a live source of
**beat‑reactive lighting and motion**: it analyses the music, tracks the beat, and
streams both the lights and the movement to the device in time with what it hears.

This README is a build‑and‑run‑it‑yourself guide: requirements, building, flashing
the app onto a phone, and connecting to a device.

---

## Table of Contents

- [What it does](#what-it-does)
- [Requirements](#requirements)
- [Building](#building)
- [Installing on a phone](#installing-on-a-phone)
  - [With Gradle (simplest)](#with-gradle-simplest)
  - [With ADB and a prebuilt APK](#with-adb-and-a-prebuilt-apk)
  - [From Android Studio](#from-android-studio)
- [Permissions](#permissions)
- [Using the app](#using-the-app)
- [Testing without a device](#testing-without-a-device)
- [Project structure](#project-structure)
- [Running the tests](#running-the-tests)
- [The firmware](#the-firmware)
- [Documentation index](#documentation-index)
- [License](#license)

---

## What it does

- **Find & connect.** Scans for BLE devices, prioritising the tail's advertised
  name **`Tail controller`**, and connects.
- **Device overview.** Subsystem status, the active motion pattern, live servo
  positions, the LED layer stack, battery, and a firmware‑version banner.
- **LED config.** Edit the device's own layer stack — effects, blend modes,
  per‑layer opacity, image upload — with a live, firmware‑accurate preview.
- **Motion config.** Pattern selection and parameters, per‑motor limits, a manual
  "drive pad" to steer the tail by hand, a keyframe‑sequence editor, and a
  behavior‑engine (mood state machine) editor.
- **BeatLight.** Analyses the microphone — beat, tempo, drops, genre — and drives
  the LEDs (and optionally the motors) in time with the music, streaming the beat
  to the device so its *own* effects can react too.
- **Effect composer.** A layered, folder‑nested graph of reactive effects with a
  live preview, which can be **installed onto the device's own layer stack** so a
  look survives the phone walking away.
- **System.** Firmware update (OTA) with progress and rollback awareness, a
  diagnostics screen, device rename, and bond management.

The app targets **protocol v6**; a device on an older protocol is surfaced as an
unsupported‑version banner rather than mis‑driven.

---

## Requirements

| | |
|---|---|
| **Phone** | Android **8.0+** (API 26). BLE required; a microphone is needed for BeatLight. |
| **JDK** | 17 (for the Gradle build) |
| **Android SDK** | compileSdk/targetSdk 34; platform‑tools (`adb`) for installing |
| **Build tools** | Gradle 9.3 · AGP 8.13.2 · Kotlin 1.9.22 · Compose BOM 2024.02.00 |
| **NDK/CMake** | Downloaded automatically on first build — the app has a small native audio‑capture layer (Oboe). |

The first build downloads the NDK and CMake and takes several minutes; later
builds are fast. The JVM **unit tests need none of the native toolchain**.

---

## Building

From the repo root (`gradlew.bat` on Windows, `./gradlew` on macOS/Linux):

```bash
gradlew.bat assembleDebug        # build the debug APK
gradlew.bat lintDebug            # Android lint (lint errors fail the build)
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions runs the unit suite and lint on every push to `main` and every
pull request ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) — neither
needs a device, an emulator or the NDK, so there is no reason for them to only
ever run locally. Lint errors fail the build in both places.

---

## Installing on a phone

First, **enable Developer Options + USB debugging** on the phone (Settings →
About phone → tap *Build number* 7×, then Settings → System → Developer options →
*USB debugging*), plug it in over USB, and accept the "Allow USB debugging"
prompt. Confirm it's visible:

```bash
adb devices        # should list your device as "device", not "unauthorized"
```

### With Gradle (simplest)

Builds and installs in one step to every connected device:

```bash
gradlew.bat installDebug
```

### With ADB and a prebuilt APK

If you already built the APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`-r` reinstalls over an existing copy, keeping its data.

### From Android Studio

Open the project, pick the connected device in the toolbar, and press **Run**
(▶). Studio handles the build‑and‑install.

After installing, launch **TailApp** from the app drawer.

---

## Permissions

The app requests these at runtime; grant them when prompted:

| Permission | Why | When |
|------------|-----|------|
| `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` | Find and talk to the tail | Android 12+ (API 31+) |
| `ACCESS_FINE_LOCATION` | BLE scanning on older Android | Android 8–11 (API 26–30) only |
| `RECORD_AUDIO` | Microphone analysis for BeatLight | On opening Audio config / BeatLight |
| `POST_NOTIFICATIONS` | The ongoing notification a foreground service must post | Android 13+ (API 33+), when a session starts |
| `FOREGROUND_SERVICE` (+ microphone) | Keep BeatLight running in the background | While a session runs |

`BLUETOOTH_SCAN` is declared with `neverForLocation`, so the app does **not** ask
for location on Android 12 and up. That assertion is honest — nothing here
derives a location from a scan result; devices are matched on service UUID and
address. The legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` and both location permissions
are capped at `maxSdkVersion="30"` so they are never requested on a modern phone.

Backup is off (`allowBackup="false"` plus a `dataExtractionRules` file): saved
effect stacks and paired‑device state are local session data, and restoring them
onto a different handset would resurrect devices you no longer own.

If a permission is denied the relevant screen explains what it needs; nothing
else is blocked.

---

## Using the app

1. **Pair the tail first.** The device's characteristics need a bonded
   (encrypted) link, and the firmware only opens its pairing window on a **fresh
   power‑on or a physical tap**. Power‑cycle or tap the tail, open TailApp, and
   let the OS pair when prompted.
2. **Scan & connect.** The scan screen lists nearby devices, `Tail controller`
   first. Tap it to connect.
3. **Configure.** From the device overview, open **LED**, **Motion**, **Audio**,
   or **BeatLight**. The composer is reached from BeatLight's *Edit* button.
4. **Go reactive.** In BeatLight, start a session to drive the tail from the
   music. In the composer, build a look and *Install* it to the device to keep it
   running standalone.

No physical device? See below.

---

## Testing without a device

The app ships a **virtual tail** — an in‑app BLE simulator that answers the real
wire protocol. It appears in the scan list as **"Virtual tail (testing)"**;
connect to it to exercise every screen with no hardware. Its behaviour is checked
against the firmware's own conformance vectors, so it stays honest to the real
device.

---

## Project structure

```
com.tailapp/
  ble/            BleTransport, connection manager, scanner, the virtual tail,
                  protocol/ (wire format)
  repository/     DeviceRepository — single source of truth for device state —
                  plus ACK tracking, OTA and sequence upload
  model/          DeviceState, LED/Motion/System models
  audio/          Mic capture (Oboe + AudioRecord), FFT, feature extraction
  beat/ drop/ genre/   Beat, drop and genre analysis tiers
  effects/        LightingEngine + BeatLight session/service
  composer/       The reactive effect graph and its 25 effects
  led/            Firmware‑parity LED render port (drives the live preview)
  lighting/       LightingOutput implementations (FF0A / preview)
  viewmodel/      One per screen
  ui/             Compose theme, screens, components
  di/AppContainer Manual dependency injection
app/src/main/cpp/ Native Oboe capture + lock‑free ring buffer (JNI)
docs/             See the documentation index below
```

MVVM with manual DI (no Hilt/Dagger); state flows from `DeviceRepository` into
Compose screens. See [`docs/composer.md`](docs/composer.md) and
[`docs/beatlight.md`](docs/beatlight.md) for the two halves of the reactive
lighting feature.

---

## Running the tests

JVM unit tests only — no device, no native toolchain:

```bash
gradlew.bat testDebugUnitTest
```

The suite runs the whole BeatLight pipeline offline (synthetic audio in,
recorded output asserted), the LED render port against the firmware's exact
integer arithmetic, and a conformance‑vector replay that checks the virtual tail
agrees with the firmware byte‑for‑byte.

---

## The firmware

The device firmware is a separate repository, **TailFirmware**. It is the
authority for the BLE wire format; `docs/ble-protocol.md` there is the
definitive reference and `docs/app-integration.md` is the app‑developer guide.
See its own `README.md` to build the hardware and flash a board.

---

## Documentation index

| Doc | What it covers |
|-----|----------------|
| [`docs/AppSpecifications.md`](docs/AppSpecifications.md) | The feature spec |
| [`docs/composer.md`](docs/composer.md) | The reactive effect graph (rendering half) |
| [`docs/beatlight.md`](docs/beatlight.md) | The audio analysis pipeline |
| [`docs/beat-model.md`](docs/beat-model.md), [`docs/genre-model.md`](docs/genre-model.md) | The beat and genre models |
| [`docs/beatlight-manual-checks.md`](docs/beatlight-manual-checks.md) | The checks that need a phone and a tail — what the JVM suite cannot reach |
| [`docs/roadmap.md`](docs/roadmap.md) | Delivery status, aligned with the firmware |
| [`docs/DevelopmentPlan.md`](docs/DevelopmentPlan.md) | The original twelve‑phase build plan; historical record, superseded by the roadmap |
| [`docs/codebase-audit-2026-07-28.md`](docs/codebase-audit-2026-07-28.md) | The 2026‑07‑28 audit record — what was found, kept as written |
| `CLAUDE.md` | Architecture notes and conventions |

---

## License

**GNU Affero General Public License v3.0** — the full text is in
[`LICENSE`](LICENSE).

The genre and beat models are *not* covered by it and are not in this repository:
Discogs‑EffNet and `genre_discogs400` are CC BY‑NC‑ND, BeatNet is CC BY 4.0. The
app ships the code that runs them; `tools/` downloads and converts the weights
onto your own device. See [`docs/genre-model.md`](docs/genre-model.md) and
[`docs/beat-model.md`](docs/beat-model.md).
