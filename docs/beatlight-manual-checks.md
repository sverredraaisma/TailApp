# BeatLight — checks that need a human

Everything that can be verified without hands has been: the JVM suite covers the
ring buffer, the front-end, the beat tracker, the drop detector, the LED engine,
the effect controller and the whole pipeline end to end on synthetic audio, and
`lintDebug` is clean.

What follows cannot be. Each item needs a real phone, real ears, and — for most
of them — the real tail. They are grouped by what they would tell us, and each
says what to do if it fails.

## 1. It runs at all

- [ ] `gradlew.bat installDebug` succeeds on the phone. First run downloads the
      NDK, so allow several minutes.
- [ ] App launches; the microphone permission prompt appears when the BeatLight
      screen asks for it, and denying it produces a readable error rather than a
      dead screen.
- [ ] Scan, connect and the existing motion/LED/audio screens still behave as
      they did — none of this work should have touched them.

## 2. Capture is actually low-latency

- [ ] With the session running, `adb logcat -s OboeCapture` reports the stream
      opened in **exclusive** mode, and the BeatLight screen's reported input
      latency is in the tens of milliseconds, not hundreds.
- [ ] If it reports the `AudioRecord` fallback instead, note the device model —
      that is a supported path, but it costs latency and is worth knowing about.
- [ ] The dropped-sample counter stays at zero through a few minutes of playback.
      A climbing counter means the analysis loop is not keeping up.

## 3. Beat tracking feels right, not just measures right

The synthetic grids say ±1 BPM and >90% of beats inside 70 ms. Real music is the
question the tests cannot answer.

- [ ] Play a track whose BPM you know. The reported BPM settles within a few
      seconds and stays there.
- [ ] The beat indicator on the monitor **feels** locked by ear, not merely close.
      Watch for the classic failure: locked half a beat off, so it pulses on the
      off-beat. The tracker has a phase-competition step for exactly this — if it
      still happens, that is a real finding.
- [ ] Downbeats land on bar one, not on beat three. Four-to-the-floor material is
      the hardest case here because every beat carries a kick.
- [ ] Try something sparse — half-time, a breakdown, something with a swung feel.
      Note *how* it fails, not just that it does.

### 3a. Which decoder — the one comparison the tests cannot settle

Both decoders are shipped and switchable in the calibration card, because measured
head to head neither wins outright:

| | Phase-locked | Particle filter |
|---|---|---|
| Tempo precision | ≤0.5 BPM | up to ~2 BPM |
| Steady material | identical, often beat for beat | identical |
| Sparse material | never drops a beat | drops ~8% |
| Heavy syncopation | **emits nothing at all** | tracks it |
| Cost | negligible | ~100×, still <1% of budget |

The synthetic signals say that much. What they cannot say is which failure you
actually notice on your music.

- [ ] Play the same track on both, back to back, and say which felt better locked.
- [ ] Find something heavily syncopated (strong off-beats, broken kick patterns).
      The phase-locked decoder is expected to go dark on it — its confidence gate
      never opens. Confirm the particle filter tracks it, and confirm the failure
      is as stark as predicted.
- [ ] Check whether the particle filter's looser tempo is visible as drift over a
      long track, or whether it is invisible in practice.

If one decoder wins on everything you play, that is worth knowing — it would make
the other a candidate for removal rather than a permanent choice.

## 4. Drops and sections

- [ ] Play a track with a build-up and drop you know. The lighting reacts within
      roughly a second of the drop, not several.
- [ ] The build-up strobe speeds up as the build-up progresses, rather than
      switching on abruptly at the top.
- [ ] A breakdown dims rather than going dark.
- [ ] Note any false drop — a cymbal crash, a shout, a sudden vocal — and what
      was playing. Thresholds live in `TransientConfig`, so a pattern of false
      fires is a tuning change, not a rewrite.

## 5. The tail itself

- [ ] Frames reach the strip: with a device connected and a session running, the
      tail follows the music and the on-screen strip matches what the tail shows.
- [ ] Stopping the session hands rendering back to the device's own effect stack —
      the tail must not freeze on the last frame.
- [ ] Disconnecting mid-session (walk out of range) leaves the tail on its own
      effects rather than stuck. The firmware reverts direct mode itself; this is
      checking that it actually does.
- [ ] Sync: does the flash land with the beat, or behind it? Use the trigger-offset
      slider to pull it into place and note the value that felt right — that number
      is the real answer to how much latency the BLE path adds.

## 6. The preview tells the truth

- [ ] On the LED config screen, the preview of the device's *own* effect stack
      matches what the tail is doing: same colours, same direction, same speed.
      Check a rainbow (motion), a static colour (exactness) and an audio-reactive
      layer (it should react to the room).
- [ ] Change a layer parameter and confirm the preview updates immediately while
      the running effect state (rainbow phase, bar levels) does not visibly reset.

## 7. Genre

Only meaningful once the model artifacts are installed; see `docs/genre-model.md`.

- [ ] With the models installed, play a few tracks of clearly different genres and
      note what the classifier says and how long it takes to settle.
- [ ] Confirm the profile does *not* flicker during a transition between tracks —
      that is what the 12-second rolling majority is for.
- [ ] Note any genre that consistently selects a profile that feels wrong. The
      genre→profile mapping is pure data in `EffectProfiles` and easy to change.

## 8. Soak

- [ ] Run a full DJ set or playlist, 30–60 minutes, spanning several genres, with
      the phone in a pocket and the screen off.
- [ ] Afterwards, report: any crash; whether memory grew; whether the phone got
      hot enough to throttle and whether the lighting degraded as it did; whether
      beat tracking drifted late in the session; whether the BLE connection
      survived.

## What to report back

For anything that fails, the useful details are: the track (or the moment in it),
what you saw versus what you expected, and — for timing problems — the trigger
offset that fixed it. Logcat filtered to `OboeCapture`, `LightingEngine`,
`BeatLightSession` and `DeviceRepository` covers the rest.
