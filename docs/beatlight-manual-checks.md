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

### 3b. The neural activation, once the model is installed

The BeatNet CRNN is exported and switched on automatically whenever its `.onnx`
is present in `filesDir/beat-models/` (`docs/beat-model.md` has the `adb push`
recipe). Its front-end matches madmom's to 1.1e-6, so what is left to find out is
whether the model helps on real music — and how much it costs on your phone.

- [ ] Install the model, restart the session, and confirm the monitor card says
      the CRNN is the live activation source rather than spectral flux.
- [ ] Play the same track with and without it. The CRNN should be noticeably
      better on anything the spectral flux tracker struggles with — sparse
      percussion, quiet intros, material where every eighth note has energy.
- [ ] Watch the dropped-sample counter. The plan's budget is inference well under
      one 20 ms hop; a climbing counter with the CRNN on and a still counter with
      it off is the signal that this phone cannot afford it. That number has never
      been measured off-device.
- [ ] The particle filter was tuned against spectral flux, whose activation is
      noisy between beats. With the CRNN's much cleaner activation it should do
      *better*, possibly with far fewer particles. Worth a listen.

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

## 6b. The composer

The layer/folder editor. Everything here is covered by JVM tests
(`docs/composer.md` lists them); what a phone adds is whether the *looks* work
and whether editing while the tail is running feels immediate.

- [ ] Each of the six built-in stacks, against music it suits: does it read as
      deliberate, or as noise? Name any that fall flat and why.
- [ ] Edits are live: drag a colour or a decay slider with the session running
      and confirm the tail changes under your finger, with no restart.
- [ ] Editing a parameter does **not** restart the stack's animations — a running
      rainbow or spectrum bar should keep its phase and levels. This is the
      instance-reuse rule; a visible reset means the diff rebuilt the layer.
- [ ] Folders: put a Beat Mask (MULTIPLY) at the top of a folder and confirm it
      pulses only that folder's contents, leaving layers beneath it untouched.
      Then move the same modulator to the top level and confirm it now gates the
      whole frame. That contrast is the feature.
- [ ] Blend modes behave as on the firmware stack: ADD brightens, MULTIPLY
      darkens/gates, OVERWRITE replaces anything non-black.
- [ ] Opacity on a folder mixes the whole group at once, not layer by layer.
- [ ] Save, switch to another stack, switch back: the edit is still there.
      Reset a built-in and confirm the shipped version returns.
- [ ] Frame rate holds up on a deep stack — build something with ~10 layers
      across 2-3 folders and check the tail is still smooth and the phone is not
      getting hot. Note the layer count where it stops being comfortable.

## 7. Genre

Only meaningful once the model artifacts are installed; see `docs/genre-model.md`.

- [ ] With the models installed, play a few tracks of clearly different genres and
      note what the classifier says and how long it takes to settle.
- [ ] Confirm the reported label settles rather than flickering during a
      transition between tracks.
- [ ] The genre no longer switches the lighting on its own — it is one more input
      an effect may read, and a label on the monitor. Note whether the label is
      accurate enough to be worth building an effect against.

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
