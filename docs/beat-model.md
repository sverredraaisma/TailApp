# The beat model

BeatLight's beat tier turns feature frames into "how much does this frame look
like a beat". Two implementations sit behind `ActivationSource`: the DSP one
(`SpectralFluxActivationSource`) that ships and runs today, and BeatNet's CRNN
(`CrnnActivationSource`), exported to ONNX and run **entirely on the phone**. No
audio, no features and no activations leave the device, ever — the same hard
requirement the genre tier has, for the same reason.

**Read the status section first.** The export works and is verified; the Kotlin
runner is written and tested as far as the JVM allows; the model is *not* wired
into the running pipeline, because the front-end it needs is not the front-end
this app has. That gap is measured, not guessed, and the numbers are below.

## What it is

| | |
|---|---|
| Model | **BeatNet** CRNN (`BDA`, "beat downbeat activation") |
| Paper | Heydari, Cwitkowitz & Duan, *BeatNet: CRNN and Particle Filtering for Online Joint Beat, Downbeat and Meter Tracking*, ISMIR 2021 |
| Source | <https://github.com/mjhydri/BeatNet>, PyPI `BeatNet==1.1.3` |
| Licence | **CC BY 4.0** |
| Checkpoint | `model_1_weights.pt` (GTZAN-trained; 2 = Ballroom, 3 = Rock corpus) |
| Architecture | `Conv1d(1→2, k=10)` → ReLU → `MaxPool1d(2)` → `Linear(262→150)` → **`LSTM(150, 150, layers=2)`** → `Linear(150→3)` → softmax |
| Input | 272 floats per 20 ms frame |
| Output | `[beat, downbeat, non-beat]`, summing to 1 |

Three things about that table are worth stating plainly because the project plan
says otherwise:

1. **The recurrent layer is an LSTM, not a GRU.** Two state tensors, not one.
   Carrying the hidden state across frames while forgetting the cell state
   produces a model that half-works and degrades over a session — exactly the
   failure that reads as "it tracks worse the second time".
2. **The plan names `mjhydri/BeatNet-Plus`.** That repository exists and is the
   2024 successor (TISMIR), with a 4-layer LSTM, a 1764-sample window and
   288-dimensional features. It is **not** on PyPI under any spelling
   (`BeatNet-Plus`, `beatnet-plus`, `BeatNetPlus` all 404) — it installs from a
   git clone, and its weights live only in the GitHub tree. This work uses the
   original BeatNet, which is on PyPI with
   its checkpoints inside the wheel, is the model the rest of the plan's numbers
   refer to, and is a straight swap if BeatNet+ is wanted later — the export
   script's only BeatNet-specific parts are the module import and four
   dimensions.
3. **The checkpoints ship inside the pip package**, under `BeatNet/models/`.
   There is no separate weights download.

### Licence and attribution

> **BeatNet** © Mojtaba Heydari et al., University of Rochester.
> <https://github.com/mjhydri/BeatNet> — licensed **CC BY 4.0**
> (<https://creativecommons.org/licenses/by/4.0/>), as declared by the
> `LICENSE` file inside the published wheel ("Attribution 4.0 International").
>
> Heydari, M., Cwitkowitz, F., & Duan, Z. (2021).
> *BeatNet: CRNN and Particle Filtering for Online Joint Beat, Downbeat and
> Meter Tracking.* ISMIR 2021.

CC BY 4.0 is materially more permissive than the genre models' BY-NC-ND:
attribution is the only condition, so redistribution *and* commercial use are
both allowed, and the derived ONNX file is an allowed adaptation as long as the
attribution above travels with it.

**So the weights are kept out of git for a different reason than the genre
models': they are a build output.** The `.onnx` does not exist upstream —
`tools/export_beatnet.py` rewrites the graph so the LSTM state crosses the
graph boundary — so committing it would mean committing something rebuildable.
`/models/` stays gitignored and `BeatModelStore` installs the file onto the
device, the same shape of arrangement the genre models use.

madmom (BSD / CC BY-NC-SA) is used only on the workstation, to generate the
reference dump. None of it is ported into the app.

## Producing the artifact

Full detail in [`tools/README.md`](../tools/README.md). Short version:

```bash
uv run tools/download_beatnet.py     # wheel + madmom sources -> /models
uv run tools/export_beatnet.py       # beatnet-crnn-model1.onnx -> /models
uv run tools/dump_beat_reference.py  # reference -> /testdata
```

Each script declares its own dependencies inline (PEP 723), so `uv run` builds
the environment it needs and nothing else. That is deliberate: the export needs
PyTorch, the reference dump needs madmom, and `tools/pyproject.toml` — the genre
tier's environment — needs TensorFlow. Putting all three in one lockfile would
be a 2 GB resolver fight for no benefit.

### The streaming export, and why it is not a straight `torch.onnx.export`

`BeatNet.model.BDA.forward` keeps the LSTM state in *instance attributes* and
reassigns them on every call. Exported as-is, that state folds into the graph as
a constant zero initialiser and every frame is decoded as if it were the first.
(The same design is why BeatNet's own `process()` starts the second file from
wherever the first ended.)

`export_beatnet.py` rewraps the module so hidden and cell state are explicit
inputs and explicit outputs, and folds in the softmax that `BDA.final_pred`
applies separately. The layers are *shared* with the loaded module, not copied,
so it is the same arithmetic rather than a reimplementation.

Three things are asserted before the file is written, and the script deletes its
own output if any of them fail:

| Check | Last measured |
|---|---|
| checkpoint loads with **no missing keys** (BeatNet loads with `strict=False`, which would silently leave a renamed layer at its random init) | no missing keys, no unexpected keys |
| streaming the rewrapped module frame by frame == one whole-sequence PyTorch run | max abs diff **3.6e-7** |
| ONNX Runtime whole-sequence == PyTorch | max abs diff **3.6e-7** |
| ONNX Runtime frame-by-frame == PyTorch whole-sequence | max abs diff **3.6e-7** |

`dump_beat_reference.py` repeats the last check on *real* madmom features rather
than random ones: **max abs diff 0.0** over 300 frames.

### Input/output contract

Resolved from the exported graph by the script, which prints them:

| | Name | Shape | Type |
|---|---|---|---|
| in | `features` | `[1, frames, 272]` | float32 |
| in | `h0` | `[2, 1, 150]` | float32 |
| in | `c0` | `[2, 1, 150]` | float32 |
| out | `probs` | `[1, 3, frames]` | float32 |
| out | `hn` | `[2, 1, 150]` | float32 |
| out | `cn` | `[2, 1, 150]` | float32 |

`frames` is dynamic; the phone always passes 1. Batch is pinned to 1 because the
recurrent state is per-session and there is exactly one session. `probs`'s class
axis is `[beat, downbeat, non-beat]` and sums to 1.

`CrnnActivationSource` requires all six names and refuses a graph that is missing
any — unlike the genre models there is no "use the only input" fallback to fall
back on, and guessing between three inputs would silently swap the hidden and
cell states.

The 272 floats are `bands ‖ max(0, bands − previous bands)`: madmom's
`SpectrogramDifferenceProcessor(diff_ratio=0.5, positive_diffs=True,
stack_diffs=np.hstack)` resolves to a one-frame lag at this window and hop, which
the dump records as `diffFrames: 1`.

## The front-end gate — the important part

The project plan makes numeric validation of the feature extractor mandatory
before any model work, and `FeatureConfig`'s KDoc claims its defaults were chosen
to match BeatNet's `log_spect.py`. **They were not, and the claim was never
checked against the real thing.**

`tools/dump_beat_reference.py` runs BeatNet's own extractor — madmom's
`LogarithmicFilteredSpectrogram`, unmodified — and
`app/src/test/java/com/tailapp/audio/BeatNetFrontEndParityTest.kt` diffs our
`FeatureExtractor` against it. Two of eight properties match:

| Property | BeatNet | `FeatureConfig()` | |
|---|---|---|---|
| sample rate | 22050 | 22050 | match |
| hop | 441 (50 fps) | 441 | match |
| log compression | `log10(1·x + 1)` | `log10(1·x + 1)` | match |
| bands per octave | 24 | 24 | match |
| **window** | **1411** (64 ms) | 2048 | **differ** |
| **band count** | **136** | 205 | **differ** |
| **lowest band centre** | 46.85 Hz | 30.00 Hz | **differ** |
| **filter scaling** | unit **area** (`norm_filters=True`) | unit **peak** | **differ** |
| **frame alignment** | centred on `t·hop` | window *ends* at `frameSize + t·hop` | **differ** |
| **model input** | `bands ‖ positive diff` (272) | `bands` (205) | **differ** |

Where each divergence comes from:

- **1411, not 2048.** `BeatNet.py` computes `int(64 * 0.001 * 22050)`. It is not
  a power of two (1411 = 17 × 83), so `audio/dsp/Fft` — radix-2 — cannot produce
  it at all, and `FeatureConfig` `require`s a power of two.
- **136, not 205.** madmom quantises its log frequencies to FFT bins and **drops
  duplicates** (`unique_bins=True`), then spends the first and last on the outer
  corners of the first and last triangles. Our `LogFilterbank` keeps every
  requested centre and collapses sub-bin triangles onto the nearest bin,
  duplicating it. So madmom's lowest *filter* sits at 46.85 Hz even though its
  `fmin` is 30 Hz. The two banks agree at the top (both clamped by the same
  Nyquist: 10853 vs 10861 Hz).
- **Unit area, not unit peak.** `norm_filters=True` divides each triangle by its
  bin weights so it sums to exactly 1. Ours peak at 1 instead. Measured through a
  flat unit spectrum, our band weights sum to anywhere from **0 to 28.3** where
  every one of madmom's sums to 1 — a per-band gain, not a global scale factor,
  so it cannot be divided out. (The zero is `LogFilterbank`'s sub-bin collapse at
  the very bottom of the range, where several band centres share one FFT bin;
  recorded because it is another way the two banks are not the same
  representation, not as a defect to fix here.)
- **Centred frames.** madmom's `FramedSignalProcessor(origin=0)` centres frame
  `t` on sample `t·hop`, zero-padding the first half-window. Ours emits a frame
  once its window has filled, so frame `i` ends at `frameSize + i·hop`. The
  offset is 1024/441 = **2.3 frames** — not an integer, so it cannot even be
  fixed by renumbering.

### How far apart, in numbers

Comparing each of madmom's 136 filters against our nearest band centre, on the
best whole-frame alignment (+2 frames), over the 6 s reference signal:

```
mean |ours − BeatNet| = 0.210      max = 1.954
reference range        0 .. 1.954
```

The mean error is **11% of the reference's entire range**. This is not a
tolerance to loosen.

### What happens if you feed it anyway

Measured, not assumed. Rebanding our 205-band frames onto BeatNet's 136-filter
geometry as carefully as the data allows (undo the log, regroup with unit-area
weights onto madmom's corners, re-log, stack the positive difference) and running
the real CRNN on the result, versus the true features, on the same 120 BPM
6-second signal:

| | true features | rebanded from ours |
|---|---|---|
| beat peaks (>0.5) | frames 62, 87, 187, 212 — spacing **25** (= 120 BPM) | frames 9, 59, 84, 134, 184, 234, 284 — spacing mostly **50** (= 60 BPM) |
| downbeat peaks | **12**: frame 12, then 25, 50, 75 … 275, exactly on the 25-frame grid | **1**, at frame 34 |

It reports half-time and the downbeat channel — the entire reason to prefer a
CRNN over spectral flux — collapses. That is why no such adapter is in the app:
a wrong front-end does not make a model slightly worse, it makes it confidently
wrong, and `SpectralFluxActivationSource` is better than confidently wrong.

### Why `FeatureConfig`'s defaults were not changed

Deliberate, and the brief for this work said so explicitly. The DSP beat tracker,
the tempo estimator and the whole transient tier are calibrated against 205 bands
from a 2048-sample window, and their tests assert numbers (tempo within ±2 BPM,
beats within ±70 ms) against that geometry. Moving the shared front-end to chase
a model that is not wired in would break three working tiers to enable none.

A real BeatNet front-end would therefore be a **second** extractor, not a
reconfiguration of this one — and it needs three things this change does not
build:

1. A 1411-point real DFT (Bluestein over a 4096-point radix-2 FFT is the obvious
   route; a naive DFT at 50 fps is ~100 Mop/s and not viable).
2. A port of madmom's `LogarithmicFilterbank` with unique-bin dedup and unit-area
   normalisation.
3. Centred framing, and a way to *reach* the audio: `ActivationSource` receives a
   `FeatureFrame`, not samples, so the extractor would have to be chosen where
   `LightingEngine` builds it.

## Where the model lives at run time

`filesDir/beat-models/`, managed by `BeatModelStore`:

| File | |
|---|---|
| `beatnet-crnn-model1.onnx` | 1.6 MB |

`install(name, stream)` writes to a `.part` and renames, so a stream that dies
halfway cannot leave a truncated `.onnx` behind. `adb push` recipe in
`tools/README.md`.

**Not `assets/`** — see the licence section: the reason is that the file is a
build output, not a licence restriction.

### The model-not-installed state

A fresh install has no model. That is the normal case, not an error:

- `BeatModelStore.isInstalled` is false and `.missing` names what is absent.
- `CrnnActivationSource.create` returns null, and `AppContainer` falls back to
  `SpectralFluxActivationSource`.
- The beat tier runs exactly as it does today.

`create` also returns null when the model *is* installed but the `FeatureConfig`
it is handed is not BeatNet's — which is the case on the shipped defaults. It
logs which properties mismatched. **That is the state the app is in today.**

A model that is present but unloadable degrades the same way, once: the failure
is logged at first use, `isAvailable` goes false, and every later frame
short-circuits to `BeatActivation(0, 0)`. A broken install must not cost a
session-length, 50 Hz stream of exceptions on the analysis thread. Loading is
lazy, so `create` never touches ONNX Runtime and cannot throw during startup.

### `reset()` is not optional

`ActivationSource.reset()` clears the LSTM's hidden **and cell** state and the
band history the difference is taken against. The state encodes where in the bar
the model believes it is; carrying a previous session's into a new one starts the
tracker confidently in the wrong place and it can take bars to recover. BeatNet's
own PyTorch module has no reset at all, which is precisely the bug the streaming
export was designed to make impossible here.

## Runtime and cost

`com.microsoft.onnxruntime:onnxruntime-android:1.27.0`, already in the build for
the genre tier — this model adds **no APK weight at all** beyond its own 1.6 MB
on the device. CPU execution provider only, and a single intra-op thread: one
frame is a 272-wide conv, two 150-cell LSTM steps and a 3-way linear, and
splitting that across threads costs more in synchronisation than it saves, fifty
times a second, on a phone already running a render loop and a BLE stream.

**Per-frame inference cost can only be measured on-device**, and has not been:
`libonnxruntime.so` is an Android native library and this project has no
instrumented tests, so no JVM benchmark of it is possible or meaningful. What can
be stated is the arithmetic:

| Stage | multiply-accumulates per frame |
|---|---|
| `Conv1d(1→2, k=10)` over 272 | ~5.3 k |
| `Linear(262→150)` | 39.3 k |
| `LSTM(150→150)` × 2 layers | 360 k |
| `Linear(150→3)` | 0.45 k |
| **total** | **~405 k MAC ≈ 0.8 MFLOP** |

The budget is one 441-sample hop, **20 ms**. 0.8 MFLOP is roughly a millisecond
of one modern phone core at a conservative effective throughput, i.e. about 5% of
the hop, and the genre tier's EfficientNet — three orders of magnitude larger —
already fits in the same thread's slack every three seconds. The plan's "well
under one hop" is very likely right; it remains unverified until someone runs it
on a phone. `docs/beatlight-manual-checks.md` is where that belongs.

The Java-side per-frame work (`buildInput`: 136 subtractions and two
`arraycopy`s, into pre-allocated arrays) allocates nothing. The three input
tensors and the result handle are per-frame allocations that ONNX Runtime's API
requires; at 50 Hz that is the same order as the genre tier's and has never been
the bottleneck.

## Testing

`tools/dump_beat_reference.py` writes `testdata/beat_reference.json`: the
synthetic signal, madmom's 300×272 feature matrix for it, the CRNN's 300×3
activations, madmom's resolved filterbank geometry, and the ONNX graph's tensor
names and shapes. The parity tests skip with a reason when it is absent, because
regenerating it costs a PyTorch install.

- **`audio/BeatNetFrontEndParityTest`** — the gate above. Asserts the two
  properties that match and pins each of the six that do not, with the measured
  size of the gap. Every one of those assertions **fails the day someone makes
  the front-ends agree**, which is the day this document needs rewriting and
  `CrnnActivationSource` can be switched on. That is the intended failure.
- **`beat/CrnnActivationSourceTest`** — the model-absent path, the
  wrong-front-end path, atomic install, a mis-shaped frame disabling the source
  rather than corrupting the input, an unloadable model degrading to silence
  exactly once, and that `reset()` clears the cell state as well as the hidden
  state. Plus two numeric checks against the dump: that the exported graph's six
  tensor names are the ones the class asks for, and that the 272-vector
  `buildInput` assembles equals madmom's stacked difference — **max |Kotlin −
  madmom| = 0.0** over all 300×272 values. It is exact, not close, because both
  sides do the same float32 subtraction on the same float32 inputs.

Inference itself is not unit-tested, for the same reason the genre model's is
not. `testdata/beat_reference.json` carries host-run activations so an on-device
run can be checked against a known-good one when that becomes worth doing.

## Status, honestly

| | |
|---|---|
| Upstream located, licence confirmed | done — BeatNet 1.1.3, CC BY 4.0, checkpoints inside the wheel |
| ONNX export with explicit LSTM state | done and verified to 3.6e-7 against PyTorch, both whole-sequence and frame-by-frame |
| Reference dump from BeatNet's own extractor | done — 300 frames, madmom unmodified |
| Front-end parity (the plan's Phase 2 gate) | **failed, measured**: mean |diff| 0.210 on a 0..1.954 range; six of eight properties differ |
| `CrnnActivationSource` | written, and correct as far as the JVM can prove; refuses to run on the wrong front-end |
| `BeatModelStore` | done |
| Wired into the running pipeline | **no** — see below |
| Per-frame cost on a device | **not measured** — needs a phone |
| Accuracy against real music | **not measured** — needs a phone and the front-end above |

`AppContainer` selects the activation source (`beatActivationSource`) and today
resolves it to `SpectralFluxActivationSource`. It is not *reached*, because
`LightingEngine` constructs `BeatTracker(featureConfig)` and takes no activation
source; giving it one is a one-line parameter addition in `effects/`, deliberately
left undone because that file was not this change's to touch.
