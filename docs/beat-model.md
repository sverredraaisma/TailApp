# The beat model

BeatLight's beat tier turns feature frames into "how much does this frame look
like a beat". Two activation functions can produce that curve: the DSP one
(`SpectralFluxActivationSource`, behind the `ActivationSource` interface) and
BeatNet's CRNN (`CrnnActivationSource`), exported to ONNX and run **entirely on
the phone**. No audio, no features and no activations leave the device, ever —
the same hard requirement the genre tier has, for the same reason.

**The CRNN is compiled out.** `AppContainer.USE_CRNN_BEAT_ACTIVATION` is `false`,
which nulls the `BeatModelStore` handed to `LightingEngine`, so neither the CRNN
source nor its extractor is constructed even where a model *is* installed. The
DSP spectral-flux activation runs everywhere, on every install.

That is a measurement, not an oversight, and the reasoning is in
`AppContainer`'s own KDoc: on a real phone microphone the CRNN's activation is
weak and **temporally smeared** — beats only about twice the baseline and spread
across many frames — so neither decoder locks cleanly and the tempo drifts. No
amount of amplitude normalisation sharpens a signal that is not sharp in time.
Spectral flux is z-scored onset detection, robust to a low noisy level, and gives
a stable, correct BPM on exactly this input. The flag exists because the CRNN is
worth having on a cleaner source — line-in, or a mic-trained model — and
everything below stays true of it: it is exported, verified, wired and tested.
Flip the flag there.

The rest of this document describes what that work established, since it is the
basis for turning the CRNN back on. What made it possible at all was building the
front-end BeatNet was actually trained on, as a *second* extractor
(`BeatNetFeatureExtractor`) rather than by moving the shared one. That front-end
now matches madmom's own output to **1.1e-6** over 81 328 values, where the
shared extractor was 0.210 out on a 0..1.954 range. The measurements are below.

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
before any model work.

**The gate is passed.** `com.tailapp.audio.BeatNetFeatureExtractor` is BeatNet's
front-end — madmom's pipeline, ported — and
`BeatNetFeatureExtractorTest` diffs every value of its output against
`tools/dump_beat_reference.py`'s madmom dump:

```
max |ours − BeatNet| = 1.1e-6      mean = 7.4e-8
over 299 frames x 272 columns = 81 328 values
reference range        0 .. 1.954
```

That residual is float32 round-off, not an algorithmic difference: the same port
carried out in double agrees with madmom at **0.0 exactly**, every value, which
is how the geometry was pinned down before a line of Kotlin was written. The two
front-ends are the same front-end.

The rest of this section is the *shared* extractor's divergence, which is still
real, still deliberate, and is the reason a second extractor exists at all.

### Why the shared front-end could not be used

`FeatureConfig`'s KDoc used to claim its defaults were chosen to match BeatNet's
`log_spect.py`. **They were not, and the claim was never checked against the real
thing.** `app/src/test/java/com/tailapp/audio/BeatNetFrontEndParityTest.kt` diffs
`FeatureExtractor` against the same dump. Two of eight properties match:

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

The mean error is **11% of the reference's entire range** — against the
1.1e-6 the dedicated extractor reaches on the same comparison, a factor of
190 000. This was never a tolerance to loosen.

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

Deliberate, and still true. The DSP beat tracker, the tempo estimator and the
whole transient tier are calibrated against 205 bands from a 2048-sample window,
and their tests assert numbers (tempo within ±2 BPM, beats within ±70 ms) against
that geometry. Moving the shared front-end would break three working tiers to
enable something that does not need it.

## The second front-end — what was built, and what it measures

`com.tailapp.audio.BeatNetFeatureExtractor`. Three pieces, and what each of them
turned out to cost:

### 1. A 1411-point DFT — `audio/dsp/BluesteinFft`

1411 = 17 × 83, so `audio/dsp/Fft` (radix-2) cannot produce it and a naive DFT at
50 fps is ~100 Mop/s. Bluestein's chirp-z algorithm turns the transform into a
convolution, which runs on a power-of-two FFT — 4096 for n = 1411 — so the
existing `Fft` does the work and there is no third transform in the codebase. The
chirp, the transformed filter and every scratch buffer are built in the
constructor; a call allocates nothing.

Checked against a naive O(n²) double-precision DFT at 2, 3, 4, 5, 7, 16, 17, 83,
100, 256 and 1411:

```
worst relative error, any size   2.5e-7
at n = 1411                      2.4e-7
```

i.e. a couple of float ulps, and no worse at the awkward size than at a power of
two. `BluesteinFftTest` prints the per-size table.

### 2. madmom's `LogarithmicFilterbank`

Ported as `MadmomLogFilterbank`, next to the extractor. Unique-bin dedup (219
requested log frequencies collapse to 138 distinct FFT bins, hence 136 triangles,
hence a lowest *filter* at 46.9 Hz although `fmin` is 30 Hz) and unit-**area**
normalisation, each triangle divided by its own sum.

Two quirks had to be reproduced rather than corrected, and both change every
band:

- **705 bins, not 706.** madmom's `stft` returns `fft_size >> 1` bins and drops
  what would be the Nyquist bin. 1411 is odd, so bin 705 is an ordinary bin that
  madmom simply never computes.
- **Bin `k` is labelled `k · sampleRate / 1410`**, not `k · sampleRate / 1411`.
  That is not the true frequency of bin `k` of a 1411-point transform, but it is
  what the filterbank the model was trained with was built against.

Worth recording: `tools/dump_beat_reference.py`'s *geometry* block builds its
filterbank on a 706-bin axis while the `features` it dumps came through madmom's
705-bin one. The two axes are 0.14% apart, enough to move **8 of the 136**
centres onto a different bin. `features` is what the model consumes, so 705 is
the axis this port reproduces; `BeatNetFeatureExtractorTest` pins the eight
one-bin disagreements so nobody re-derives that conclusion from scratch.

### 3. Centred framing and a streaming contract

madmom's `FramedSignalProcessor(origin=0)` centres frame `t` on sample `t·hop`,
so frame `t` covers `[t·hop − 705, t·hop + 706)` and completes once sample
`t·hop + 705` has arrived — the first frame needs 706 samples, not a full window,
because its leading 705 samples are zero padding. Getting this wrong shifts every
activation by 2.3 frames and is invisible except as bad sync.

The streaming contract is `FeatureExtractor`'s, deliberately: `push` takes
arbitrary chunk sizes, frame boundaries land in the same absolute places
regardless of chunking (asserted bit-identically, one block against 37-sample
pieces), timestamps are walked back from the chunk's own `endTimestampNanos`, and
`reset()` returns it to silence. The one frame madmom emits that streaming cannot
is the last: it is zero-padded on the *right*, and a live extractor does not know
where the signal ends. So 299 frames are compared against the dump's 300.

The output is the 272-float vector directly — `bands ‖ max(0, bands − previous
bands)` — rather than bands plus a difference computed downstream. `BeatNetFrame`
is its own type, not a `FeatureFrame`, so the wrong spectrogram cannot reach the
model by type-checking.

### What it costs

Measured on the JVM over the 6 s reference signal (`BeatNetFeatureExtractorTest`
prints it; run to run it moves by about 50%, so these are ranges):

| | per frame |
|---|---|
| BeatNet front-end (1411-pt Bluestein + 136 unit-area filters) | **0.09 – 0.14 ms** |
| shared front-end (2048-pt radix-2 + 205 filters + statistics) | 0.05 – 0.06 ms |
| both | **0.15 – 0.20 ms** — about 1% of the 20 ms hop |

Desktop JVM, not a phone, so treat it as an order of magnitude rather than a
budget. The point it settles is that the second front-end is *not* the expensive
part of running the CRNN — it costs about twice the shared one, on a hop that has
a hundred times that in hand.

## Running it: two front-ends, one audio stream

`LightingEngine` owns the decision, because it is the only thing positioned to
drive both extractors off one resampled stream. When `BeatModelStore` has a model
and `CrnnActivationSource.create` accepts the shared `FeatureConfig`, the engine
pushes each chunk through both extractors and drives the decoder through
`BeatDecoder.process(frame, activation)` — the overload that exists for exactly
this. The shared `FeatureFrame` still feeds the tempo estimator, the transient
tier and every timestamp; only the activation changes.

**How the frames pair.** Both run at a 441-sample hop, so after start-up they
emit one for one — but not at the same *points*, because their windows are
aligned differently. The shared extractor emits frame `i` once `2048 + i·441`
samples have arrived; the centred BeatNet extractor emits frame `t` after only
`706 + t·441`, so it runs ahead by

```
lag = (2048 − 706) / 441 = 3 frames
```

and shared frame `i` takes the activation of **BeatNet frame `i + 3`** — exactly
the newest BeatNet frame in existence when shared frame `i` closes. It is
implemented by discarding the first three activations and then consuming one per
shared frame, which makes it independent of how the audio was chunked. The model
is still *run* on those three frames: they are real audio and the LSTM's state
has to have seen them.

**Residual offset: 19 samples, 0.86 ms.** Window *ends* are the right thing to
align, not centres: both activation functions respond to an onset on the first
frame whose window contains it, so an onset at sample `s` shows up in the first
frame whose window closes at or after `s` on either side. Shared frame `i` closes
at `i·441 + 2047`; BeatNet frame `i + 3` closes at `i·441 + 2028`. The activation
is stamped with the shared frame's timestamp, so the model's opinion is applied
0.86 ms later than the audio it was formed from — two orders of magnitude inside
the ±70 ms window the beat tests assert against. (Aligning window *centres*
instead would pick frame `i + 2` and a −6.4 ms offset; that is the wrong
criterion for a positive-difference feature and throws away the freshest frame
for nothing.) `LightingEngineTest` checks the lag and the residual against the
two extractors rather than restating them.

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

A fresh install has no model. That is the normal case, not an error — and with
`USE_CRNN_BEAT_ACTIVATION = false` it is also the *only* case today, because
`AppContainer` passes `LightingEngine` a null `BeatModelStore` and the gate below
is never reached. It is described as written because it is what the flag re-arms.
When a store *is* passed and no model is present:

- `BeatModelStore.isInstalled` is false and `.missing` names what is absent.
- `CrnnActivationSource.create` returns null, so `LightingEngine` never
  constructs the second front-end at all.
- The beat tier runs on `SpectralFluxActivationSource`, exactly as it did before
  the CRNN existed. `BeatLightState.activationSource` says so, and the monitor
  card shows it.

`create` also returns null when the model *is* installed but the shared
`FeatureConfig` cannot be paired with BeatNet's: a different sample rate (the two
extractors are fed the same samples), a different hop (their frame streams would
run at different rates, so no fixed pairing exists), or a shared window shorter
than BeatNet's first frame (706 samples — the paired activation would arrive
late). On the shipped defaults all three hold, so **once the flag is on, the CRNN
runs whenever its model is installed.** Note that `create` no longer looks at the band count or the
window length: the model brings its own front-end now, and checking the shared
one against BeatNet's geometry would be checking the wrong thing.

A model that is present but unloadable degrades the same way, once: the failure
is logged at first use, `isAvailable` goes false, every later frame
short-circuits to `BeatActivation(0, 0)`, and the engine goes back to the DSP
activation for the rest of the session (costing about a second while the DSP
source's adaptive statistics warm up). A broken install must not cost a
session-length, 50 Hz stream of exceptions on the analysis thread. Loading is
lazy, so `create` never touches ONNX Runtime and cannot throw during startup.
`LightingEngineTest` drives that whole path — on the JVM the first inference
fails with `UnsatisfiedLinkError`, which is exactly what a corrupt model looks
like on a device — and asserts the session still tracks 128 BPM afterwards.

### `reset()` is not optional

A session's memory has two halves and both have to go:

- `CrnnActivationSource.reset()` clears the LSTM's hidden **and cell** state. It
  encodes where in the bar the model believes it is; carrying a previous
  session's into a new one starts the tracker confidently in the wrong place and
  it can take bars to recover. BeatNet's own PyTorch module has no reset at all,
  which is precisely the bug the streaming export was designed to make impossible
  here.
- `BeatNetFeatureExtractor.reset()` clears the frame the positive difference is
  taken against, along with the ring buffer.

`LightingEngine.reset` does both, on every `start()`.

## Runtime and cost

`com.microsoft.onnxruntime:onnxruntime-android:1.27.0`, already in the build for
the genre tier — this model adds **no APK weight at all** beyond its own 1.6 MB
on the device. CPU execution provider only, and a single intra-op thread: one
frame is a 272-wide conv, two 150-cell LSTM steps and a 3-way linear, and
splitting that across threads costs more in synchronisation than it saves, fifty
times a second, on a phone already running a render loop and a BLE stream.

**Per-frame *inference* cost can only be measured on-device**, and has not been:
`libonnxruntime.so` is an Android native library and this project has no
instrumented tests, so no JVM benchmark of it is possible or meaningful. (The
*front-end* cost is measurable and was measured — 0.202 ms per frame for both
extractors together, above.) What can be stated about inference is the
arithmetic:

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

The Java-side per-frame work is now just wrapping `BeatNetFrame.features` in a
`FloatBuffer` — the extractor already assembled the stacked difference, so there
is no copy and no reshape between the front-end and the tensor. The three input
tensors and the result handle are per-frame allocations that ONNX Runtime's API
requires; at 50 Hz that is the same order as the genre tier's and has never been
the bottleneck.

## Testing

`tools/dump_beat_reference.py` writes `testdata/beat_reference.json`: the
synthetic signal, madmom's 300×272 feature matrix for it, the CRNN's 300×3
activations, madmom's resolved filterbank geometry, and the ONNX graph's tensor
names and shapes. The parity tests skip with a reason when it is absent, because
regenerating it costs a PyTorch install.

- **`audio/BeatNetFeatureExtractorTest`** — **the gate**. Diffs all 299×272
  values against the dump (max 1.1e-6, mean 7.4e-8), checks the difference half
  separately so a right-bands-wrong-difference extractor cannot hide behind the
  zeros, asserts chunk-boundary independence bit-identically, pins the centred
  framing (frame 0 completes at 706 samples, not at a full window) and times both
  front-ends against the hop.
- **`audio/dsp/BluesteinFftTest`** — the transform against a naive O(n²)
  double-precision DFT at eleven sizes including 1411, plus a delta, a pure tone,
  agreement with the radix-2 `Fft` where both apply, and that repeated calls do
  not leak scratch.
- **`audio/BeatNetFrontEndParityTest`** — the *shared* front-end's divergence,
  still pinned with the measured size of each gap. It is no longer the gate, but
  every one of its assertions fails the day someone quietly moves `FeatureConfig`
  onto BeatNet's geometry — which the CRNN does not need and which three
  calibrated tiers would notice. That is the intended failure.
- **`beat/CrnnActivationSourceTest`** — the model-absent path, the pairing gate,
  atomic install, a mis-shaped frame disabling the source rather than corrupting
  the input, an unloadable model degrading to silence exactly once, that
  `reset()` clears the cell state as well as the hidden state, and that the
  exported graph's six tensor names are the ones the class asks for. The
  272-vector check that used to live here moved into the extractor's test along
  with the code that builds it — where it is stronger, because the vector is now
  computed from audio rather than assembled from reference bands.
- **`effects/LightingEngineTest`** — that with no model the DSP activation runs
  and says so, that an unloadable one degrades mid-session without taking the
  tempo with it, and that the two front-ends pair three frames apart with a
  19-sample residual.

Inference itself is not unit-tested, for the same reason the genre model's is
not. `testdata/beat_reference.json` carries host-run activations so an on-device
run can be checked against a known-good one when that becomes worth doing.

## Status, honestly

| | |
|---|---|
| Upstream located, licence confirmed | done — BeatNet 1.1.3, CC BY 4.0, checkpoints inside the wheel |
| ONNX export with explicit LSTM state | done and verified to 3.6e-7 against PyTorch, both whole-sequence and frame-by-frame |
| Reference dump from BeatNet's own extractor | done — 300 frames, madmom unmodified |
| Arbitrary-size DFT (`BluesteinFft`) | done — 2.5e-7 worst relative error against a naive double-precision DFT, 2.4e-7 at n = 1411 |
| BeatNet front-end (`BeatNetFeatureExtractor`) | done — **max 1.1e-6, mean 7.4e-8** against madmom over 81 328 values on a 0..1.954 range |
| Front-end parity (the plan's Phase 2 gate) | **passed** — by a second extractor. The shared one still differs by 0.210 and deliberately stays that way |
| `CrnnActivationSource` | consumes `BeatNetFrame`; correct as far as the JVM can prove |
| `BeatModelStore` | done |
| Wired into the running pipeline | **yes** — `LightingEngine` runs both front-ends and drives `BeatDecoder.process(frame, activation)`; falls back to spectral flux with no model or a failed load |
| Front-end cost | measured on the JVM: ~0.1 ms + ~0.05 ms per frame, about 1% of the 20 ms hop |
| Per-frame *inference* cost on a device | **not measured** — needs a phone |
| Accuracy against real music | **not measured** — needs a phone; `docs/beatlight-manual-checks.md` is where that belongs |

The remaining unknowns are both on-device: what one ONNX frame actually costs on
a phone, and whether the CRNN tracks real music better than the DSP tracker does.
Neither can be answered from a JVM test suite, and neither is a reason not to
ship the path — with no model installed nothing changes at all, and installing
one is an explicit `adb push`.
