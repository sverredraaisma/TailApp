# TailApp codebase audit — 2026-07-28

> **Status: remediated.** Everything below was fixed in the follow-up pass on the
> same day, except the two items named under "Deliberately deferred". The suite
> went 1020 → **1149 tests, 0 failures, 0 skipped**, lint holds at **0 errors**,
> and the Kotlin build is now **warning-free**. This document is kept as the
> record of what was found and why it mattered — read it as history, not as a
> list of open problems.
>
> **Deliberately deferred, still open:**
> 1. **Kotlin 2.x + Compose BOM + targetSdk 36** (P1-37, P1-39). Compose compiler
>    1.5.10 accepts only Kotlin 1.9.22, so this is one coordinated migration —
>    Kotlin 2.x plus the `org.jetbrains.kotlin.plugin.compose` plugin, then a
>    current BOM, then targetSdk 36 — and it needs device validation this pass
>    could not give it. It is what gates a Play upload.
> 2. **R8 / `isMinifyEnabled`** (P1-38). There is no release signing config, so a
>    minified release cannot be built or smoke-tested here; silently breaking the
>    ONNX or JNI paths is worse than leaving R8 off. Enable it together with a
>    signing config and an on-device smoke test.
>
> Two findings were **withdrawn** on closer inspection rather than fixed:
> `Palettes.kt:63`'s clamp is unreachable defensive code (all five callers bound
> their index), and `tools/` was missing `torch` and `scipy` rather than `madmom`
> — madmom is deliberately unpacked from an sdist because it ships no 3.11 wheel,
> so declaring it would have broken `uv sync`.
>
> One fix was **reverted on evidence**: unifying the three disagreeing tempo
> ranges onto a single 55-215 band lost the beat lock entirely — the whole of
> `BeatTrackerTest` failed, including a clean 128 BPM grid. `beat/TempoRange` now
> carries a representable range (55-215) *and* a narrower search range (60-200)
> for `TempoEstimator`, whose harmonic comb and log-normal prior are tuned for
> that band. The distinction is measured and documented there.

Full-codebase audit at commit `9a7c0dd` ("Add a full build-and-install README").
Six parallel reviewers covered BLE protocol conformance, the BLE transport and
repository, the BeatLight signal chain and renderer, the presentation layer,
build/dependencies/tests, and documentation accuracy. Every finding below cites
lines that were read; the headline items were re-verified independently before
landing here.

**Scope:** ~31.4k LOC app (`app/src/main`), ~20k LOC JVM tests (`app/src/test`),
the JNI capture layer (`app/src/main/cpp`), 8 docs, and a cross-repo check
against **TailFirmware** at `f6147b1` ("Fix the P0/P1/P2 findings from the
2026-07-27 codebase audit").

**Baseline, measured fresh with `--rerun-tasks` (a stale 7/25 cache had to be
discarded first):**

| Measure | Result at audit time |
|---|---|
| `gradlew.bat testDebugUnitTest` | **BUILD SUCCESSFUL**, 126 s |
| Test suites / tests | 96 / **1020** |
| Failures / errors / `@Ignore` | **0 / 0 / 0** |
| `gradlew.bat lintDebug` | **BUILD SUCCESSFUL**, 185 s |
| Lint errors / warnings / hints | **0** / 28 / 1 |
| Committed secrets | none (`git ls-files` scan clean) |

---

## Verdict

**The engineering this codebase is proudest of holds up.** The wire format is
correct: six characteristics were checked field-by-field against the firmware
that publishes them and the encoding, endianness, CRC-32, framing and packet
sizing are right everywhere, including the subtle cases the docs call out — the
FF0A `((mtu-3)-2)/3` capacity, the v6 framed-block skip-by-length walk, the FF05
beat trailer offset. The `led/` port really is faithful: `GAMMA8` was recomputed
and diffed at all 256 entries with zero differences, and the blend, palette,
noise and layout arithmetic match the C++ including its deliberate truncation.
The DSP is sound where it matters most — the Bluestein FFT does *not* have the
classic `n² mod 2N` precision bug, and every degeneracy path in the particle
filter is handled. 1020 tests pass with nothing disabled.

**What fails is the layer nobody wrote a test for.** There are no instrumented
tests, and the six packages with no coverage at all — `ui.screen`,
`ui.components`, `navigation`, `di`, `ui.theme`, and the app root — are exactly
where every P0 landed. All four are in **twelve lines of `AndroidManifest.xml`**.

The most serious is that **the app cannot discover a tail on any Android 12 or
newer device.** `BLUETOOTH_SCAN` is declared without
`android:usesPermissionFlags="neverForLocation"`, so the platform additionally
requires `ACCESS_FINE_LOCATION` before it will deliver a single `ScanResult` —
and `ScanScreen` never requests it on API 31+. Permissions read as granted,
`isScanning` goes true, `onScanFailed` never fires, and the list sits on "No
devices found" forever. Two reviewers found this independently from opposite
ends. On Android 8–11 the app is worse off still: the legacy `BLUETOOTH` /
`BLUETOOTH_ADMIN` permissions are absent entirely, so `startScan` and
`connectGatt` throw `SecurityException` — caught nowhere.

Neither is reachable by the JVM suite, and lint does not model the
`neverForLocation` interaction, which is why a green build has been sitting on
top of them.

Two further themes are worth naming. **A documented invariant is not
implemented at all**: `LedOutputStage` — the class CLAUDE.md singles out as the
reason the preview does not "quietly lie" — has no production caller. And
**there is no CI**, which is how a 1020-test suite ends up guarding a codebase
whose actual failure modes are all outside it.

---

## P0 — ship blockers

### 1. BLE scanning is dead on Android 12+ (`neverForLocation` missing)

`app/src/main/AndroidManifest.xml:4,6-7` · `ui/screen/ScanScreen.kt:52-56`

`BLUETOOTH_SCAN` is declared without the `neverForLocation` assertion, and
`ACCESS_FINE_LOCATION` without `android:maxSdkVersion="30"`. Under that exact
combination Android 12+ treats a scan as location-deriving and withholds every
result until `ACCESS_FINE_LOCATION` is granted. `ScanScreen.kt:52-54` requests
only `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` when `SDK_INT >= S`.

There is no error path: the scan starts, `onScanFailed` is never called, and the
user gets a permanent empty list with nothing to diagnose. `ACCESS_COARSE_LOCATION`
(`:7`) is declared and never requested by any code path.

**Fix:** add `android:usesPermissionFlags="neverForLocation"` to `BLUETOOTH_SCAN`
and `android:maxSdkVersion="30"` to both location permissions. The app derives no
location from scan results, so the assertion is honest.

### 2. Every BLE call throws `SecurityException` on API 26–30

`app/src/main/AndroidManifest.xml:4-7` · `app/build.gradle.kts:12` (`minSdk = 26`)

`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` only exist from API 31. On Android 8–11 the
app holds neither `android.permission.BLUETOOTH` nor `BLUETOOTH_ADMIN`, so
`BleScanner.kt:95` (`startScan`) and `BleConnectionManager.kt:211` (`connectGatt`)
throw. `BleScanner.kt:97` catches only `IllegalStateException`, so it is a crash.
A third of the declared minSdk range is unusable.

**Fix:** declare both legacy permissions with `android:maxSdkVersion="30"`.

### 3. No `POST_NOTIFICATIONS` — two mic-holding services run invisibly on API 33+

`AndroidManifest.xml` (absent) · `audio/AudioStreamService.kt:53` ·
`effects/BeatLightService.kt:61`

targetSdk is 34. The permission is neither declared nor requested anywhere.
`ServiceCompat.startForeground` still succeeds, but the notification is
suppressed — leaving a foreground service capturing the microphone under a
wake lock (`AudioStreamService.kt:26`, up to 4 hours) with no notification, no
stop affordance, and no way for the user to know it is running. That is a
Play-policy failure as well as a UX one.

**Fix:** declare `POST_NOTIFICATIONS` and request it from `BeatLightScreen` /
`AudioConfigScreen` alongside `RECORD_AUDIO`. Give both notifications a content
intent and a stop action while you are there (P2 item below).

### 4. `connect()` on a live connection leaks the GATT client and kills the new link

`ble/BleConnectionManager.kt:211,178-184`

`gatt = device.connectGatt(...)` overwrites the field without `disconnect()` or
`close()` on the previous client. This is reachable from the UI: navigating back
from `DeviceOverviewScreen` does not disconnect — only the explicit toolbar
button does (`DeviceOverviewScreen.kt:175`), and system back has no handler at
all (P1-9).

Connect to A, press back, connect to B: A's client is leaked (Android permits
~32 registered clients before `connectGatt` starts returning null), A stays
connected, and when A's stack eventually reports `STATE_DISCONNECTED`,
`teardown` unconditionally sets `_connectionState.value = DISCONNECTED` and
calls `failPendingOperations()` — **killing B's in-flight setup**. The
`if (this.gatt === gatt)` guard at `:181` protects the handle but neither the
state flow nor the pending operations.

**Fix:** tear down any existing gatt inside `connect()`, and gate the state-flow
write and `failPendingOperations` in `teardown` on `this.gatt === gatt`.

---

## P1 — wrong behaviour

### Rendering fidelity — the preview lies about three separate things

**5. `LedOutputStage` has no production caller.** `led/LedOutputStage.kt:17` is
referenced only by `LedOutputStageTest`. `LedStackRenderer.renderFrame`
(`:104`) returns the raw composite and `LedPreviewClock.frameAt` (`:70`) hands
it straight to the UI, while `LedConfigViewModel.setOutputConfig` (`:175`) lets
the user set brightness, gamma and current limit on the device, which applies
all three in `LedMatrix::push` (`led_matrix.cpp:161-190`). Set a 500 mA limit and
a bright white stack: the device scales the frame to ~40%, the preview shows
full. This is verbatim the "browns out on hardware as if it were fine" failure
CLAUDE.md names as the class's reason to exist. *Verified independently: the
class appears nowhere else in `app/src/main`.*

**6. Per-layer opacity is parsed, carried, and then dropped.**
`led/LedStackRenderer.kt:76` constructs `LayerCompositor.Layer(renderer,
blendMode, config.enabled)` — omitting the 4th parameter, which defaults to
`opacity = 255` (`LayerCompositor.kt:13-19`). `LedStateParser.kt:34` parses the
value and `ColorMath.blend` implements it correctly; it is simply never passed.
A layer set to 50% renders at 100% in the preview. Opacity is tested at the
`ColorMath` level only, never through `LedStackRenderer`. *One-line fix.*

**7. `ImageRenderer` never received the firmware's `src_len` hardening.**
`led/effects/ImageRenderer.kt:23-27` vs `image_effect.cpp:6-23`. The firmware's
2026-07-27 audit fixed a `uint16_t` truncation that let a 200×220 image copy
~129 KB out of a 3 KB buffer; the fix added a `src_len` refusal that blanks the
effect. The Kotlin `setImage(rgb, width, height)` has no length parameter and no
check, so it renders the in-range prefix plus black where the device renders all
black. This is the only firmware-audit fix that changed render behaviour, and
the port has not followed it.

### BLE transport and repository

**8. Everything BLE runs on `Dispatchers.Main.immediate`.** `di/AppContainer.kt:34,50`.
20 Hz FF02 parsing, the OTA stream loop (`DeviceRepository.kt:1069-1158`),
`Crc32.compute` over a whole firmware image (`:1020`), the ~80-write
`installLayerStack` (`:1211-1257`) and all image chunking run on the UI thread. A
1 MB CRC there is an ANR candidate; the rest is sustained jank. `LightingEngine`
already takes its dispatcher as a parameter — the repository should too.

**9. `refreshAll()` runs inside the notification collector and starves it.**
`DeviceRepository.kt:202` — `CONFIG_CHANGED -> refreshAll()` suspends the
collector for four mutex-serialised reads (up to 20 s of timeouts).
`BleConnectionManager`'s 64-slot buffer (`:50`) fills in ~3 s at 20 Hz and
`emitUpdate` (`:167-171`) then drops — including FF09 acks, so unrelated
in-flight commands time out and retry for nothing. Same shape in `loadProfile`.
Launch it, as `onDisconnected` already does for `abandonAll`.

**10. The routing relay negates the 64-slot buffer.** `ble/RoutingBleTransport.kt:40-42`
— `shareIn(scope, Eagerly, replay = 0)` yields `extraBufferCapacity = 0` with
SUSPEND, so the relay's `emit` blocks until the repository consumes each item.
Any downstream slowness back-pressures into `BleConnectionManager`'s `tryEmit`
and drops notifications, and every notification hops the main thread.

**11. Timed-out GATT reads are not keyed by characteristic.**
`BleConnectionManager.kt:57,265-288`. `readCompletion` is a single anonymous slot
with no UUID. After a 5 s timeout the mutex is released while the stack still
owes a response; the next read installs a fresh completion and the *previous*
characteristic's callback resumes it. Concretely: an FF06 read times out under
load, `refreshProfiles` starts, and FF06 bytes are handed to `ProfileListParser`.
Same hazard on `writeCompletion` and `descriptorWriteCompletion`. Narrower race
at `:274-281`: the completion is installed before `readCharacteristic`, so a
stale callback in that window can resume the continuation twice →
`IllegalStateException: Already resumed`.

**12. Optimistic FF06-backed updates never reconcile.** `DeviceRepository.kt:456-457`
— `sendCommand`'s `Boolean` is discarded by every caller and the cache is
updated regardless of rejection. FF02/FF04 fields self-correct from notifies, but
the FF06 block has no notify and no periodic re-read, so `setMotorsEnabled`
(`:587`), `setMotionLimits` (`:557`), `setServoConfig` (`:514`) and `setImuTap`
(`:598`) leave a lie until reconnect. Worst case: `setMotorsEnabled(true)` after
a stall clears the stall banner even when the device refused.

**13. Switching to the virtual tail strands the real connection.**
`ble/RoutingBleTransport.kt:48-52` flips `active` without disconnecting the old
backend; `disconnect()` then routes to the virtual transport and the real
`BluetoothGatt` stays open for the process lifetime.

**14. Scanner wedges permanently if Bluetooth is turned off mid-scan.**
`ble/BleScanner.kt:70-71,104-112`. Adapter-off produces no `onScanFailed`, so
`_isScanning` stays true, `startScan` early-returns forever, and the UI shows a
spinner with no button. There is no scan timeout at all. `stopScan`/`startScan`
also catch only `IllegalStateException`, so a permission revoked in Settings
crashes via `ScanScreen.kt:70`'s `onDispose`.

**15. `enableNotifications` results are all discarded** (`DeviceRepository.kt:157-172`,
seven calls). A failed CCCD write on `CMD_RESULT` makes every
`sendCommandAwaitingAck` burn 3 × 1 s and report `Unanswered`, with no
indication why.

**16. `writeWithoutResponse` discards the queue-full result.**
`BleConnectionManager.kt:332-348` ignores `BluetoothStatusCodes` on API 33+ and
the `Boolean` on the legacy path; `findCharacteristic(uuid) ?: return` at `:333`
drops packets silently. `streamDirectFrame` bursts N packets per frame with no
flow control, so `ERROR_GATT_WRITE_REQUEST_BUSY` shows up as torn pixels and
nothing in the log.

### Signal chain

**17. `SectionStateTracker` latches into `DROP` forever ~50 s after music stops.**
`drop/SectionStateTracker.kt:147`. `everHighEnergy` is cleared only by `reset()`.
Once the 45 s `RollingStats` window turns over to the noise floor, `rmsZ` returns
to ~0, the `quiet` branch stops firing, and the state commits to `DROP`
permanently — full-intensity drop lighting in a silent room, indefinitely.

**18. `FftProcessor`'s adaptive gain converges to a fixed ⅓.**
`audio/FftProcessor.kt:89-91`. `p ← p(1 − s/3) + s·rms` has fixed point
`p* = 3·rms`, so `normalizedLoudness = rms/p* × 255 = 85` for *any* steady level.
After ~2 s the FF05 stream reports 33% whether you whisper or shout, spiking only
on changes. The test asserts `in 0..255` only.

**19. The log bin mapping puts the DC bin in the lowest 8 of 64 output bins.**
`audio/FftProcessor.kt:98-105`. At the live geometry (1470 samples →
`highestOneBit` = 1024, 43.07 Hz/bin) output bins 0-7 all resolve to
`from=0, to=1` and return `magnitudes[0]` — the DC term — identically; bins 8-13
all return `magnitudes[1]`. A 60 Hz bass note does not move the bottom eight
bars; they track the mic's DC offset, which is also folded into the loudness RMS.
Floor `startBin` at 1 and zero-pad 1470 → 2048 rather than truncating to 1024.

**20. `FftStreamManager` stop→start releases the *new* recorder and busy-spins.**
`audio/FftStreamManager.kt:88-97,79-82`. `stop()` uses `cancel()`, not
`cancelAndJoin()`, so the old job's `finally { audioCaptureManager.stop() }` runs
asynchronously; a `start()` in that window overwrites `audioRecord` and the stale
`finally` releases the new one. The new loop then reads 0, which the loop at
`:67-78` treats as neither error nor data — an unbounded busy-spin with
`_isStreaming` still true. Double-tapping the toggle leaks a mic-holding
`AudioRecord`, kills FF05, and pegs a core.

**21. `StrobeEffect` subscripts a raw array with an unvalidated persisted value.**
`composer/effects/StrobeEffect.kt:31` via `ParamBag.enumIndex`
(`EffectParam.kt:109`, no clamp); `CompositionSerializer.decodeParams` (`:147`)
validates neither `Choice` indices nor `Scalar` ranges on load. An imported
`.tailstack.json` with `"division": 5` throws from the render coroutine — the
render loop dies while `analysisJob` lives, so the UI shows a running session
with a frozen tail. Clamp centrally in `ParamBag.setAll` against the schema.

**22. Genre inference blocks the render loop every 3 s.**
`genre/OnnxGenreClassifier.kt:96-128` is called inline from
`effects/LightingEngine.kt:696`, and analysis and render share
`workDispatcher = Dispatchers.Default.limitedParallelism(1)`. An EffNet-class
forward pass is 50-150 ms on a mid-range phone → 2-5 dropped frames every 3 s, as
a periodic visible hitch. It already takes a copied window and returns a value,
so it can move to its own dispatcher unchanged.

**23. `MotionChoreography`'s drop flick reverses mid-flick.**
`composer/MotionChoreography.kt:89`. The comment says "alternate direction per
drop"; the code reads `ctx.beatCount`, which advances 2-3 times inside the 1.2 s
`FLICK_WINDOW_SECONDS` at 120 BPM. The tail is thrown to +25°, then snaps to
−25° half a beat later at full amplitude — a hard mechanical reversal commanded
at render rate. Latch the sign at drop time.

**24. Duplicate layer ids are unvalidated and `importJson` is a live path to them.**
`composer/CompositionRenderer.kt:84-109` keys instances by `node.id`, so two
nodes sharing an id share one `ReactiveEffect`: params applied twice, `render`
called twice per frame, every dt-integrating effect double-advancing.
`EffectComposerViewModel.importJson` (`:207-222`) re-ids the composition but
deliberately not the layers, and nothing validates a decoded tree. This is the
exact failure CLAUDE.md warns about ("Layer ids must be unique across the whole
tree, or two layers share one instance") with no enforcement behind it.

### Presentation

**25. No `rememberSaveable` or `SavedStateHandle` anywhere in the app.** Every
dialog and text field is plain `remember`: `DeviceOverviewScreen.kt:125,131,146,158`,
`EffectComposerScreen.kt:106-112,883`, `MotionConfigScreen.kt:139,365`,
`LedConfigScreen.kt:267,299`, `ScanScreen.kt:50`. Open "Rename device", type,
rotate → dialog gone, text lost.

**26. System back leaves the device connected and streaming.**
`DeviceOverviewScreen.kt:175` + `navigation/TailAppNavHost.kt:73-75`. Only the
toolbar icon disconnects; there is no `BackHandler` in the app. Gesturing back
leaves GATT open and FF05 running while `ScanScreen` starts scanning on top of a
live connection. Compounds P0-4.

**27. A disconnect on any sub-screen is invisible and never navigates.**
`DeviceOverviewScreen.kt:125-144`. The detection lives only on the overview and
`wasConnected` is a plain `remember`, which Navigation-Compose disposes when a
child destination is pushed. Lose the tail while on MotionConfig and nothing
reacts — sliders write into the void (every command reports `!written`, which
only `DeviceOverviewViewModel` inspects) — and popping back recomposes with
`wasConnected == false`, so the dialog still never appears.

**28. `PuppetPad` floods BLE at pointer rate.** `MotionConfigScreen.kt:188-190,587-598`
→ `MotionConfigViewModel.kt:63-83`. `onDrag` fires `startPuppet` on every pointer
sample (60-120 Hz), and `startPuppet` does `puppetJob?.cancel(); puppetJob =
launch { streamMotionTargets(); delay(100) }` — so each sample kills the resend
loop and issues a fresh FF0B write. The 100 ms pacing only ever elapses when the
finger is still. Keep one long-lived job reading a `@Volatile` target instead.

**29. SAF reads run on the main thread.** `FirmwareUpdateScreen.kt:70-77` and
`EffectComposerScreen.kt:118-132` call `openInputStream(...).readBytes()` inside
the `ActivityResultContracts.OpenDocument` callback. The firmware screen's own
comment says "The image can be a megabyte"; picking from a network-backed
provider blocks the UI thread for the whole download. `LedConfigViewModel.kt:195`
already does this correctly with `withContext(Dispatchers.IO)`.

**30. `DebouncedSlider` drops the last edit if the screen leaves within 250 ms.**
`ui/components/EffectParameterSlider.kt:50-58` writes from a
`LaunchedEffect(pendingValue)` after `delay(250)`; leaving composition cancels
it. Drag a limit to its final value, tap Back immediately — UI showed the new
value, device never got it, nothing says so.

**31. The composer leaves unsaved edits live in the engine with no way back.**
`EffectComposerViewModel.kt:56,333-340`, no `onCleared`. `edit()` writes
`engine.composition` unconditionally; backing out without saving leaves the tail
rendering the unsaved tree, while reopening the editor loads the *stored*
version — so editor and tail diverge and `hasUnsavedChanges` reads false.

**32. Permanent permission denial is a dead end on all three screens.**
`ScanScreen.kt:58-67`, `AudioConfigScreen.kt:57-61`, `BeatLightScreen.kt:103-107`.
No `shouldShowRequestPermissionRationale`, no rationale, no settings intent.
After the second denial the launcher returns denied instantly and the buttons do
nothing, forever, with no message.

**33. `BeatLightScreen` recomposes the whole screen at preview frame rate.**
`BeatLightScreen.kt:87-99`. `frame` and `state` are collected and read at the top
level, invalidating the entire `Column` ~60×/s; `CompositionsSection` takes a
`List` (unstable to the Compose compiler) so it can never skip, and
`Composition.summary()` (`:529`) walks the tree recursively on every one.

**34. The LED preview renders the firmware engine on the main thread.**
`ui/components/LedPreview.kt:73-79` runs the full `LedStackRenderer` port inside
`withFrameNanos`, at display refresh rate, on the UI thread — plus a per-LED
radial-gradient brush allocation at `:186-195`. CLAUDE.md's "analysis never runs
on the main thread" is honoured for capture but not for render.

**35. Tap events queue behind a suspending snackbar.**
`DeviceOverviewScreen.kt:101-122`. `showSnackbar` suspends ~4 s and the collector
is sequential, so a few quick taps produce a minute of replaying "Tap detected"
snackbars while events 17+ are dropped by `tryEmit`.

**36. `LaunchedEffect(lastResult)` misses repeated identical failures.**
`DeviceOverviewScreen.kt:90-98`. `lastCommandResult` is never cleared, so the
same rejection twice in a row shows once and the user believes the second
succeeded; on rotation the stale one re-shows as new.

### Build and tooling

**37. `targetSdk = 34` is two Play generations behind** (`app/build.gradle.kts:13`,
lint `OldTargetApi`). The Aug-2025 gate was API 35, the Aug-2026 gate is 36 — a
store upload today is already rejected. Blocked in practice by finding 39.

**38. Release builds have no minification or resource shrinking.**
`app/build.gradle.kts:44-50` — `isMinifyEnabled = false`, no `isShrinkResources`,
and `proguard-rules.pro` is a single comment. The APK already carries ~29 MB/ABI
of ONNX native code. The codebase is reflection-free, so the standard rules plus
ORT's consumer rules ought to suffice — but that is unproven, because R8 has
never run. There is also **no release signing config at all**: release builds are
currently unsignable.

**39. Compose BOM 2024.02.00 is ~2.5 years stale and transitively pinned.**
`app/build.gradle.kts:82`, dragging activity-compose 1.8.2→1.13.0,
navigation-compose 2.7.7→2.9.8, lifecycle 2.7.0→2.11.0, core-ktx 1.12.0→1.19.0,
coroutines 1.7.3→1.11.0. None are known-vulnerable, but Compose compiler 1.5.10
accepts *only* Kotlin 1.9.22, so this is one coordinated move — Kotlin 2.x + the
`org.jetbrains.kotlin.plugin.compose` plugin (which replaces `composeOptions`
entirely), then a current BOM — and it gates finding 37.

**40. NDK version unpinned and 16 KB page alignment unasserted.** No `ndkVersion`
in `app/build.gradle.kts`; no `-Wl,-z,max-page-size=16384` in
`app/src/main/cpp/CMakeLists.txt`. AGP 8.13's default NDK (r27+) aligns to 16 KB
by default, so this likely works today — but it silently depends on whichever NDK
the build machine has, and r25/r26 produces a `libtailapp_audio.so` that
hard-fails on Android 15+ 16 KB devices with no signal until runtime.

**41. `tools/` declares neither `torch` nor `madmom`.** `pyproject.toml` pins six
packages exactly, but `tools/export_beatnet.py` imports `torch` and
`tools/dump_beat_reference.py` imports `madmom` and `BeatNet.*`. A clean
`uv sync` produces an environment in which 2 of the 6 scripts fail at import.

---

## P2 — latent, performance, hygiene

**Port fidelity.** `BeatPulseRenderer.kt:62` clamps where `beat_pulse_effect.cpp:48`
wraps, and this is reachable, not theoretical. `downbeatBoost` is deliberately
unclamped in `setParam` on *both* sides (`BeatPulseRenderer.kt:66-75`), and
`render` assigns it straight to `brightness` on a downbeat (`:34`). At
`downbeatBoost = 1.5` on default white, the downbeat frame computes `255 × 1.5 =
382.5`: the firmware's `static_cast<uint8_t>` gives **126**, a dark grey — its
`describe_params` comment says so outright ("anything above full white wraps
rather than clipping") — while `PixelBuffer.set`'s `coerceIn` gives **255**, full
white. On the exact frame a downbeat lands, the preview flashes bright where the
tail goes dark, on the one effect with no parity test. The line comment at `:61`
("Truncated, matching the firmware's `static_cast<uint8_t>`") asserts the
behaviour the call does not have, which is what hides it. Prefer clamping
`brightness` to 1.0 on both sides over reproducing the wrap: out-of-range
float→integer conversion is UB in C++, so the firmware's 126 is an artefact, not
a designed look ·
palette-id out of range wraps to a valid palette on-device (`uint8_t` cast) but
blanks the layer in `FireRenderer.kt:30`, `GradientScrollRenderer.kt:29`,
`PlasmaRenderer.kt:27`, `MotionGlowRenderer.kt:40`, `TwinkleRenderer.kt:36` (fix:
`and 0xFF`) · `ImageRenderer` stores `Int` dimensions where the firmware uses
`uint8_t`, so a 256-wide image renders in the port and blanks on the device ·
`AnimationRenderer.kt:25-27` renders black by design (frames live in device
flash), so the preview shows black where hardware plays an animation ·
`ColorMath.kt:66` falls back to `OVERWRITE` where `layer_compositor.cpp:110`
falls back to `overlay` (unreachable today) · `BeatPulseRenderer` and
`AnimationRenderer` have no parity coverage.

**Composer.** `CompositionRenderer.kt:158-173` applies opacity with its own float
`mix()` instead of `ColorMath.blend(...)`, losing per-channel deltas below
`1/opacity` and contradicting its own KDoc (`from=255, to=0, t=0.5` gives 128
where `ColorMath.normal(255, 0, 128)` gives 127) · `CompositionRenderer.kt:175-184`
— `applyBrightness` uses float `(channel * brightness).toInt()` where the
firmware's master brightness is the integer `c * factor / 255` (`rgb_scale`), the
same one-LSB drift, compounding with the device applying its own master
brightness on top of the streamed frame · `led/PixelBuffer.kt:24-30` clamps to
0..255 with a comment claiming this matches the firmware's `uint8_t` casts — it
does not, C++ truncates mod 256 (a computed 300 is 44 on the device, 255 in the
port). `BeatPulseRenderer` is the one effect that reaches that range — see the
worked example above. Every other effect either clamps before writing or
multiplies a ≤255 base by a level provably in [0,1], and all five `Palettes.sample`
callers bound their index (`FireRenderer.kt:46`, `GradientScrollRenderer.kt:41`,
`MotionGlowRenderer.kt:41`, `PlasmaRenderer.kt:42`, `TwinkleRenderer.kt:59`), so
`Palettes.kt:63`'s own `coerceIn` is unreachable defensive code rather than a
divergence · `CompositionScene.kt:278` —
`timeSeconds` never wraps; at 24 h `SectionDimmerEffect.kt:37` quantises to a
handful of duty states and `PlasmaEffect.kt:33` freezes for frames · `Json.kt:149,176,186`
has no recursion depth limit (a ~10k-deep array overflows the stack, survivable
only because `CompositionSerializer.kt:30,38` catches `Throwable`, contradicting
the KDoc's `JsonException` promise) · `Json.kt:217-221` — `"\uZZZZ"` throws
`NumberFormatException` and `toInt(16)` accepts a sign, so `"\u-12F"` silently
becomes U+FED1, and `"\uD800"` decodes to a lone surrogate that `writeString`
(`:90-92`) re-emits raw, producing invalid UTF-16 on round-trip; `:74` writes
NaN/Infinity as `0` with no diagnostic, so a
composition that acquired a NaN brightness silently saves as black rather than
failing loudly, and `readNumber` (`:230-236`) accepts JSON-invalid forms like
`00123` and `1.` ·
`DropFlashEffect.kt:45` starts a weak drop's burst mid-tail ·
`MotionChoreography.kt:58,103` hands its reused internal array across a seam that
does not enforce the "valid until next call" contract ·
`CompositionRenderer.kt:47,54` — `composition` is a plain `var` read cross-thread.

**Engine and audio.** `LightingEngine.kt:416-417` — `start()`'s `isRunning` guard
spans a suspending mic-open, so two concurrent starts leak an `AudioSource` and
share one `FeatureExtractor`, the exact corruption `limitedParallelism(1)` exists
to prevent · `:505-545` — `pumpAnalysis`'s drain loop has no suspension point, so
a backlog starves `renderFrame` and past 2 s the device ages the FF0A stream out ·
per-frame allocation at `TailDirectLedOutput.kt:73`, `LightingEngine.kt:573,662,668`,
`BarSweepEffect.kt:56` · `cpp/ring_buffer.h:43-44` computes `skipped` then
discards it, so the two ring mirrors report different `overrunCount` ·
`cpp/ring_buffer.h:88` / `FloatRingBuffer.kt:136` — the seqlock bound omits the
producer's in-flight burst (needs a ~1.5 s deschedule to bite) ·
`cpp/oboe_capture.cpp:183` mallocs and zero-fills 8 KB ~200×/s on the analysis
thread · producer/consumer cursors share a cache line (`ring_buffer.h:137-139`),
compounded by a `getAndAdd(0)` used purely as a fence · `FftProcessor.kt:53-54,65,94`
allocates ~300 KB/s and recomputes twiddles that `dsp/Fft.kt` already tabulates ·
`FftProcessor.kt:33-36` — four non-volatile tunables mutated from the UI thread
while `process()` runs on IO · `FeatureFrameFftEncoder.kt:50-58` — a `@Volatile`
setter nulls a non-volatile field · `AudioRecordAudioSource.kt:101-108` — `stop()`
cuts the reader loose, so a fast restart violates the single-producer contract ·
`OboeAudioSource.kt:26` — the native handle is a non-volatile raw pointer (a
misuse is a native use-after-free, not an exception) · no non-finite guard at the
capture boundary (`AudioRecordAudioSource.kt:88`, `OboeAudioSource.kt:93`): one
NaN poisons bands, flux and the drop statistics for the session, and
`FeatureFrameFftEncoder.kt:80`'s `if (v > frameMax)` is false for NaN so the FF05
bars go silently dead · `FftStreamManager.kt:85` — unwrapped
`startForegroundService` leaves the mic open on an API 31+ throw.

**Beat, drop, genre.** `TempoEstimator.kt:209-218` divides by sample count, not
variance, so `SWITCH_MARGIN` hysteresis means different things on different
material (~3 s of the old BPM after a track change) · `:154-173,227-231` — a
60 BPM track with a steady eighth-note pulse locks at 120, and 60 BPM sits exactly
at `maxLag` where `interpolatePeak` refuses to interpolate; the three tempo ranges
in the codebase disagree (55-215, 60-200, 60-…) · `BeatTracker.kt:371-382,89` —
`trackSilence` drops phase but not tempo, and `bpm` reports the last locked value
forever, diverging from `ParticleFilterBeatDecoder.bpm:326` which correctly
returns 0 · `SectionStateTracker.kt:73-88` — `confidence` counts agreement with
the *outgoing* state, collapsing to the 0.25 floor on every genuine transition ·
`TransientConfig.kt:56` — `buildupRiseRatio` is documented as *the* build-up
criterion and read nowhere (three literals are hard-coded at
`SectionStateTracker.kt:139-142`); `RollingStats.meanBefore`/`latest` and
`TransientSnapshot.centroidZ` are likewise production-dead · `TransientConfig.kt:64-70`
doesn't validate `onsetDensityWindowSeconds`; a 0 yields NaN at
`TransientDetector.kt:146` and makes BUILDUP permanently unreachable, silently ·
`OnnxGenreClassifier.kt:130-139` and `CrnnActivationSource.kt:143-150` — `close()`
has no production caller, both ONNX sessions are retained for the process
lifetime, and `close()` is unsynchronised against an in-flight `classify` ·
`GenreLabels.kt:82` — `EXPECTED_COUNT` is only a capacity hint, so a truncated
label list silently reads a prefix of the real buffer · `EffnetMelSpectrogram.kt:116-172`
computes 187 frames and uses 128, discarding ~32% of the mel work and never
classifying the last 0.94 s of each window · minor: `BeatTracker.kt:216,307`,
`ParticleFilterBeatDecoder.kt:465`, `OctaveBias.kt:32-34`, `TransientDetector.kt:107`.

**Transport.** `requestMtu`/`discoverServices` bypass the GATT mutex
(`BleConnectionManager.kt:229-263`) · `CONNECTED` is published before service
discovery (`:76-77`), so the UI reads "Connected" while every `findCharacteristic`
returns null · `disconnect()` never guarantees `close()` if the callback never
arrives (`:226`) · `notificationJob?.cancel()` is not joined
(`DeviceRepository.kt:142-143`), so a fast reconnect can shift
`CommandAckTracker`'s FIFO correlation by one · `_systemEvents`/`_commandResults`
`tryEmit` drop silently (`:199,247`) · `streamFirmware`'s `NonCancellable` abort
can burn 8 s on the main thread when the cause is a disconnect (`:1152-1157`) ·
silent `?: return` on a failed FF07 read (`:340`) · the scanner re-sorts and
re-emits the whole device map on every advertisement (`BleScanner.kt:58-67`).

**Presentation.** Side effect written during composition
(`DeviceOverviewScreen.kt:126-128`) · `SecurityException` unhandled across the
BLE seam (`BleScanner.kt:73-110`, all call sites `@SuppressLint`) · hardcoded
axis-limit slider ranges that an offset-mounted tail cannot represent
(`MotionConfigScreen.kt:207-238`) · degenerate `min == max` slider ranges yield a
NaN thumb (`EffectComposerScreen.kt:687-689`, `EffectParameterSlider.kt:97`);
nothing asserts `min < max` on the specs · 126 tick marks on one slider and no
`freqStart < freqEnd` cross-validation (`AudioConfigScreen.kt:122-163`,
`AudioConfigViewModel.kt:101-113`) · foreground notifications have no content
intent and no stop action (`AudioStreamService.kt:38-43`, `BeatLightService.kt:52-57`) ·
`PuppetPad` competes with the parent scroll (`MotionConfigScreen.kt:582-604`) ·
a `ViewModelProvider.Factory` allocated per recomposition at 11 sites in
`TailAppNavHost.kt` · **the nav `address` argument scopes nothing** — every
ViewModel is built from the singleton repository, 7 of 11 destinations declare
`address` and never read it, and deep-linking to a MAC renders whatever the
repository happens to be connected to (the URL encoding itself round-trips
correctly) · zero `contentDescription` outside `ScanScreen.kt:107` (`PuppetPad`,
`LedPreview`, `TailPositionView` and the beat dot are invisible to TalkBack) ·
`res/values/strings.xml` holds exactly one entry, so no screen is localisable.

**Build hygiene.** `-Ofast` on the native target (`cpp/CMakeLists.txt:13`) implies
`-ffinite-math-only`, making NaN checks in the capture path undefined — it buys
nothing for a memcpy-bound callback · **no CI configuration of any kind** (no
`.github/`, `.gitlab-ci.yml`, `Jenkinsfile`, `.circleci`) · Gradle 9.3
deprecation warnings on every run, almost certainly from AGP 8.13.2 ·
`MissingApplicationIcon` (`AndroidManifest.xml:17` — the app ships the system
default launcher icon) · `android:allowBackup="true"` puts saved stacks and
paired-device state into cloud backup by default rather than by decision ·
`ObsoleteSdkInt` at `AudioRecordAudioSource.kt:139`, unused `R.color.black`/`white`,
15× `UseKtx`, 1 `TrimLambda` · one Kotlin warning, `BehaviorModelsTest.kt:231:48`
(duplicate label name) · `tools/dump_beat_reference.py:208` imports madmom's
private `_diff_frames`; both BeatNet scripts inject `sys.path` and fail with a
bare `ImportError` if `download_beatnet.py` has not been run.

---

## Cross-repo: what the firmware's own audit changed

The firmware fixed every finding from its 2026-07-27 audit in `f6147b1`. Three
consequences reach this side:

1. **`image_effect.cpp` gained a `src_len` refusal** — the only fix that changed
   render behaviour. The Kotlin port has not followed it (P1-7). Every other LED
   change (`animation_storage.cpp` fclose checking, `led_matrix.cpp` bounds and
   `coords_` retirement) is robustness, not pixel math, so **the port is not
   stale on the numbers.**
2. **FF09 now has two independent sequence counters** — an open item in the
   firmware's own report. Executed commands use `app_bridge.cpp:285`'s `g_ack_seq`;
   writes rejected before dispatch use `ble_service.c:269-279`'s separate
   counter. `CommandAckTracker.kt:111-120` treats any delta > 1 as dropped acks
   and abandons that many waiters. The app never sends a malformed write itself,
   so this is latent — but the app's gap-detection assumes a contract the
   firmware has stopped honouring, and the fix belongs on whichever side unifies
   first.
3. **`LedMatrix::configure` gained a `MAX_TOTAL_LEDS = 1000` ceiling and now
   rejects rather than clamps** (`led_matrix.h:26`, `led_matrix.cpp:36-53`,
   returning `bool`). `SystemCommands.setLedMatrix` (`:10`) bounds only the ring
   *count* (`maxRings = 20`) and never the summed total — 20 rings of up to 255
   LEDs is 5100, five times the ceiling — so an oversized layout is accepted
   locally and rejected on the device. Its KDoc (`:6-7`) still says "The firmware
   clamps `num_rings` to `MAX_LED_RINGS`", which was true before `f6147b1` and is
   not now: a rejected `configure` leaves the previous layout in force, so the
   app's optimistic state and the device diverge with only an FF09 code to say so.
4. **`partitions_4mb.csv` (added for the S3) gives ~1.98 MB OTA slots**, while
   `Protocol.kt:197` hard-codes `OTA_SLOT_BYTES = 960 * 1024` and enforces it at
   `FirmwareUpdateViewModel.kt:219` before the device is consulted. On an S3 tail
   the app refuses an image the device would accept. There is no FF06 field
   carrying the real slot size — worth raising with the firmware side.

### Protocol gaps — features the firmware has and the app cannot reach

No wire *disagreement* was found. These are missing capabilities:

- **FF0D parameter descriptors are entirely absent.** No UUID, no parser, no
  `SCMD_SELECT_DESCRIPTORS (0x07)` builder — verified: zero matches for `FF0D` in
  `app/src/main`. Every parameter name, unit, range and default is hard-coded in
  `model/LedModels.kt` / `model/MotionModels.kt`, which is precisely the coupling
  FF0D exists to remove, and it goes stale the moment firmware adds a parameter.
  **This is also why the `BeatPulseRenderer` divergence above is reachable.** The
  firmware caps `downbeat` at `0.0..1.0` in its FF0D `ParamDescriptorSet`, and
  that descriptor is the *only* guard — `set_param` itself does not clamp. Since
  the app never reads the descriptors, and `LedCommands.setEffectParam` sends the
  raw float unclamped, the range contract the firmware advertises is enforced
  nowhere on either side. Parsing FF0D and clamping writes to the declared range
  would close that hole and retire the hardcoded control ranges in the LED config
  UI at the same time.
- **Five FF01 commands have no builder:** `MCMD_SET_MOTOR_SCALE (0x0A)` and
  `MCMD_SET_GENTLE_SCALE (0x0B)` — both *parsed and displayed* from the FF06
  tuning block (`SystemInfoParser.kt:141-142`) and unwritable —
  `MCMD_SET_TAP_CONFIG (0x14)`, `MCMD_SET_AXIS_MIX (0x15)` (a diagonal-geometry
  tail runs the identity mix forever) and `MCMD_SET_ENCODER_CFG (0x16)` (encoder
  assist can never be enabled, so FF02's encoder fields stay dead-reckoned).
  Note `VirtualTailTransport.kt:965` accepts `0x01..0x16` — the simulator honours
  commands the app cannot build.
- **Four FF03 commands have no builder:** `LCMD_SET_FRAME_RATE (0x0C)` and the
  animation upload trio `0x0D/0x0E/0x0F`. Consequence: `EFFECT_ANIMATION (0x11)`
  is selectable and advertised in capabilities but has no upload path, so every
  slot is empty and the effect renders black.
- **`RESULT_OTA_VERIFY_PENDING (0x0B)` is not in the result enum.**
  `Protocol.kt:215-233` stops at `0x0A`, so the one rejection whose remedy is
  "wait ~10 s and retry" renders as "Unrecognised result code" — and
  `isRetryable` (`:236`) returns false for a code the firmware explicitly marks
  retryable. This hits every user who updates firmware twice in a row.
- **The FF06 OTA `flags` byte is read as a boolean** (`SystemInfoParser.kt:159`,
  `block.u8() != 0`). Firmware packs bit 0 `pending_verify` and bit 1
  `running_version_unknown` (`ota_manager.cpp:341`). Mask bit 0; bit 1 is
  currently discarded, so the app cannot tell the user a displayed 0.0.0 is a
  placeholder.
- **Eight FF07 driver-health events (0x05-0x0C) are dropped silently** — the
  TMC2209 overtemp / short / open-load pairs, emitted even while disconnected,
  which is what the readable ring is for. `SystemEventParser.parseLog` uses
  `mapNotNull`, so unknown codes vanish. Partially mitigated: `DiagnosticsParser`
  reads the per-motor fault masks from FF0C, so the *state* is visible; the
  *edge* is lost.
- **FF06 blocks `0x05` (tap) and `0x06` (axis mix) are skipped by length.** This
  is the v6 framing working exactly as designed, not a bug — but it pairs with
  the missing `0x14`/`0x15` writers above.

---

## Documentation

**Would mislead into broken code:**

- **FF02 is documented as 77 bytes; the payload is 97.** `CLAUDE.md:166`,
  `docs/DevelopmentPlan.md:46,262`. Firmware: `ble_protocol.h:567`
  (`MOTION_STATE_SIZE ... // 97 bytes`). The *code* is right —
  `Protocol.kt:118,124,133` models 77/81/97 and `MotionStateParser.kt:29,49`
  gates each appended block. 77 is the v5 minimum, not the payload; anyone
  sizing a buffer from the table truncates the behavior and logical-position
  blocks. (`testutil/FirmwarePayloads.kt:73`'s comment repeats it.)
- **`docs/AppSpecifications.md:62-66` describes FF0D descriptors as a working app
  capability** and `docs/roadmap.md:16,286` marks `SYS-9 descriptors` **done**.
  There is no FF0D code in the app.
- **`docs/beat-model.md:10-11` says "The CRNN is switched on."** It is compiled
  out: `di/AppContainer.kt:184` `USE_CRNN_BEAT_ACTIVATION = false`, and `:123`
  nulls the model store, so an installed model is never consulted.
  `docs/beatlight.md:186` states the opposite, correctly — the two docs directly
  contradict each other.
- **`docs/genre-model.md:70,120-121` documents `GenreDebouncer` and
  `EffectControllerConfig.minGenreConfidence (0.35)`.** Neither class exists;
  they survive only as aspirational comments. The real default is
  `minConfidence = 0f` (`OnnxGenreClassifier.kt:274`) and `AppContainer.kt:98`
  doesn't pass it, so the effective gate is 0. `EffectProfile` was explicitly
  removed (`composer/Composition.kt:9`).

**Wrong facts:** effect and stack counts in four places (`roadmap.md:93` "20
reactive effects" → 25; `composer.md:245` and `beatlight-manual-checks.md:134`
"six built-in stacks" → 7; `beatlight.md:111` "six effects from
`led/effects/`" → 18) · `roadmap.md:19` "ten motion patterns" → 11 (ids 0x00-0x0A including
`PATTERN_KEYFRAME`) · `FirmwareExport.kt:16`'s comment says the device has
seventeen effects → 18 · `beatlight.md:90` "Transient | ~5-10 Hz" → a fixed
`statsRateHz = 10f` · `roadmap.md:193` promises a `tap_spark` effect that does
not exist.

**CLAUDE.md has fallen behind four shipped screens.** It lists 7 viewmodels
(there are 11 — `BehaviorConfig`, `Diagnostics`, `FirmwareUpdate`,
`KeyframeEditor`), 5 config routes (`NavRoutes.kt:26-37` also defines
`keyframes`, `behavior`, `firmware`, `diagnostics`), one file under `repository/`
(there are 5) and five under `model/` (there are 9). It omits FF0D from the
characteristic table, describes FF07 as 4 events (the app decodes 8, the firmware
defines 16), and never mentions the standard SIG Battery and Device Information
services the app implements (`CharacteristicUuids.kt:58-65`). `AppSpecifications.md`
and `README.md` *do* cover these — CLAUDE.md is the outlier. Its Testing section
also omits `VirtualTailTransport`, `RoutingBleTransport`, the conformance-vector
replay, `testutil/BeatReference.kt` and `testutil/FakeSharedPreferences.kt`.

**The composition handoff is documented as weaker than it is.** `CLAUDE.md:122`
says `setComposition` "parks it in a `@Volatile` and swaps it in on the render
thread", and `CompositionScene.kt:36`'s own header comment repeats it — but the
implementation uses `AtomicReference` with a `getAndSet` drain at frame start
(`:59-66`), and the KDoc immediately below at `:61` explains *why*: "A plain
`@Volatile` field read-then-nulled would lose an edit that landed" mid-frame. The
code is correct and stronger than both descriptions of it; two comments describe
a design it deliberately moved past. Worth fixing precisely because the weaker
description is the one someone would preserve during a refactor.

**Undocumented code:** `lighting/CompositeLightingOutput.kt`, `drop/RollingStats.kt`.
`docs/composer.md:303-319` names 4 test files where there are 12.

**Stale wording:** `roadmap.md:92` "None of this is computable on the ESP32-C3"
(the firmware now builds both targets) · `README.md:235` "license file if
present" (it exists) · `README.md:186` points at a `docs/` architecture doc that
does not exist and omits two that do · `beatlight.md:62-63` "the same
`AdaptivePeakNormalizer`" is true at class level only — three sites each build
their own. On the firmware side, `docs/companion-app-improvements.md:11-13,149`
still says "protocol version bumped to 1" and lists battery/diagnostics as open;
both shipped on both sides.

---

## Verified clean — worth not regressing

- **Wire encoding/decoding**, byte-by-byte: FF02 (incl. the v6 `axis*2+half`
  motor order), the FF06 preamble and framed-block walk, FF04, FF07, FF08, FF09,
  FF0B, FF0C (at `DIAGNOSTICS_VERSION 3`, which the app already handles across
  v1/v2/v3), FF0E, the behavior records (`static_assert`ed on the firmware side),
  the keyframe blob, CRC-32, and every catalogue constant. `FirmwarePayloads.kt`
  still mirrors `app_update_ble_state` faithfully, v6 framing included.
- **`led/` core port**: `GAMMA8` diffed at all 256 entries, zero differences;
  `ColorMath`, `Palettes`, `Noise` (including `ushr` for C's unsigned shift and
  the deliberate non-`floor` lattice index), `LayerCompositor`, `LedLayout` and
  `LedOutputStage`'s *internal* arithmetic all faithful. All 18 effect pairs were
  diffed line by line: 10 are port-exact throughout, 5 more differ only on the
  palette-id line noted in P2, and the remaining 3 are the `BeatPulse` clamp, the
  `Image` length check and the deliberately-empty `Animation`. `LedCatalogParityTest`
  asserts firmware-derived pixels for 10 of them.
- **`dsp/`**: `Fft.kt`, `BluesteinFft.kt` (the `n² mod 2N` precision bug is *not*
  present — the `Long` arithmetic is exact), `LogFilterbank.kt`, `Resampler.kt`.
- **Effect determinism**: no `Random`, `currentTimeMillis`, `nanoTime` or
  `SystemClock` anywhere under `composer/` — all randomness is a pure integer
  hash and all time comes from `ReactiveContext`. No effect can escape 0..255; dt
  handling is correct throughout; degenerate 0- and 1-LED strips are guarded.
- **Particle filter**: every degeneracy path handled; NaN cannot enter the
  weights by construction; no Kotlin remainder-vs-modulo trap in the beat tier.
- **Transport**: connection setup does run in its own job and a disconnect during
  setup reaches the state flow; lock ordering is consistent with no deadlock
  path; every mutex-guarded op has a 5 s timeout; `MAX_OUTSTANDING` bounds waiter
  growth; the FF0A keepalive-vs-timeout relationship is enforced by a constructor
  `require`.
- **Presentation**: `collectAsStateWithLifecycle` is used *exclusively* — not one
  `collectAsState()` in the app. No ViewModel holds a `Context` or `Activity`.
  `BeatLightService` is foreground-service compliant with a bounded wake lock.
- **Repo hygiene**: no committed secrets, keystores, `local.properties`, models
  or build output; the wrapper is tracked correctly; the documented version
  matrix matches the build files exactly and is a supported combination.

---

## Method and confidence

Six reviewers worked in parallel from scoped briefs, each instructed to verify
against cited lines rather than trust comments or prose, and to separate "app
missing a firmware feature" from "app disagrees with firmware." The signal-chain
reviewer further delegated four sub-passes — the 18 LED effect pairs, the
composer/effects/lighting packages, audio+JNI, and beat/drop/genre — so ten
reviewers contributed in total. Those four reported independently; the parent
believed they had failed and filed a coverage caveat saying `audio/`, `cpp/`,
`beat/`, `drop/` and `genre/` were unaudited and the `ImageRenderer` question
open. That caveat is void: all four returned complete results, which are folded
in above, and the `ImageRenderer` divergence is P1-7.

**Convergence, where it happened, is meaningful.** The manifest permission
defects were found independently by the transport and presentation reviewers
from opposite ends. Four items — the FF02 size, FF0D's absence,
`RESULT_OTA_VERIFY_PENDING`, and the OTA flags byte — were found independently by
the protocol and documentation reviewers.

**Re-verified by hand before landing here:** that `LedOutputStage` appears
nowhere in `app/src/main` except its own declaration; that
`LedStackRenderer.kt:76` omits the `opacity` argument against a 4-parameter
`Layer`; that `Protocol.kt` stops at `0x0A`; that `FF0D` has zero matches in main
sources; that `POST_NOTIFICATIONS` has zero matches anywhere; and the manifest
permission block in full. The test and lint numbers were produced with
`--rerun-tasks` after a stale cache was found and discarded.

**Two corrections were made to the briefs during the pass**, both from reviewers
pushing back on my framing rather than accepting it: FF02 is 97 bytes, not the 77
the app's own CLAUDE.md states, and FF0C diagnostics is at version 3, not the
version 2 I briefed — the app already handles all three versions correctly.

**Not covered:** nothing was reproduced on hardware or on a real Android device,
so the P0 permission findings are derived from the platform contract and the
manifest, not from an observed failure — they are the first thing to confirm on
a phone. No instrumented or Robolectric testing exists to fall back on. `assembleDebug`
was not run, so the NDK toolchain and the 16 KB alignment question (finding 40)
are unverified empirically. R8 has never run against this codebase, so finding
38's "the keep rules are probably adequate" is reasoning, not evidence. The ONNX
model files themselves were not inspected, and the genre/beat model behaviour was
reasoned about from the code around it rather than by running inference. The
firmware findings referenced here are taken from its own audit and the diff of
`f6147b1`; they were not independently re-derived.
