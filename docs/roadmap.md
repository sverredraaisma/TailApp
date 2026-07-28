# TailApp ⇄ TailFirmware — feature report & aligned roadmaps

> ## Delivery status
>
> **All six milestones are complete on both sides**, and the protocol has had its
> one announced breaking cleanup (v6). Everything below is the plan as written;
> the [milestone table](#5-joint-milestones) carries the live status.
>
> | Milestone | State |
> |---|---|
> | M1 — Honest tethered control | **done** |
> | M2 — A body the effects can feel | **done** |
> | M3 — One mic, one beat | **done** |
> | M4 — Looks that survive the phone leaving | **done** — LED-1/2/3/4/7, the app parity mirror + native export (A3-1..3) |
> | M5 — Motion that dances | **done** — MOT-0 mixer, MOT-1 encoder assist, MOT-5 catalogue, MOT-6 behavior engine, MOT-7 crossfade, MOT-8 keyframes, MOT-9 fusion, MOT-11 streaming; app A4-1..4 |
> | M6 — A shippable device | **done** — SYS-1 battery, SYS-2 OTA, SYS-3 diagnostics, SYS-6 DIS/name/bonds, MOT-3 driver health; app A5-1..3. **SYS-9 descriptors: firmware-complete, app-side protocol only** — see below |
>
> The device is no longer dependent on a paired phone for anything beyond a
> rainbow: it has eighteen LED effects (`0x00`-`0x11`) and eleven motion patterns
> (`0x00`-`0x0A`, ending in `PATTERN_KEYFRAME`) of its own, a mood
> state machine, and three tail-reactive effects that light it from its own
> movement. What the phone still uniquely provides is the microphone — hence the
> FF05 beat trailer, which lets the device's own audio wag and animations lock to
> a beat it cannot hear.
>
> **SYS-9 is the one item this table overstates.** The firmware publishes
> parameter descriptors on FF0D and the app parses them —
> `ParamDescriptorParser`, `model/ParamModels`, `SystemCommands.selectDescriptors`
> and `CharacteristicUuids.PARAM_DESC`, covered by `ParamDescriptorParserTest`.
> Nothing in `repository/`, `viewmodel/` or `ui/` consumes them, so the point of
> the item — a pattern this build was not written for still getting usable
> controls — is not delivered. The remaining work is a repository flow and a
> fallback in the parameter editors; no wire work is left.
>
> **The protocol reached v6 in three announced steps.** v4/v5 were bundled into
> one version (nothing shipped between them). **v6 (SYS-8 / A5-3)** is the one
> planned break, released on both repos together: it retired the vestigial PID
> (the motors have been open-loop steppers since `d4973bf`) and made the FF06 read
> self-describing — each trailing block now carries a `[tag][len]` prefix, so a
> reader skips a block it does not know rather than mis-reading the bytes of
> whatever used to follow it. That ends the positional drift that produced a
> recurring class of bug: an appended block silently shifting every offset behind
> it. A v5 device is surfaced as an unsupported-version banner, not parsed on a
> best-effort basis.
>
> **Two guards keep the "green tests, dead on device" failure from recurring.**
> The host build fakes the hardware seams, so a source absent from the firmware
> build, or a function defined only in a fake, can pass every test while never
> running on the device — which happened repeatedly. `check_sources_registered.sh`
> catches the first; `check_ble_service_defined.sh` the second; both run in CI. The
> QA-5 conformance vectors, exported from the firmware's own dispatch path and
> replayed against the app's simulator, catch the two implementations drifting
> apart on the wire.
>
> **Report dated** 2026-07-24; **delivery completed** 2026-07-25 (protocol v6).
> **Scope:** the app's implemented feature surface (this repo), the firmware's
> implemented surface and its 2026-07-24 design review
> (`TailFirmware/docs/design-review-and-roadmap.md`), and the alignment between the two.
>
> This document supersedes `docs/DevelopmentPlan.md` for forward planning — that plan's
> twelve phases are all implemented; it remains as a historical record.
>
> The firmware's side of this plan lives in **`TailFirmware/docs/roadmap.md`**, which was
> revised together with this document. Both end in the same
> [joint milestones](#5-joint-milestones) table; that table is the contract.

---

## 1. Where the two projects stand

### 1.1 The wire surface: who implements what

> **Resolved.** This section described the state at the time of the review: the
> app spoke **v3** while the firmware shipped **v4**, so a current device tripped
> the compatibility banner on every connect, and five v4 features were
> firmware-only. Worst of them, `SYS_EVENT_STALL` was *silently dropped* — a
> stall latches every motor off, and the user's only clue was a tail that
> stopped. All of it is closed; both sides now speak **v6**. Kept as the record
> of what the drift cost, because it is the standing failure mode of a
> two-repository protocol and will happen again.

| Protocol v4 feature | Status at review | Now |
|---|---|---|
| `SYS_EVENT_STALL` (`0x04`) on FF07 | Silently dropped by `SystemEvent.fromCode` | Persistent banner + re-enable action |
| `MCMD_ENABLE_MOTORS` (`0x09`) | No builder, no UI | Builder, repository method, recovery button |
| `MCMD_SET_MOTION_LIMITS` (`0x08`) | No builder, no UI | Per-motor velocity/accel/jerk/StallGuard editor |
| FF06 motion block | Not parsed | Parsed; absent block stays distinguishable from "motors off" |
| PID marked vestigial | Presented as first-class controls | Collapsed into a "legacy" section |

The v5 additions on top — the FF09 sequence byte and readable result, the
readable FF07 ring, `RESULT_BUSY`, the FF05 beat trailer, per-layer opacity, the
output stage, and FF0B motion targets — landed on both sides together, which is
the point: the drift above happened because one side moved alone.

### 1.2 What the app has that the firmware plan barely accounts for

The firmware's design review plans LED/motion features as if the device were the only
renderer. The app has meanwhile built an entire second half:

- **BeatLight** — on-phone beat/downbeat/tempo tracking (DSP default, optional BeatNet
  CRNN), drop and build-up detection, section state, genre classification
  (Discogs-EffNet, optional install). None of this is computable on either of the
  firmware's targets — an ONNX EfficientNet and a CRNN are out of reach on a
  160 MHz RISC-V C3, and the S3 the firmware also builds for does not change that.
- **The composer** — a layered, folder-nested effect graph of **25 reactive effects**,
  all reading one per-frame `ReactiveContext`, rendered on the phone and streamed to
  the tail over **FF0A direct drive**. Includes per-layer opacity — a blend capability
  the firmware compositor doesn't have (its plan calls it LED-2).
- **A firmware-parity LED engine port** (`com.tailapp.led`) — pixel-exact preview of
  the device's *own* effect stack, integer truncation included.
- **A protocol simulator** (`VirtualTailTransport`) — the app runs against an
  in-process virtual tail with no radio; the firmware's QA-5 plans a host-side
  simulator that partially duplicates this.

Consequence for planning: **the device has no microphone** — its three audio effects
only ever see what the phone streams on FF05. So firmware-side *audio*-reactive
features compete with the app's composer (which analyses the same audio at far higher
resolution and streams finished pixels), while firmware-side *standalone* and
*tail-reactive* features (things that must work with the phone gone, or that read the
IMUs/motors directly) do not compete with anything. The two roadmaps below divide the
work along exactly that line.

### 1.3 Device data the app receives but never lets effects see

> **Resolved by milestone M2.** Taps and the motion state now reach
> `ReactiveContext`, and five effects read them. Kept because the *shape* of the
> gap is worth remembering: every one of these was already parsed and displayed,
> and stopped one layer short of being useful.

The composer's design principle is "there is no such thing as an effect that cannot
react" — yet the device's own body was invisible to it:

| Data | Arrives as | Today | Missing |
|---|---|---|---|
| Tap events (base/tip) | FF07 notify | Snackbar on the overview screen | Not in `ReactiveContext`; no effect can fire on a physical tap |
| Gravity vector | FF02 @ ~20 Hz | Read-only display card | Not in `ReactiveContext` |
| Motor positions (4) | FF02 @ ~20 Hz | Read-only display card | Not in `ReactiveContext`; no wag-speed / deflection input |
| `maxServos`, `maxImus` | FF06 capabilities | Parsed, unused by any screen | UI hard-codes 4/2 |

This is the highest-leverage gap in the app: the plumbing (parse → repository flow)
already exists for all of it, and the composer's registry-wide tests mean each new
effect is covered the day it lands.

### 1.4 Duplications worth resolving

> **Resolved by M3 and M4.** One capture now feeds both consumers, and a
> composer look can be installed onto the device's own layer stack.

- **Two mic captures, mutually exclusive.** The FF05 visualiser stream
  (`FftStreamManager`) and BeatLight (`LightingEngine`) each open their own capture;
  `BeatLightSession` stops the FF05 stream when it starts. One shared front-end could
  serve both simultaneously — and carry beat data to the device (§A2).
- **Two render paths for "audio-reactive lighting".** The firmware's
  Audio Power/Bar/FreqBars stack (fed by FF05) and the app's composer (fed by FF0A)
  do overlapping jobs. Keep both — the firmware path costs the phone almost nothing
  (66-byte frames vs a 30 fps pixel stream) and survives app backgrounding — but stop
  growing the firmware's audio-effect catalog; grow its *standalone* catalog instead.
- **Compositions die with the connection.** A composer look exists only while the
  phone streams. There is no way to "install" even an approximation of it onto the
  device for phone-free wear (§A3).

---

## 2. Division of labour

The principle both roadmaps follow:

| | Owns | Because |
|---|---|---|
| **Phone (app)** | Audio analysis, beat/drop/genre, authoring (composer, future keyframes), high-rate rendering (FF0A), streaming inputs (FFT + beat trailer, future motion targets) | Compute, storage, UI, microphone all live here |
| **Device (firmware)** | Standalone behavior (ambient + tail-reactive effects, motion patterns, behavior engine), safety (stall latch, power limiter, limits), identity (profiles, config, OTA) | Must work with the phone in a pocket, dead, or absent |
| **Wire (protocol)** | Versioned, additive between bumps, breaking changes bundled into v5 | One consumer, one producer, two repos — drift is the standing failure mode (v3/v4 proves it) |

Every subsystem should offer the same pair of modes with the same takeover semantics
direct LED mode already has: **device-autonomous** (compositor, patterns, behavior
engine) and **app-driven** (FF0A pixels, future motion-target streaming), with
timeout/disconnect fallback from the second to the first.

---

## 3. App roadmap

Effort: **S** ≤ ½ day, **M** ≤ 3 days, **L** = a week+. "Needs FW" names items from
`TailFirmware/docs/roadmap.md`; unmarked items work against today's firmware.

### Phase A0 — Protocol v4 catch-up *(do first; all small, all app-only)*

| ID | Item | Effort |
|----|------|--------|
| A0-1 | Bump `SUPPORTED_PROTOCOL_VERSION` 3→4. Extend `testutil/FirmwarePayloads` first (wire-format changes start there), then parse the FF06 v4 motion block — `motors_enabled` + per-motor max vel/accel/jerk/StallGuard threshold — into `SystemInfo`. | S |
| A0-2 | Add `SYS_EVENT_STALL` to `SystemEvent` and `MCMD_ENABLE_MOTORS` to `MotionCommands`. Surface a stall as a persistent banner (overview + BeatLight screens, not just a snackbar) with a "Re-enable motors" action; show `motors_enabled` in the servo status card. | S |
| A0-3 | Motion-limits editor on `MotionConfigScreen` (`MCMD_SET_MOTION_LIMITS`: per-motor velocity/accel/jerk sliders + stall-threshold). Demote the PID controls into a collapsed "legacy" section — vestigial in v4, deleted in v5. | S |
| A0-4 | Doc refresh: `CLAUDE.md` protocol section to v4 (stall, enable-motors, motion limits, motion block, PID vestigial); mark `docs/DevelopmentPlan.md` as historical. | S |

### Phase A1 — The tail's body enters the composer *(the headline feature)*

Everything the composer knows comes from the microphone; this phase adds the device
itself as an input. All of it runs against current firmware — the firmware items named
below only improve quality (HARD-9 fixes the shared tap edge-detector, SYS-7 stops
missed events, MOT-10 upgrades tap detection to hardware).

| ID | Item | Effort |
|----|------|--------|
| A1-1 | Tap events into the render pipeline: repository tap flow → `BeatLightSession` → a thread-safe inbox on `LightingEngine` (same hand-off discipline as `setComposition` — an `AtomicReference` drained with `getAndSet` at frame start, since a `@Volatile` read-then-null loses an edit landing mid-frame) → `ReactiveContext` gains `lastTapEnd` (BASE/TIP/none), `secondsSinceTap`, `tapCount`. `ComposerTestSupport` defaults to "never tapped". | M |
| A1-2 | Tap effects: `tap_ripple` (one-shot ripple from the tapped end — base spawns at y=0, tip at y=1) and the modulator `tap_gate` (opens on tap, decays). One class + one registry row each; `ReactiveEffectsTest` covers them for free. *(This item also listed a `tap_spark`; it was never built and is not in `ReactiveEffects.ALL` — `sparkle` on a tap-gated folder covers it without a second effect.)* | S each |
| A1-3 | Motion telemetry into `ReactiveContext`: gravity vector and the four motor positions from FF02 (~20 Hz, latest-snapshot into the render thread), smoothed; derived `tailDeflection` (x, y) and `wagSpeed`. Defaults: at rest, gravity straight down. | M |
| A1-4 | Tail-reactive effects — the app-side render of the firmware catalog's "reactive to the tail itself" group: `motion_glow` (brightness from `wagSpeed`), `wag_trail` (comet driven by live X deflection), `gravity_level` (the downhill side of each ring lights up). | S each |
| A1-5 | BeatLight monitor additions: tap indicator, live deflection widget. | S |

### Phase A2 — One microphone, one analysis

| ID | Item | Needs FW | Effort |
|----|------|----------|--------|
| A2-1 | Derive FF05 frames (loudness + 64 bins) from the BeatLight `FeatureExtractor`'s STFT instead of a second capture, so the firmware's audio effects and BeatLight run **simultaneously** and the mutually-exclusive mic rule disappears. Re-map `AudioConfig` settings onto the derivation. | — | M |
| A2-2 | **Beat trailer on FF05**: append `[beat_phase: u8][bpm: u8][flags: u8 — beat/downbeat/drop]` to each frame. Verified additive: the firmware's FF05 parse reads exactly `num_bins` bytes and ignores the rest, so old firmware is unaffected. With LED-8 the device's effects and motion patterns get a real beat for 3 bytes/frame. | LED-8 | S |

### Phase A3 — Compositions that outlive the connection

| ID | Item | Needs FW | Effort |
|----|------|----------|--------|
| A3-1 | **"Install on tail"**: map export-compatible composition subtrees onto a firmware FF03 layer stack and save to a profile slot — `solid`→Static Color, `rainbow`→Rainbow, `spectrum_bars`→Audio Freq Bars, `vu_meter`→Audio Bar, `bass_pulse`/`breathe`→Audio Power approximations. Report unmappable layers honestly instead of silently dropping them. | HARD-2/3 (so the saved stack survives reboot) | M |
| A3-2 | Grow the mappable set as the firmware LED track lands (opacity, palettes, ambient effects): mirror each new firmware effect in `com.tailapp.led` **with parity tests**, add its app-side param metadata, extend the export mapping. This is the standing tax of §1.2's parity port — budget it per firmware batch, not per release. | LED-2/3/4 | M per batch |
| A3-3 | Composition file export/import (JSON via share sheet) — library backup, look-swapping between phones. | — | S |

### Phase A4 — Motion choreography

| ID | Item | Needs FW | Effort |
|----|------|----------|--------|
| A4-1 | Live tail-position visualizer (2-D X/Y plot from FF02; logical *and* physical once the axis mixer reports both). | (MOT-0 for dual reporting) | S |
| A4-2 | **Motion streaming ("puppet")**: drive axis targets from the phone — joystick / phone-tilt, and composer-driven motion (a beat-locked wag, a drop flick) rendered from the same `ReactiveContext` that drives the pixels. The tail dances to the same analysis as the lights. | MOT-11 | L |
| A4-3 | Keyframe sequence editor + chunked, CRC'd upload (reuse the image-upload transfer pattern). | MOT-8 | M |
| A4-4 | Behavior-engine configuration UI (states, transitions, triggers). | MOT-6 | M |

### Phase A5 — Ops & lifecycle

| ID | Item | Needs FW | Effort |
|----|------|----------|--------|
| A5-1 | OTA/DFU flow: pick or bundle a firmware image, chunked transfer with progress, version/rollback state from FF06. | SYS-2 | M |
| A5-2 | Battery card + low-power messaging; diagnostics screen (uptime, heap, stall count, loop overruns); device rename. | SYS-1/3/6 | S–M each |
| A5-3 | **Protocol v5 migration, one paired release**: delete the PID UI, adopt the axis-mixer limit semantics, FF09 sequence-byte correlation in `DeviceRepository` (finally distinguishes two identical in-flight commands), FF02 motor-order per the mapping table. | SYS-8 | M |

### Phase A6 — Hygiene *(anytime, independent)*

| ID | Item | Effort |
|----|------|--------|
| A6-1 | Drive servo/IMU UI counts from `Capabilities.maxServos`/`maxImus` instead of hard-coded 4/2. | S |
| A6-2 | Consume FF04 change-notifies when firmware LED-7 lands — live reconcile instead of read-after-write. | S |
| A6-3 | Direct-mode liveness pairing: keep the FF0A keepalive interval (today 1 s) below the firmware's stale-frame timeout (planned 2 s); document the pairing in `composer.md`. | S |

---

## 4. Firmware roadmap — what this plan asked to change

The firmware roadmap (`TailFirmware/docs/roadmap.md`) is the design review's §4 with
these app-driven changes — see that file for the full item tables:

1. **Added MOT-11** — live motion-target streaming ("puppet"), promoted from the idea
   catalog to a numbered item: it is the app's A4-2 counterpart and the single biggest
   joint feature.
2. **Added LED-8** — accept the FF05 beat trailer (A2-2) and expose beat/downbeat/drop
   to effects and patterns. Three bytes per frame buy what an on-device beat detector
   never could; the §5.3 "beat/onset detector" enabler is demoted to standalone-only.
3. **Added SYS-9** — per-pattern/per-effect parameter descriptors in the capabilities
   block, so future firmware features degrade gracefully in an older app instead of
   being invisible (today all names/ranges are hard-coded app-side; every firmware
   addition strands old app releases).
4. **Re-scoped LED-3** — the new-effects catalog is split: standalone/ambient and
   tail-reactive effects stay firmware-first (they're what the device does alone);
   new *audio*-reactive firmware effects are dropped from the plan (the device has no
   mic; tethered, the composer over FF0A is strictly more capable — the existing three
   plus LED-8 beat-awareness cover the low-power niche).
5. **Raised SYS-7** (FF07 event latch/ring) into the early sequence — once app effects
   consume taps (A1), a dropped notify is a missed ripple, not a missed snackbar.
6. **MOT-6 arbitration note** — the behavior engine must yield to app streaming with
   the same takeover/fallback semantics direct LED mode has.
7. **QA-5 deprioritized** — the app already ships `VirtualTailTransport`; the
   remaining value is firmware-side ground truth (conformance vectors the app's
   simulator can replay), not a second simulator.

Phase 0 (hardening) is untouched: **HARD-1 through HARD-5 remain the prerequisite for
everything**, and two of them (HARD-2/3 — sane defaults, param-default sync) are what
make A3-1's "install on tail" produce a stack that still works after a power cycle.

---

## 5. Joint milestones

Each milestone is shippable and demonstrable on its own. Firmware Phase 0 hardening
runs before/alongside M2 and must be complete before M4.

| Milestone | Meaning | App items | Firmware items | State |
|---|---|---|---|---|
| **M1 — Honest tethered control** | The app tells the truth about the device: stalls visible and recoverable, motion limits editable, no false banner | A0-1..4 | — (shipped) | **done** |
| **M2 — A body the effects can feel** | Tap the tail and the lights ripple; wag it and they glow | A1-1..5 | HARD-9, SYS-7 | **done** (MOT-10 still later) |
| **M3 — One mic, one beat** | BeatLight and the firmware's own effects run at once, and the device knows the beat | A2-1, A2-2 | LED-8, M2-fix (loudness wiring) | **done** |
| **M4 — Looks that survive the phone leaving** | A composer look (or its honest approximation) installs to a profile and works standalone | A3-1..3 | HARD-2/3, LED-1, LED-2, LED-3, LED-4, LED-7 | **done** — the ten-effect catalogue, the palettes, the app's parity mirror, and eight effects now exporting natively instead of degrading |
| **M5 — Motion that dances** | The tail moves to the same analysis as the lights: streamed targets, keyframes, behavior engine | A4-1..4 | MOT-0, MOT-7, MOT-11, MOT-8, MOT-6 | **done** — mixer, encoder assist, catalogue, behavior engine, crossfade, keyframes, fusion and streaming; app visualizer, manual pad, keyframe editor and behavior UI |
| **M6 — A shippable device** | OTA, battery, diagnostics, and one clean protocol break | A5-1..3 | SYS-1, SYS-2, SYS-3, SYS-6, SYS-8, SYS-9 | **done** — battery, OTA, diagnostics, DIS/name/bonds and driver health; protocol v6 (SYS-8/A5-3) is the one announced break, released on both repos together. SYS-9 descriptors are parsed but not yet consumed — see the delivery-status note |

### What M4 delivered, and what it did not

The *mechanism* is complete: `FirmwareExport` maps a composition onto the
device's own layer stack and saves it to a profile, reporting by name every
layer that could not come along. The firmware gained per-layer opacity, a Normal
blend mode, master brightness, gamma and a power limiter, and FF04 now notifies
on change.

What is outstanding is **catalogue**, not capability: the palette table (LED-4)
and the ambient/tail-reactive firmware effects (LED-3) would make an installed
look worth more, but nothing about the install path depends on them. A3-2 (the
standing parity tax) and A3-3 (JSON export/import) are likewise additive.

The order is deliberate: M1 is a day of catch-up; M2–M3 are almost entirely app-side
and make the *current* hardware feel alive; M4 needs the firmware's hardening phase
done; M5 rides on the axis-mixer/mechanical revision timeline; M6 bundles every
breaking change into one announced v5 release on both sides.
