# TailApp Development Plan

> **Status:** Planning
> **Last updated:** 2026-03-30

## Table of Contents

1. [Current State](#1-current-state)
2. [Architecture Design](#2-architecture-design)
3. [Development Phases](#3-development-phases)
4. [Phase Details](#4-phase-details)
5. [Architecture Review & Design Fixes](#5-architecture-review--design-fixes)

---

## 1. Current State

The project has a minimal skeleton:
- Gradle build with Compose, Navigation, Lifecycle dependencies
- `TailApp` Application class with manual DI via `AppContainer`
- No BLE code, no screens, no ViewModels implemented yet

The firmware is feature-complete with 8 BLE GATT characteristics (FF01-FF08) using a binary command protocol. All data is little-endian IEEE 754 floats. The app must maintain a local model of all effect/pattern names and parameters since the device does not broadcast these.

---

## 2. Architecture Design

### High-Level Architecture

```
com.tailapp/
├── ble/
│   ├── BleScanner                    # Scans for devices, prioritizes "Tail controller"
│   ├── BleConnectionManager          # GATT connection lifecycle, MTU negotiation
│   ├── ConnectionState               # DISCONNECTED | CONNECTING | CONNECTED
│   └── protocol/
│       ├── TailProtocol              # Encodes commands into byte arrays, decodes state
│       ├── CharacteristicUuids       # All UUID constants (FF00-FF08)
│       ├── MotionCommands            # Command builders for FF01
│       ├── LedCommands               # Command builders for FF03
│       ├── SystemCommands            # Command builders for FF06
│       ├── ProfileCommands           # Command builders for FF08
│       ├── MotionStateParser         # Decodes FF02 (77 bytes) into MotionState
│       ├── LedStateParser            # Decodes FF04 (header + layers) into LedState
│       └── SystemInfoParser          # Decodes FF06 read into SystemInfo
├── audio/
│   ├── AudioCaptureManager           # Microphone capture via AudioRecord
│   ├── FftProcessor                  # FFT computation on PCM data
│   └── FftStreamManager             # Orchestrates capture → FFT → BLE stream at 30fps
├── model/
│   ├── BleDevice                     # Scan result data class
│   ├── MotionState                   # Decoded FF02 state
│   ├── LedState                      # Decoded FF04 state
│   ├── SystemInfo                    # Decoded FF06 system info
│   ├── MotionPattern                 # Enum: Static, Wagging, Loose (with param metadata)
│   ├── LedEffect                     # Enum: Rainbow, StaticColor, Image, AudioPower, AudioBar, AudioFreqBars
│   ├── BlendMode                     # Enum: Multiply, Add, Subtract, Min, Max, Overwrite
│   ├── LayerConfig                   # Per-layer state: effect, blend, enabled, transforms, params
│   ├── ServoConfig                   # Axis, half, invert, PID gains
│   └── DeviceState                   # Aggregated: MotionState + LedState + SystemInfo + ConnectionState
├── di/
│   └── AppContainer                  # Manual DI: creates BLE, audio, and repository instances
├── repository/
│   └── DeviceRepository              # Single source of truth for device state
│                                     # Coordinates BLE writes and state updates
│                                     # Exposes StateFlows for all device state
├── viewmodel/
│   ├── ScanViewModel                 # Scan results, connect action
│   ├── DeviceOverviewViewModel       # Device status, FFT toggle, navigation
│   ├── LedConfigViewModel            # Layer management, effect params, image upload
│   ├── MotionConfigViewModel         # Pattern selection, servo config, calibration
│   └── AudioConfigViewModel          # FFT settings: bins, normalization, freq range
├── navigation/
│   ├── NavRoutes                     # Sealed class with all routes
│   └── TailAppNavHost                # NavHost composable
├── ui/
│   ├── theme/                        # Material3 theme
│   ├── screen/
│   │   ├── ScanScreen                # Device list with prioritized "Tail controller"
│   │   ├── DeviceOverviewScreen      # Status dashboard with sub-screen navigation
│   │   ├── LedConfigScreen           # Layer list, effect parameters, image upload
│   │   ├── MotionConfigScreen        # Pattern picker, servo settings, calibration
│   │   └── AudioConfigScreen         # FFT bin count, normalization, frequency range
│   └── components/
│       ├── DeviceListItem            # Scan result row
│       ├── SubsystemStatusCard       # Status indicator for servos, BLE, LEDs, IMU, I2C
│       ├── LayerCard                 # Single layer with effect, blend, params
│       ├── EffectParameterSlider     # Float parameter with label, range, unit
│       ├── ServoConfigCard           # Axis/half/invert/PID per servo
│       ├── PatternParameterControls  # Dynamic param UI based on active pattern
│       └── ImagePickerButton         # Image selection + upload progress
├── TailApp                           # Application subclass
└── MainActivity                      # Single Activity entry point
```

### Key Architectural Decisions

1. **DeviceRepository as coordination layer** — All BLE reads/writes and device state go through `DeviceRepository`. ViewModels never touch `BleConnectionManager` directly. This prevents race conditions from multiple ViewModels issuing concurrent BLE writes and provides a single StateFlow source for all UI.

2. **Protocol layer for binary encoding/decoding** — A dedicated `protocol/` package handles all byte-level encoding (command building) and decoding (state parsing). This isolates binary format knowledge from the rest of the app and makes protocol changes contained.

3. **App-side effect/pattern metadata** — Per the firmware spec, effect names, parameter names, ranges, and defaults are NOT broadcast by the device. The app defines these as enums with metadata (parameter count, names, ranges, defaults). The UI reads this metadata to dynamically build parameter controls.

4. **FFT audio pipeline** — Audio capture, FFT, and BLE streaming are separated into three components. `FftStreamManager` orchestrates them and runs on a background coroutine, writing to FF05 at 30fps using Write Without Response.

5. **Mutex-guarded BLE writes** — Android BLE supports only one GATT operation at a time. `BleConnectionManager` serializes all reads/writes through a coroutine `Mutex`.

### Navigation Graph

```
ScanScreen (start)
    │
    └── DeviceOverviewScreen (/{address})
            ├── LedConfigScreen (/{address}/led)
            ├── MotionConfigScreen (/{address}/motion)
            └── AudioConfigScreen (/{address}/audio)
```

---

## 3. Development Phases

| Phase | Name | Dependencies |
|-------|------|-------------|
| **1** | BLE Foundation | None |
| **2** | Binary Protocol Layer | Phase 1 |
| **3** | Device State Management | Phase 2 |
| **4** | Scan Screen | Phase 1 |
| **5** | Device Overview Screen | Phase 3, 4 |
| **6** | Motion Config Screen | Phase 3 |
| **7** | LED Config Screen | Phase 3 |
| **8** | Image Upload | Phase 7 |
| **9** | Audio Capture & FFT Streaming | Phase 3 |
| **10** | Audio Config Screen | Phase 9 |
| **11** | Profile Management | Phase 3 |
| **12** | Polish & Edge Cases | All above |

### Dependency Graph

```
Phase 1 (BLE Foundation)
  ├── Phase 2 (Protocol Layer)
  │     └── Phase 3 (State Management)
  │           ├── Phase 5 (Device Overview) ← also needs Phase 4
  │           ├── Phase 6 (Motion Config)
  │           ├── Phase 7 (LED Config)
  │           │     └── Phase 8 (Image Upload)
  │           ├── Phase 9 (Audio/FFT)
  │           │     └── Phase 10 (Audio Config)
  │           └── Phase 11 (Profiles)
  │
  └── Phase 4 (Scan Screen)

Phase 12 (Polish) ← all above
```

---

## 4. Phase Details

### Phase 1: BLE Foundation

**Goal:** Scanning, connecting, MTU negotiation, characteristic discovery, read/write/notify primitives.

**Tasks:**

1. `CharacteristicUuids` — Define all UUIDs as constants
   - Service UUID: `0000FF00-0000-1000-8000-00805F9B34FB`
   - Characteristics FF01-FF08 with full UUIDs

2. `BleScanner`
   - Wraps `BluetoothLeScanner`
   - Exposes `StateFlow<List<BleDevice>>` of discovered devices
   - Filters/prioritizes devices named "Tail controller"
   - Filters by service UUID `0000FF00` for reliable discovery
   - Handles API 31+ vs API 26-30 permission differences

3. `BleConnectionManager`
   - `connect(address)` / `disconnect()` — full GATT lifecycle
   - MTU negotiation to 256 bytes on connect
   - Service/characteristic discovery
   - `writeCharacteristic(uuid, data)` — mutex-guarded, supports both Write and Write No Response
   - `readCharacteristic(uuid)` — mutex-guarded
   - `enableNotifications(uuid)` — enables CCCD for notify characteristics
   - Exposes `StateFlow<ConnectionState>` (DISCONNECTED, CONNECTING, CONNECTED)
   - Exposes `SharedFlow<CharacteristicUpdate>` for notification data
   - Bonding support (Just Works, LE Secure Connections)
   - Reconnection: auto-resume advertising detection and prompt user

4. `ConnectionState` enum
   - DISCONNECTED, CONNECTING, CONNECTED

5. Runtime permissions
   - `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` (API 31+)
   - `ACCESS_FINE_LOCATION` (API 26-30)
   - `RECORD_AUDIO` (needed later for FFT, but request early)

**Deliverables:** Can scan, connect, read, write, and receive notifications from the Tail controller.

---

### Phase 2: Binary Protocol Layer

**Goal:** Encode all commands into byte arrays, decode all state notifications into Kotlin data classes.

**Tasks:**

1. **Model enums with metadata**

   `MotionPattern` enum:
   ```
   Static(0x00)  — params: [x1Pos, x2Pos, y1Pos, y2Pos]
   Wagging(0x01) — params: [frequency, xAmplitude, y1Pos, y2Pos]
   Loose(0x02)   — params: [damping, reactivity]
   ```
   Each param has: name, default, min, max, unit.

   `LedEffect` enum:
   ```
   Rainbow(0x00)       — params: [direction, speed, scale]
   StaticColor(0x01)   — params: [red, green, blue]
   Image(0x02)         — params: [orientation]
   AudioPower(0x03)    — params: [red, green, blue, fadeRate]
   AudioBar(0x04)      — params: [red, green, blue, direction, fadeRate]
   AudioFreqBars(0x05) — params: [numBars, red, green, blue, fadeRate, orientation]
   ```

   `BlendMode` enum: Multiply(0), Add(1), Subtract(2), Min(3), Max(4), Overwrite(5)

2. **Command builders (FF01 Motion Commands)**
   - `selectPattern(patternId: Byte): ByteArray` → `[0x01, patternId]`
   - `setPatternParam(paramId: Byte, value: Float): ByteArray` → `[0x02, paramId, f32LE]`
   - `setServoConfig(servoId, axis, half, invert): ByteArray` → `[0x03, ...]`
   - `setPidGains(servoId, kp, ki, kd): ByteArray` → `[0x04, servoId, f32, f32, f32]`
   - `calibrateZero(): ByteArray` → `[0x05]`
   - `setAxisLimits(axis, min, max): ByteArray` → `[0x06, axis, f32, f32]`
   - `setImuTap(imuId, enabled): ByteArray` → `[0x07, imuId, enabled]`

3. **Command builders (FF03 LED Commands)**
   - `setLayerEffect(layer, effectId, blendMode): ByteArray` → `[0x01, ...]`
   - `setEffectParam(layer, paramId, value): ByteArray` → `[0x02, layer, paramId, f32]`
   - `removeLayer(layer): ByteArray` → `[0x03, layer]`
   - `setLayerTransform(layer, flipX, flipY, mirrorX, mirrorY): ByteArray` → `[0x04, ...]`
   - `uploadImageChunk(offset: UShort, data: ByteArray): ByteArray` → `[0x05, u16LE, data...]`
   - `finalizeImage(width, height, layer): ByteArray` → `[0x06, w, h, layer]`
   - `setLayerEnabled(layer, enabled): ByteArray` → `[0x07, layer, enabled]`

4. **Command builders (FF06 System Commands)**
   - `setLedMatrix(numRings, ledsPerRing): ByteArray` → `[0x01, numRings, ...counts]`

5. **Command builders (FF08 Profile Commands)**
   - `saveProfile(slot): ByteArray` → `[0x01, slot]`
   - `loadProfile(slot): ByteArray` → `[0x02, slot]`
   - `listProfiles(): ByteArray` → `[0x03]`
   - `deleteProfile(slot): ByteArray` → `[0x04, slot]`

6. **State parsers**

   `MotionStateParser` — decodes FF02 (77 bytes):
   ```
   MotionState(
       activePatternId, params[0..7], encoderPositions[0..3],
       gravityX/Y/Z, xAxisLimits, yAxisLimits
   )
   ```

   `LedStateParser` — decodes FF04 (variable length):
   ```
   LedState(
       numRings, ledsPerRing[], layers[]: LayerConfig(
           effectId, blendMode, enabled, flipX/Y, mirrorX/Y, params[0..7]
       )
   )
   ```

   `SystemInfoParser` — decodes FF06 read:
   ```
   SystemInfo(
       firmwareVersion, servos[]: ServoConfig(axis, half, invert, muxChannel, pid),
       imus[]: ImuConfig(muxChannel, tapEnabled)
   )
   ```

7. **FFT frame builder**
   - `buildFftFrame(loudness: Byte, bins: ByteArray): ByteArray` → `[loudness, numBins, bins...]`
   - No command_id prefix (raw data for FF05)

**Deliverables:** Complete encode/decode layer covering all 8 characteristics. All binary formats tested with known byte sequences from the firmware docs.

---

### Phase 3: Device State Management

**Goal:** `DeviceRepository` that coordinates BLE communication and exposes unified device state as StateFlows.

**Tasks:**

1. **`DeviceState` aggregate model**
   ```kotlin
   data class DeviceState(
       val connectionState: ConnectionState,
       val systemInfo: SystemInfo?,
       val motionState: MotionState?,
       val ledState: LedState?,
       val fftStreamActive: Boolean
   )
   ```

2. **`DeviceRepository`**
   - On connect: request MTU 256, discover services, enable notifications on FF02/FF04/FF07, read FF02/FF04/FF06 for initial state sync
   - Subscribes to `BleConnectionManager.characteristicUpdate` and routes updates through the appropriate parser
   - Exposes `StateFlow<DeviceState>` combining all state
   - Provides suspend functions for every command:
     - `selectPattern(patternId)` — writes to FF01 and updates local state
     - `setPatternParam(paramId, value)` — writes to FF01 and updates local state
     - `setLayerEffect(layer, effectId, blendMode)` — writes to FF03 and updates local state
     - `setEffectParam(layer, paramId, value)` — writes to FF03 and updates local state
     - ... (all other commands)
   - **Optimistic local state updates**: since the device silently ignores invalid commands and notifications arrive at 1Hz, the repository updates local state immediately after a successful write. Notification data reconciles/overwrites.
   - On disconnect: clears device state, stops FFT stream

3. **`AppContainer` updates**
   - Instantiate `BleScanner`, `BleConnectionManager`, `DeviceRepository`
   - Wire dependencies

**Deliverables:** ViewModels can observe `DeviceState` and call repository methods to control the device. State stays in sync via notifications.

---

### Phase 4: Scan Screen

**Goal:** List nearby BLE devices, prioritize "Tail controller", connect on tap.

**Tasks:**

1. **`ScanViewModel`**
   - Starts/stops scanning
   - Exposes `StateFlow<List<BleDevice>>` sorted: "Tail controller" devices first, then by RSSI
   - Exposes permission state
   - `connect(address)` triggers connection via repository

2. **`ScanScreen`**
   - Permission request flow (BLUETOOTH_SCAN, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION)
   - Device list with `DeviceListItem` composable showing name, address, RSSI
   - "Tail controller" devices highlighted
   - Pull-to-refresh or auto-scan
   - On device tap: connect and navigate to DeviceOverviewScreen

3. **`DeviceListItem` component**
   - Device name (bold if "Tail controller"), MAC address, signal strength indicator

**Deliverables:** User can see nearby BLE devices and tap to connect.

---

### Phase 5: Device Overview Screen

**Goal:** Dashboard showing device status, subsystem health, current configuration summary, and navigation to sub-screens.

**Tasks:**

1. **`DeviceOverviewViewModel`**
   - Observes `DeviceRepository.deviceState`
   - Derives: subsystem statuses, current pattern name, servo positions, layer summary, total LED count
   - `toggleFftStream()` — starts/stops FFT streaming
   - Disconnect action

2. **`DeviceOverviewScreen`**
   - **Top bar**: device name, connection indicator, FFT stream toggle button
   - **Subsystem status cards**: Servos, Bluetooth, LEDs, IMUs, I2C — each shows OK/error
     - Servo status: derived from SystemInfo (4 servos configured)
     - BLE status: connected
     - LED status: derived from LedState (total LED count, num layers)
     - IMU status: derived from SystemInfo (2 IMUs, tap enabled/disabled)
     - I2C status: derived from SystemInfo (mux channels assigned)
   - **Motion summary**: active pattern name, current encoder positions (4 values as degrees)
   - **LED summary**: number of active layers, list of effect names with blend modes, total LED count from ring config
   - **Navigation buttons**: LED Config, Motion Config, Audio Config

3. **`SubsystemStatusCard` component**
   - Icon, name, status text, status color (green/yellow/red)

**Deliverables:** User sees full device status at a glance and can navigate to config screens.

---

### Phase 6: Motion Config Screen

**Goal:** Select motion patterns, edit parameters, configure servos, calibrate.

**Tasks:**

1. **`MotionConfigViewModel`**
   - Observes motion state from DeviceRepository
   - `selectPattern(patternId)` — sends command, updates local pattern
   - `setPatternParam(paramId, value)` — sends command
   - `setServoConfig(servoId, axis, half, invert)` — sends command
   - `setPidGains(servoId, kp, ki, kd)` — sends command
   - `calibrateZero()` — sends command
   - `setAxisLimits(axis, min, max)` — sends command

2. **`MotionConfigScreen`**
   - **Pattern selector**: dropdown/chip group for Static, Wagging, Loose
   - **Pattern parameters**: dynamic list of `EffectParameterSlider` based on selected pattern
     - Static: 4 position sliders (x1, x2, y1, y2) in degrees
     - Wagging: frequency (Hz), amplitude (degrees), y1 position, y2 position
     - Loose: damping (0-1), reactivity (0-10)
   - **Current positions**: real-time encoder readout (4 values, updated from notifications)
   - **Servo configuration section**: expandable cards per servo
     - Axis assignment (X/Y dropdown)
     - Half assignment (First/Second dropdown)
     - Invert toggle
     - PID gains (Kp, Ki, Kd sliders)
   - **Calibrate Zero button**: with confirmation dialog ("Position tail at neutral first")
   - **Axis limits**: X min/max and Y min/max sliders in degrees

3. **`PatternParameterControls` component**
   - Reads param metadata from `MotionPattern` enum
   - Renders appropriate sliders with labels, ranges, units

4. **`ServoConfigCard` component**
   - Expandable card per servo with all configuration fields

**Deliverables:** User can fully configure motion patterns and servo settings.

---

### Phase 7: LED Config Screen

**Goal:** Manage LED layers, effect parameters, blend modes, transforms, matrix config.

**Tasks:**

1. **`LedConfigViewModel`**
   - Observes LED state from DeviceRepository
   - CRUD operations for layers: add, remove, set effect, set blend, set enabled
   - `setEffectParam(layer, paramId, value)` — sends command
   - `setLayerTransform(layer, flipX, flipY, mirrorX, mirrorY)` — sends command
   - `setLedMatrix(numRings, ledsPerRing)` — sends command
   - Image upload flow (delegated, see Phase 8)

2. **`LedConfigScreen`**
   - **Matrix config section**: ring count, LEDs per ring (editable list)
   - **Total LED count display**
   - **Layer list**: ordered bottom (0) to top (7), each as a `LayerCard`
   - **Add layer button** (if < 8 layers)

3. **`LayerCard` component**
   - Effect name dropdown (Rainbow, Static Color, Image, Audio Power, Audio Bar, Audio Freq Bars)
   - Blend mode dropdown (Multiply, Add, Subtract, Min, Max, Overwrite)
   - Enabled toggle
   - Transform toggles: Flip X, Flip Y, Mirror X, Mirror Y
   - Dynamic parameter controls based on effect type:
     - Rainbow: direction (dropdown: H/V/diagonal), speed (slider), scale (slider)
     - Static Color: RGB color picker or 3 sliders
     - Image: orientation dropdown (0°/90°/180°/270°) + upload button
     - Audio Power: RGB sliders + fade rate slider
     - Audio Bar: RGB sliders + direction dropdown + fade rate slider
     - Audio Freq Bars: bar count, RGB sliders, fade rate, orientation dropdown
   - Remove layer button with confirmation

4. **`EffectParameterSlider` component**
   - Label, current value, slider with min/max from metadata, unit suffix
   - Debounced: sends BLE command after user stops sliding (300ms debounce)

**Deliverables:** User can fully manage LED layers and effects.

---

### Phase 8: Image Upload

**Goal:** Pick an image, resize to 32x32, convert to RGB, upload via chunked BLE writes.

**Tasks:**

1. **Image picker integration**
   - Android photo picker or `ACTION_GET_CONTENT` for image selection
   - Load selected image as Bitmap

2. **Image processing**
   - Resize to max 32x32 pixels (maintain aspect ratio, pad with black, or stretch — stretch per firmware spec)
   - Convert to raw RGB byte array (3 bytes per pixel, row-major)
   - Max 3072 bytes (32 × 32 × 3)

3. **Chunked upload**
   - Calculate chunk size based on current MTU (MTU - 3 bytes ATT overhead - 3 bytes command header = usable payload)
   - For each chunk: `uploadImageChunk(offset, chunkData)` → write to FF03
   - After all chunks: `finalizeImage(width, height, targetLayer)` → write to FF03
   - Add sequential delays between chunks (BLE needs time between writes)
   - Progress tracking via `StateFlow<Float>` (0.0 to 1.0)

4. **`ImagePickerButton` component**
   - Button to launch image picker
   - Upload progress indicator
   - Preview of selected image

**Deliverables:** User can select an image from gallery and upload it to a layer on the device.

---

### Phase 9: Audio Capture & FFT Streaming

**Goal:** Capture microphone audio, compute FFT, stream to device at 30fps.

**Tasks:**

1. **`AudioCaptureManager`**
   - Uses `AudioRecord` API with `RECORD_AUDIO` permission
   - Sample rate: 44100 Hz (standard)
   - Buffer: enough for ~33ms of audio (1 frame at 30fps)
   - Exposes PCM data via callback or Flow
   - Start/stop lifecycle

2. **`FftProcessor`**
   - Receives PCM samples, applies windowing (Hann window)
   - Computes FFT (use Android's built-in or a lightweight library)
   - Outputs magnitude spectrum
   - **Bin reduction**: reduces full FFT to N bins (configurable, default 64)
   - **Configurable frequency range**: start and end frequency for bin mapping
   - **Volume normalization**: adaptive gain that slowly adjusts to ambient volume
     - Tracks running average of peak amplitude
     - Normalization speed configurable (fast = responsive, slow = stable)
   - Outputs: loudness (0-255), bin values (0-255 each)

3. **`FftStreamManager`**
   - Orchestrates `AudioCaptureManager` → `FftProcessor` → BLE write to FF05
   - Runs at 30fps cadence
   - Uses Write Without Response for minimum latency
   - Exposes `StateFlow<Boolean>` for streaming active state
   - Start/stop controlled from DeviceOverviewViewModel

4. **Permissions**
   - `RECORD_AUDIO` runtime permission request
   - Handle denial gracefully (FFT features unavailable)

**Deliverables:** Microphone audio is captured, FFT-processed, and streamed to device in real-time.

---

### Phase 10: Audio Config Screen

**Goal:** Configure FFT stream parameters.

**Tasks:**

1. **`AudioConfigViewModel`**
   - Exposes and controls FftProcessor settings:
     - Number of bins (1-128, default 64)
     - Volume normalization speed
     - Frequency range start (Hz)
     - Frequency range end (Hz)
   - Settings stored locally (SharedPreferences) — these are app-side, not device-side

2. **`AudioConfigScreen`**
   - Bin count slider (1-128)
   - Volume normalization speed slider
   - Frequency range: start and end sliders (20 Hz - 20000 Hz, log scale)
   - Live FFT visualization preview (optional, shows current bins as a bar chart)

**Deliverables:** User can tune how the FFT stream is constructed.

---

### Phase 11: Profile Management

**Goal:** Save, load, and delete device configuration profiles.

**Tasks:**

1. **Profile UI in DeviceOverviewScreen**
   - Profile slot list (0-3) showing occupied/empty status
   - Save current config to a slot
   - Load from a slot
   - Delete a slot
   - Confirmation dialogs for load (overwrites current) and delete

2. **`DeviceRepository` profile methods**
   - `saveProfile(slot)` — writes to FF08
   - `loadProfile(slot)` — writes to FF08, then re-reads FF02/FF04/FF06 to sync state
   - `deleteProfile(slot)` — writes to FF08

**Deliverables:** User can save and restore device configurations.

---

### Phase 12: Polish & Edge Cases

**Goal:** Robust, production-quality app.

**Tasks:**

1. **Connection resilience**
   - Handle unexpected disconnects: show toast, return to scan screen or show reconnect option
   - Handle BLE adapter off: prompt user to enable Bluetooth
   - Handle bond loss: clear bond and re-pair

2. **State reconciliation**
   - On reconnect: re-read all state characteristics to resync
   - Handle notification data that conflicts with optimistic local state (notification wins)

3. **UI polish**
   - Loading states during BLE operations
   - Error states with retry actions
   - Debounced parameter sliders (don't flood BLE with writes)
   - Smooth animations for state transitions
   - Dark theme support

4. **Edge cases**
   - Device supports only one connection — show warning if another app is connected
   - Image upload interrupted by disconnect — retry from beginning
   - FFT stream: handle audio focus, phone call interruption
   - Large MTU not granted — fall back to smaller chunk sizes for image upload
   - Handle device firmware version mismatch (future: version check on connect)

5. **Testing**
   - Unit tests for protocol encoding/decoding with known byte sequences from firmware docs
   - ViewModel tests with fake repository
   - Manual testing with real hardware

**Deliverables:** Reliable, polished app ready for daily use.

---

## 5. Architecture Review & Design Fixes

After designing the architecture above, here is a critical review and the resulting changes already incorporated into the plan:

### Issue 1: Missing optimistic state updates

**Problem:** The device only sends state notifications at 1Hz. If the user moves a slider, the UI won't reflect the change for up to 1 second, making the app feel laggy and broken.

**Fix (incorporated):** `DeviceRepository` performs optimistic local state updates immediately after a successful BLE write. When a notification arrives, it overwrites the local state (reconciliation). This is already reflected in Phase 3's DeviceRepository design.

### Issue 2: BLE write flooding from slider interactions

**Problem:** Dragging a parameter slider fires continuous value changes. Each change triggers a BLE write. At ~30 events/second from a slider, this overwhelms the BLE stack (which can handle maybe 5-10 writes/second with mutex serialization).

**Fix (incorporated):** Phase 7 specifies that `EffectParameterSlider` uses 300ms debouncing. Additionally, the `DeviceRepository` should use a conflation strategy: if a new write for the same characteristic+parameter arrives while a previous write is in the mutex queue, replace the queued value instead of queuing a second write. This is added to Phase 3.

### Issue 3: FFT stream contention with command writes

**Problem:** FFT streams at 30fps to FF05 using Write Without Response. If the user simultaneously changes settings (which use Write with Response on FF01/FF03), the mutex in `BleConnectionManager` would either block FFT frames (causing audio dropout) or block settings commands.

**Fix (incorporated):** The FFT stream characteristic (FF05) uses Write Without Response which does not require a GATT response and therefore does not need to go through the mutex. `BleConnectionManager` should provide a separate `writeWithoutResponse(uuid, data)` path that bypasses the mutex. This is safe because Write Without Response is fire-and-forget at the GATT level. Only Write-with-Response operations need serialization. This distinction is added to Phase 1's BleConnectionManager design.

### Issue 4: Image upload resilience

**Problem:** Image upload sends many sequential BLE writes (3072 bytes / ~240 bytes per chunk = ~13 chunks). If any chunk fails or the connection drops mid-upload, the device has partial data.

**Fix (incorporated):** Phase 8 specifies progress tracking. Additionally, on disconnect during upload, the upload is aborted and must be restarted from the beginning on reconnect (the device has no resume capability). The finalize command (0x06) is what triggers the device to use the data, so partial uploads are harmless — they just waste bandwidth.

### Issue 5: No back-pressure on notification parsing

**Problem:** FF02 and FF04 send notifications at 1Hz. If the app is slow to parse (e.g., during a GC pause), notifications could queue up. With `SharedFlow`, old values are dropped, but the app might show stale intermediate states.

**Fix:** This is actually fine. `SharedFlow` with `replay=0` means the collector gets the latest emission. 1Hz notifications are slow enough that this isn't a real problem. No change needed — this is a non-issue upon closer analysis.

### Issue 6: Audio config persistence

**Problem:** FFT settings (bin count, frequency range, normalization speed) are app-side only. If the user reinstalls the app, these are lost.

**Fix (incorporated):** Phase 10 specifies SharedPreferences for local persistence. This is sufficient for these settings.

### Issue 7: Thread safety of DeviceRepository state

**Problem:** Multiple coroutines (notification handler, user actions, FFT stream) access DeviceRepository concurrently.

**Fix (incorporated):** `DeviceState` is immutable (data class). Updates use `MutableStateFlow.update { }` which is atomic. Individual BLE operations are serialized through the mutex in `BleConnectionManager`. No additional synchronization needed in DeviceRepository — StateFlow handles this correctly.

### Issue 8: Navigation architecture — deep links and back stack

**Problem:** If the connection drops while the user is on a sub-screen (e.g., LedConfigScreen), they need to be returned to the scan screen. But naive "navigate to scan on disconnect" can conflict with the Compose navigation back stack.

**Fix (incorporated):** Phase 12 specifies disconnect handling. The approach: when `DeviceRepository` emits `ConnectionState.DISCONNECTED`, all device screens observe this and show a "Disconnected" overlay or dialog with a "Return to Scan" button that pops the entire device back stack. This avoids forced navigation that can cause Compose navigation crashes.

### Issue 9: Missing reconnection after bond

**Problem:** After initial bonding, subsequent connections should use the bond. But if the bond is lost (device reflashed, user cleared Bluetooth cache), the connection will fail silently.

**Fix (incorporated):** Added bond loss handling to Phase 12. On connect failure after a previously bonded device, clear the bond and attempt fresh pairing.

### Issue 10: DeviceRepository lifecycle

**Problem:** If `DeviceRepository` is a singleton in `AppContainer` and the user connects to device A, disconnects, then connects to device B, leftover state from device A might leak.

**Fix (incorporated):** Phase 3 specifies that `DeviceRepository` clears all device state on disconnect. The repository represents the *current* connection, not a specific device. State is fully reset between connections.