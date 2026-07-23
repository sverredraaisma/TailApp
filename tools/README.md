# tools/ — model preparation

Offline scripts that turn published models into the ONNX files the app runs, plus
the reference dumps the Kotlin unit tests diff against. Two independent sets:

| | Scripts | Model | Doc |
|---|---|---|---|
| genre tier | `download_models.py`, `convert_to_onnx.py`, `dump_reference.py` | Essentia Discogs-EffNet + genre_discogs400 | [`docs/genre-model.md`](../docs/genre-model.md) |
| beat tier | `download_beatnet.py`, `export_beatnet.py`, `dump_beat_reference.py` | BeatNet CRNN | [`docs/beat-model.md`](../docs/beat-model.md) |

Nothing in here is part of the Gradle build. It runs once, on a workstation, when
the models need (re)building. The app never calls out to a network at run time.

**Two environments, on purpose.** The genre scripts use `tools/pyproject.toml`
(TensorFlow, Python 3.11) via `uv sync`. The beat scripts carry their own
dependencies inline (PEP 723) and are run with `uv run tools/<script>.py`, which
builds the environment each one needs and nothing else — PyTorch for the export,
madmom on Python 3.9 for the reference dump. One lockfile holding TensorFlow,
PyTorch *and* madmom would be a 2 GB resolver fight for no benefit.

---

# The genre classifier

## Licence and attribution

The models are **not in this repository** and must not be committed. `/models/`
is gitignored.

> **Discogs-EffNet** (`discogs-effnet-bs64-1.pb`) and **genre_discogs400**
> (`genre_discogs400-discogs-effnet-1.pb`)
> © Music Technology Group, Universitat Pompeu Fabra.
> Published at <https://essentia.upf.edu/models.html> under
> **CC BY-NC-ND 4.0** (<https://creativecommons.org/licenses/by-nc-nd/4.0/>).
>
> Alonso-Jiménez, P., Serra, X., & Bogdanov, D. (2022).
> *Music Representation Learning Based on Editorial Metadata from Discogs.*
> ISMIR 2022.

`download_models.py` fetches the **original, unmodified** files from UPF's own
host, with attribution — which is what BY-NC-ND permits. Redistributing them, or
redistributing the ONNX conversions derived from them, is what it does not; hence
no `.pb`, no `.onnx`, and no weights of any kind in git, and hence the models are
installed onto a device rather than packaged into the APK.

The **NC** clause also means this feature is fine for a personal wearable tail
and is not fine in anything commercial.

## Setup

`uv` manages the environment. The system Python here is 3.14, which TensorFlow
does not support, so `pyproject.toml` pins the interpreter to 3.11 and `uv sync`
fetches a managed CPython rather than touching the system install.

```bash
cd tools
uv sync            # ~500 MB: TensorFlow, tf2onnx, onnx, onnxruntime
```

TensorFlow is needed only to *read* the frozen graphs. Inference on both sides —
the reference dump here and the app on the phone — is ONNX Runtime, so the two
run the same engine.

> On Windows, `tensorflow-cpu` is a 2 KB stub that pulls in `tensorflow-intel`
> through per-wheel metadata that uv's universal resolver does not see. The
> dependency list therefore names the real distribution per platform. Installing
> plain `tensorflow-cpu` on Windows gets you the stub and no `tensorflow` module.

## The scripts, in order

### 1. `download_models.py`

```bash
uv run python download_models.py [--force]
```

Fetches three files into `<repo>/models/`:

| File | Size | What |
|---|---|---|
| `discogs-effnet-bs64-1.pb` | 18.4 MB | frozen EfficientNet-B0 embedding graph |
| `genre_discogs400-discogs-effnet-1.pb` | 2.1 MB | frozen 400-class genre head |
| `genre_discogs400-discogs-effnet-1.json` | 15 KB | class labels + inference settings |

Idempotent: a file whose size already matches is left alone. Downloads land on a
`.part` first so an interrupted run cannot leave a truncated `.pb` that the next
run treats as complete.

The `.json` is not just metadata — it is installed onto the device alongside the
models and is where `GenreLabels` reads the 400 class names from at run time.

### 2. `convert_to_onnx.py`

```bash
uv run python convert_to_onnx.py
```

Converts both graphs to ONNX (opset 13) and prints the resolved tensor names and
shapes. Read them from the output; do not assume them. As of the last run:

```
=== discogs-effnet-bs64-1.onnx (16,035,882 bytes)
  input   name='serving_default_melspectrogram:0'      shape=[batch, 128, 96]
  output  name='PartitionedCall:1'                     shape=[batch, 1280]

=== genre_discogs400-discogs-effnet-1.onnx (2,050,398 bytes)
  input   name='serving_default_model_Placeholder:0'   shape=[batch, 1280]
  output  name='PartitionedCall:0'                     shape=[batch, 400]
```

Two things the script does that are not obvious:

- **It picks output `:1`, not `:0`, from the embedding graph.** The frozen
  backbone carries its own 400-way style head on `PartitionedCall:0`; the 1280-d
  penultimate embedding that `genre_discogs400` was trained on is
  `PartitionedCall:1`.
- **It rewrites the baked-in batch size.** The published graph is "bs64": batch
  64 is folded into the constant shape of its `Reshape` nodes, so ONNX Runtime
  rejects any other batch at run time. The script overrides the placeholder to a
  dynamic batch and rewrites those constants' leading `64` to `-1`, then asserts
  a batch-1 run reproduces row 0 of a batch-64 run exactly (last measured: max
  abs diff **0.0**). Without this the phone would do 64x the work for one patch.

### 3. `dump_reference.py`

```bash
uv run python dump_reference.py
```

Synthesises a deterministic 3-second test signal (a 120 BPM kick, a sustained
minor triad and seeded noise hats — no audio file is committed, and none is
needed), runs the whole pipeline on it, and writes
`<repo>/testdata/genre_reference.json`: the signal, the mel spectrogram, the
embedding and the 400 class probabilities, as base64 little-endian float32.

`EffnetMelSpectrogramTest` reads that file and asserts the Kotlin front-end
reproduces the mel matrix. **The mel front-end is duplicated in Python and
Kotlin, and policing that duplication is the entire reason this script exists.**
Change a constant on one side and the test fails on the other.

Last measured parity: max |Kotlin − Python| = **5.5e-6** across all 187x96
log-mel bands (range 0..5.8). Perturbing the mel by that much moves the class
probabilities by under 4e-6 and never reorders the top 5.

If the file is missing the parity tests skip with a reason rather than failing —
regenerating it costs a TensorFlow install.

## Installing the models on a device

The two `.onnx` files do not exist upstream in the form the app needs (the batch
rewrite above is local), so there is nothing to download at run time. Push them:

```bash
adb shell run-as com.tailapp mkdir -p files/genre-models
for f in discogs-effnet-bs64-1.onnx \
         genre_discogs400-discogs-effnet-1.onnx \
         genre_discogs400-discogs-effnet-1.json; do
  adb push "models/$f" "/data/local/tmp/$f"
  adb shell run-as com.tailapp cp "/data/local/tmp/$f" "files/genre-models/$f"
done
```

Until all three are present `GenreModelStore.isInstalled` is false, `AppContainer`
uses `NoGenreClassifier`, and the lighting runs on the default profile. See
`docs/genre-model.md`.

---

# The beat model (BeatNet CRNN)

Three scripts that turn BeatNet's published checkpoint into the streaming ONNX
graph `com.tailapp.beat.CrnnActivationSource` runs, plus the reference dump the
front-end parity tests diff against.

Read [`docs/beat-model.md`](../docs/beat-model.md) before running them — in
particular the front-end section, which is why the model is not switched on.

## Licence and attribution

> **BeatNet** (`model_1_weights.pt`, inside `BeatNet-1.1.3-py3-none-any.whl`)
> © Mojtaba Heydari et al., University of Rochester.
> Published at <https://github.com/mjhydri/BeatNet> under **CC BY 4.0**
> (<https://creativecommons.org/licenses/by/4.0/>) — the wheel's own `LICENSE`
> file is the Attribution 4.0 International legal code.
>
> Heydari, M., Cwitkowitz, F., & Duan, Z. (2021).
> *BeatNet: CRNN and Particle Filtering for Online Joint Beat, Downbeat and
> Meter Tracking.* ISMIR 2021.

CC BY 4.0 permits redistribution and commercial use with attribution, so unlike
the genre weights there is no licence reason the `.onnx` could not ship. It is
kept out of git because it is a *build output*: it does not exist upstream, and
`export_beatnet.py` rebuilds it in seconds.

madmom 0.16.1 (BSD / CC BY-NC-SA, © the madmom authors) is used only here, on the
workstation, to generate the reference dump. None of it is ported into the app.

## Setup

No `uv sync`. Each script declares its dependencies inline (PEP 723):

```bash
uv run tools/download_beatnet.py     # stdlib only
uv run tools/export_beatnet.py       # torch, onnx, onnxruntime  (~250 MB)
uv run tools/dump_beat_reference.py  # numpy, scipy, librosa, mido, onnxruntime
```

Both of the latter pin **Python 3.9**, which `uv` fetches as a managed CPython.
That is madmom's constraint, not a preference: madmom 0.16.1 does
`from collections import MutableSequence` (gone in 3.10) and uses `np.float`
(gone in numpy 1.24).

## The scripts, in order

### 1. `download_beatnet.py`

```bash
uv run tools/download_beatnet.py [--force]
```

Fetches two archives into `<repo>/models/`, verifies each against the SHA-256
PyPI publishes, and unpacks them:

| Archive | Size | Unpacked to | What |
|---|---|---|---|
| `BeatNet-1.1.3-py3-none-any.whl` | 9.2 MB | `models/beatnet/pkg` | `model.py`, `log_spect.py` and the three 1.6 MB checkpoints |
| `madmom-0.16.1.tar.gz` | 20.0 MB | `models/madmom` | madmom's sources |

Idempotent: an already-unpacked tree with the right digest is left alone.

Two things it deliberately does *not* do:

- **It does not `pip install BeatNet`.** The package pins `numba==0.54.1`, which
  resolves against nothing modern, and needs pyaudio and matplotlib for the live
  streaming and plotting this project does not use. The other two scripts put the
  unpacked directory on `sys.path` instead, so only torch, numpy, madmom and
  librosa are ever needed.
- **It does not `pip install madmom` either.** madmom publishes no wheels, only an
  sdist that Cythonises three `.pyx` files — so installing it needs a C toolchain
  (on Windows, a Windows SDK that ships separately from the MSVC compiler). None
  of the compiled modules are on the path this project uses: they are an HMM
  decoder, a CRF beat decoder and a comb filterbank, all part of madmom's
  *decoder*. `dump_beat_reference.py` registers namespace stubs for `madmom` and
  `madmom.audio` so those two `__init__.py` files never execute, and imports the
  pure-Python feature modules directly. Nothing is patched; the code that runs is
  upstream's bytes.

### 2. `export_beatnet.py`

```bash
uv run tools/export_beatnet.py [--model 1|2|3]   # 1 = GTZAN (default)
```

Writes `<repo>/models/beatnet-crnn-model1.onnx` (1.6 MB, opset 17) and prints the
resolved tensor names. Read them from the output; do not assume them. As of the
last run:

```
  arch     conv1=(2, 1, 10) linear0=(150, 262) lstm=LSTM(150, 150, layers=2) linear=(3, 150)
  streaming vs whole-sequence (torch):     max abs diff 3.576e-07
  exported beatnet-crnn-model1.onnx (1,614,782 bytes, opset 17)
  input    name='features'   shape=[1, 'frames', 272] type=tensor(float)
  input    name='h0'         shape=[2, 1, 150] type=tensor(float)
  input    name='c0'         shape=[2, 1, 150] type=tensor(float)
  output   name='probs'      shape=[1, 3, 'frames'] type=tensor(float)
  output   name='hn'         shape=[2, 1, 150] type=tensor(float)
  output   name='cn'         shape=[2, 1, 150] type=tensor(float)
  whole-sequence ORT vs torch:             max abs diff 3.576e-07
  frame-by-frame ORT vs whole-sequence:    max abs diff 3.576e-07
```

The export is not a formality. `BDA.forward` keeps its LSTM state in *instance
attributes*; exported as-is that state folds into the graph as a constant zero
and every frame is decoded as if it were the first. The script rewraps the module
so hidden and cell state are explicit inputs and outputs, folds in the softmax
`final_pred` applies separately, and refuses to write the file unless streaming
it frame by frame reproduces a whole-sequence PyTorch run.

It also refuses to export if the checkpoint has **missing keys**. BeatNet loads
with `strict=False`, which would silently leave a renamed layer at its random
initialisation.

### 3. `dump_beat_reference.py`

```bash
uv run tools/dump_beat_reference.py [--model 1|2|3]
```

Synthesises the same deterministic 120 BPM test signal `dump_reference.py` uses,
at 22050 Hz for 6 s (no audio file is committed, and none is needed), runs
**BeatNet's own `LOG_SPECT`** and then the exported graph on it, and writes
`<repo>/testdata/beat_reference.json`: the signal, the 300×272 feature matrix,
the 300×3 activations, madmom's resolved filterbank geometry, and the graph's
tensor names and shapes — as base64 little-endian float32.

`BeatNetFeatureExtractorTest` reads that file and diffs `BeatNetFeatureExtractor`
— BeatNet's front-end, ported — against every value of it. **That is the gate the
project plan puts in front of any model work, and it passes**: max
|ours − BeatNet| = **1.1e-6**, mean 7.4e-8, over 299×272 = 81 328 values on a
reference range of 0..1.954. The residual is float32 round-off; the same port in
double agrees exactly.

`BeatNetFrontEndParityTest` reads the same file and measures the *shared*
`FeatureExtractor` against it. **It does not match, and that is deliberate**: mean
|ours − BeatNet| = **0.210**, with six of eight front-end properties differing.
Three calibrated tiers depend on that geometry, so it stays. See
`docs/beat-model.md`.

`CrnnActivationSourceTest` also reads it, for the exported graph's tensor names
and the softmax it produces.

One inconsistency in this script worth knowing about before trusting its geometry
block: `filterbank_geometry()` builds its filterbank on a `winLength // 2 + 1` =
**706**-bin frequency axis, while the pipeline that produces `features` uses
madmom's `stft`, which returns `fft_size >> 1` = **705**. The axes are 0.14%
apart, enough to move 8 of the 136 filter centres onto a different bin. The
`features` array is the one the model consumes and the one the Kotlin is asserted
against; `filterCenterHz` is informational and `BeatNetFeatureExtractorTest`
tolerates exactly those eight one-bin differences.

If the file is missing the parity tests skip with a reason rather than failing.

## Installing the model on a device

```bash
adb shell run-as com.tailapp mkdir -p files/beat-models
adb push models/beatnet-crnn-model1.onnx /data/local/tmp/beatnet-crnn-model1.onnx
adb shell run-as com.tailapp cp /data/local/tmp/beatnet-crnn-model1.onnx \
    files/beat-models/beatnet-crnn-model1.onnx
```

Until it is present `BeatModelStore.isInstalled` is false and `LightingEngine`
runs `SpectralFluxActivationSource` — it does not even construct the second
front-end. **Installing it is enough to switch the CRNN on**: the model is fed by
`BeatNetFeatureExtractor`, not by the shared front-end, so
`CrnnActivationSource.create` only has to check that the two extractors can be
paired (same sample rate, same hop, shared window at least 706 samples), and on
the shipped `FeatureConfig` they can. Restart the BeatLight session after
pushing; the monitor card's "Activation" line reports which one is live.
