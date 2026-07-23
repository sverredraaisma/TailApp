# tools/ — model preparation for the genre classifier

Three scripts that turn MTG's published Essentia models into the two ONNX files
`com.tailapp.genre.OnnxGenreClassifier` runs, plus the reference dump the Kotlin
unit tests diff against.

Nothing in here is part of the Gradle build. It runs once, on a workstation, when
the models need (re)building. The app never calls out to a network at run time —
see `docs/genre-model.md`.

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
