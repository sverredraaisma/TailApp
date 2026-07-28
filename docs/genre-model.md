# The genre model

BeatLight's context tier recognises what kind of music is playing and publishes
it on [`ReactiveContext`](composer.md), where any effect in the user's stack may
read it. (It used to select a lighting profile outright; the composer replaced
that, so the genre is now an input rather than a switch.) It runs a pretrained
neural classifier **entirely on the phone**. No audio, no features and no embeddings
leave the device, ever — that is a hard requirement of this feature, not a
preference, and it is why there is no "just call an API" path anywhere in here.

The model is generalised, not trained on anyone's library; see the deviations
table in [beatlight.md](beatlight.md).

## What it is

Two frozen graphs from MTG's Essentia model zoo, stacked:

| Stage | Model | In | Out |
|---|---|---|---|
| Embedding | **Discogs-EffNet** (`discogs-effnet-bs64-1`), EfficientNet-B0 | `[patches, 128, 96]` mel | `[patches, 1280]` |
| Head | **genre_discogs400** (`genre_discogs400-discogs-effnet-1`) | `[patches, 1280]` | `[patches, 400]` sigmoid |

The embedding model was trained on ~3.3 M tracks of Discogs editorial metadata;
the head predicts 400 Discogs *styles* (`Electronic---Trance`,
`Rock---Post-Punk`, `Stage & Screen---Soundtrack`, …). The `Parent---Style`
string is what reaches `ReactiveContext` verbatim; nothing maps it to a smaller
vocabulary, because the composer replaced the profile selector that needed one.

### Licence and attribution

> **Discogs-EffNet** and **genre_discogs400**
> © Music Technology Group, Universitat Pompeu Fabra —
> <https://essentia.upf.edu/models.html>
> Licensed **CC BY-NC-ND 4.0** (<https://creativecommons.org/licenses/by-nc-nd/4.0/>).
>
> Alonso-Jiménez, P., Serra, X., & Bogdanov, D. (2022).
> *Music Representation Learning Based on Editorial Metadata from Discogs.*
> ISMIR 2022.

**ND is why the weights are not in this repository and not in the APK.**
Downloading and running the original, unmodified artifacts with attribution is
what the licence allows; shipping them, or shipping the ONNX files derived from
them, is not. `/models/` is gitignored and no `.pb`, `.onnx` or weight file is
ever committed. **NC** means this is fine for a personal wearable and not fine in
anything commercial.

## The pipeline

```
 LightingEngine window   ~2.08 s of mono float at FeatureConfig.sampleRate (22050)
        │
        ▼  Resampler (audio/dsp)                        linear, state kept across windows
 16 kHz mono                                            ~33270 samples
        │
        ▼  EffnetMelSpectrogram
 frames        512-sample Hann, hop 256, first frame centred at 0   -> ~130 frames
 spectrum      |rfft|, 257 bins
 mel           96 triangular bands, Slaney warping, linear slopes,
               unit_tri normalisation, applied to |X|^2
 compression   log10(10000 * energy + 1)
        │
        ▼  patches of 128 frames, hop 128               -> [1, 128, 96]
 discogs-effnet.onnx                                    -> [1, 1280]
        │
        ▼
 genre_discogs400.onnx                                  -> [1, 400] sigmoid
        │
        ▼  mean over patches, argmax + 4 runners-up
 GenreState("Electronic---Techno", 0.267, t, [...])
        │
        ▼  published on ReactiveContext; any effect may read it
```

There is no debouncer and no profile selector on the end of that chain any more.
Both belonged to the `EffectProfile` system the composer replaced: a rolling
majority existed to stop a *switch* flapping between whole looks. The genre is
now one input among many on a context an effect chooses what to do with, and a
`section_dimmer`-style consumer that wants stability can smooth it itself.

### Front-end constants, and where they come from

Every number above is transcribed from the Essentia algorithms that produced the
training features, not chosen. A frozen graph has no opinion about whether its
input is right — feed it a mel spectrogram computed slightly differently and it
returns confident nonsense.

| Setting | Value | Source |
|---|---|---|
| sample rate | 16000 | `genre_discogs400-...json` → `inference.sample_rate` |
| frame / hop | 512 / 256 | `TensorflowPredictEffnetDiscogs` `_frameSize`, `_hopSize` |
| window | Hann, symmetric, **not** normalised | `TensorflowInputMusiCNN`, `Windowing(normalized=false)` |
| bands | 96 | `TensorflowInputMusiCNN` |
| warping | `slaneyMel` | ” |
| weighting | `linear` | ” |
| normalize | `unit_tri` | ” |
| band type | **power** | `MelBands.type` default |
| bounds | 0 – 8000 Hz | `TensorflowInputMusiCNN` |
| compression | `log10(10000*x + 1)` | `UnaryOperator(shift=1, scale=10000)` then `log10` |
| patch | 128 frames, hop 128 | `TensorflowPredictEffnetDiscogs` |

Four of those are easy to get wrong and each one silently degrades the model:

1. **`type = power`.** The filterbank multiplies *squared* magnitudes. It is a
   `MelBands` default, so Essentia's own configuration never mentions it.
2. **`normalize = unit_tri`** divides each triangle by its theoretical area
   `(fstep1 + fstep2)/2`, not by the bin weights that actually landed in it.
3. **`weighting = linear`** means the triangle slopes are linear **in Hz** even
   though the corners are mel-spaced. The intuitive reading — mel-linear slopes,
   which is what librosa does — tilts every band.
4. **The compression is `log10(scale*x + shift)`, not `log10(x + shift)*scale`.**
   Essentia's `UnaryOperator` applies its function *first* and then shift/scale,
   so the `log10(10000x+1)` here is built from two operators in sequence.

Two divergences from Essentia are deliberate and unobservable:

- Essentia's `Windowing` defaults to `zeroPhase = true`, a circular rotation by
  half a frame. Circular shifts change phase only, and only magnitudes are used.
- Essentia's `FrameCutter` zero-pads a final partial frame. `EffnetMelSpectrogram`
  stops at the last complete one: padded tail frames are never part of a patch we
  keep, and dropping them makes the frame count a closed form on both sides of
  the Python/Kotlin port.

### Why `confidence` is a raw sigmoid

`genre_discogs400` ends in a sigmoid, not a softmax. It is multi-label: the 400
scores do not sum to 1, and sibling styles ("Techno", "Minimal Techno", "Deep
Techno") legitimately fire together. `GenreState.confidence` is therefore the
winner's raw activation. Renormalising to sum 1 would divide by a number that
*grows* with how many styles fired — pushing confidence down exactly when the
model is most certain.

The only gate on it is `OnnxGenreClassifier`'s `minConfidence`, below which a
window is reported as "nothing useful to say" — and its default is **`0f`**, i.e.
open. It is a constructor parameter rather than a constant because a caller with
a reason to be strict should be able to say so; nothing in the app currently
does, since the composer hands the confidence to effects rather than acting on
it, and gating in two places would just make the number a lie in one of them.

### Sample rates

`LightingEngine` sizes the context window from its own `FeatureConfig.sampleRate`
(22050 Hz), not from `GenreClassifier.sampleRate`. So `OnnxGenreClassifier`
declares 22050 — the rate windows honestly arrive at — and resamples to the
model's 16 kHz itself, reusing `audio/dsp/Resampler`. The resampler's state is
kept across windows on purpose: windows arrive back to back, and resetting per
window would stamp a discontinuity into every one of them.

The window is sized **from the patch grid**, not chosen:
`EffnetMelSpectrogram.secondsForPatches(1)` — 2.048 s — plus 1.5% slack, so
`DEFAULT_WINDOW_SECONDS` is about 2.08 s. The slack is the only reason it is not
exactly one patch: it is headroom for the linear resampler landing a sample short,
which would otherwise cost the whole patch.

It used to be 3.0 s, which produced 187 frames of which a single 128-frame patch
consumed 128 — **about a third of every window's mel work computed and thrown
away**, every window, forever. Sizing to the grid instead means ~98% of the stream
reaches the model, and inference runs a little more often for less CPU per second
of audio.

### It runs on its own thread

`LightingEngine` confines its analysis and render loops to a single thread,
because they share a `FeatureExtractor`. Genre inference is the deliberate
exception and gets its own `genreDispatcher`: an EffNet pass is tens of
milliseconds, long enough to stall the render loop for whole frames and show up
as a hitch in the lights every time the classifier fires. It is safe because it
shares nothing with the loops — it is handed a copied window — and its result is
published back through the work dispatcher, which stays the only place the
scene's genre field is written. `close()` is `@Synchronized` against `run` for
the same reason: cancellation can land mid-inference.

## Producing the artifacts

Full detail in [`tools/README.md`](../tools/README.md). Short version:

```bash
cd tools
uv sync
uv run python download_models.py     # .pb + .json  -> /models
uv run python convert_to_onnx.py     # .onnx        -> /models
uv run python dump_reference.py      # reference    -> /testdata
```

Conversion is not a formality. The published embedding graph is "bs64": batch 64
is folded into the constant shape of its `Reshape` nodes, so ONNX Runtime rejects
any other batch size at run time. `convert_to_onnx.py` rewrites those constants
to `-1` and then proves a batch-1 run reproduces row 0 of a batch-64 run exactly
(max abs diff 0.0). Without it the phone would do 64 patches' work for one.

Resolved tensor names, read out of the converted graphs rather than assumed:

| Model | Input | Output |
|---|---|---|
| `discogs-effnet-bs64-1.onnx` | `serving_default_melspectrogram:0` `[batch,128,96]` | `PartitionedCall:1` `[batch,1280]` |
| `genre_discogs400-discogs-effnet-1.onnx` | `serving_default_model_Placeholder:0` `[batch,1280]` | `PartitionedCall:0` `[batch,400]` |

`OnnxGenreClassifier` prefers these names but falls back to the session's only
input/output if a re-conversion names them differently.

## Where the models live at run time

`filesDir/genre-models/`, managed by `GenreModelStore`:

| File | |
|---|---|
| `discogs-effnet-bs64-1.onnx` | 16.0 MB |
| `genre_discogs400-discogs-effnet-1.onnx` | 2.1 MB |
| `genre_discogs400-discogs-effnet-1.json` | 15 KB — the class labels are read from here |

**Not `assets/`.** Putting them in assets means committing the weights and
shipping them in every APK, which is the thing the ND clause is about. So the app
carries the code and the models arrive separately, like a downloadable asset
pack.

**No automatic download either**, and not for lack of trying: the two `.onnx`
files do not exist upstream in the form this app needs, because the batch rewrite
is local. There is no URL to fetch. Installation is therefore an explicit act —
`adb push` during development (recipe in `tools/README.md`), or
`GenreModelStore.install(name, stream)` from a document picker or from a host you
control. `install` writes to a `.part` and renames, so a stream that dies halfway
cannot leave a truncated `.onnx` behind.

Labels ship *with* the weights rather than being hard-coded, for two reasons: a
label list that can drift out of order relative to the weights is a silent
mislabelling bug, and copying 400 strings out of an ND-licensed distribution into
the source tree is exactly what the licence is about. `GenreLabels` parses the
`"classes"` array by hand — `org.json` is stubbed to return zeros under
`unitTests.isReturnDefaultValues = true`, so a JSON library would be untestable
here.

### The models-not-installed state

A fresh install has no models. That is the normal case, not an error:

- `GenreModelStore.isInstalled` is false and `.missing` names what is absent —
  what a "models not installed" UI shows.
- `OnnxGenreClassifier.create` returns null, and `AppContainer` falls back to
  `NoGenreClassifier`.
- The pipeline runs end to end and every stack still renders; effects simply see
  a genre of `unknown`.

A model that is *present but unloadable* degrades the same way, once: the failure
is logged at first use, `isAvailable` goes false, and every later window
short-circuits. A broken install must not cost a session-length stream of
exceptions on the analysis thread. Loading is lazy, so `create` never touches
ONNX Runtime and cannot throw during startup.

## Runtime and cost

`com.microsoft.onnxruntime:onnxruntime-android:1.27.0`, **CPU execution provider
only**. No NNAPI delegate: NNAPI is deprecated as of Android 15, and ORT's NNAPI
EP partitions most of an EfficientNet back onto the CPU anyway, so a delegate
buys partition overhead and a second code path to debug. One patch every three
seconds is a few tens of milliseconds of CPU; the analysis thread has a whole
50 Hz hop of slack to absorb it.

**APK cost is the real price of this feature, and it is large.** Measured on the
debug APK built from this commit:

`app-debug.apk` is **138.9 MB**, of which ONNX Runtime's native libraries are
**116.2 MB** — 84%, leaving 22.0 MB for everything else the app was already
carrying. AGP stores `.so` uncompressed in a debug APK, and the module sets no
`abiFilters`, so all four ABIs ship:

| ABI | in the APK (stored) | deflated |
|---|---|---|
| arm64-v8a | 28.1 MB | 10.7 MB |
| armeabi-v7a | 20.1 MB | 9.7 MB |
| x86_64 | 34.1 MB | — |
| x86 | 34.0 MB | — |

Two levers, neither taken here because they change how the app is packaged for
everyone and that is not this change's business:

- `splits { abi { ... } }` or `ndk { abiFilters += "arm64-v8a" }` — drops the
  three ABIs a phone will never load.
- `packaging { jniLibs { useLegacyPackaging = true } }` — compresses at the cost
  of extracting on install.

An arm64-only, compressed release build would carry ~11 MB of ONNX Runtime.
ORT's AAR declares `minSdkVersion 24`, below this module's 26, so nothing else
had to move.

The model files themselves add 18 MB **on the device**, not in the APK.

## Testing

`app/src/test/java/com/tailapp/genre/`:

- **`EffnetMelSpectrogramTest`** diffs the Kotlin front-end against
  `tools/dump_reference.py`. There is no self-evident correct answer to assert
  for a neural front-end — the only meaningful check is "does this produce the
  same numbers as the implementation the model was trained with".
  Achieved parity: **max |Kotlin − Python| = 5.5e-6** over 187x96 log-mel bands
  whose range is 0..5.8, i.e. ~1e-6 relative. The test's bound is 5e-5, an order
  of magnitude of headroom for another JVM's rounding. Perturbing the mel by
  ±5.6e-6 moves the 400 class probabilities by under 4e-6 and never reorders the
  top 5, so the remaining float32/float64 gap is not a classification difference.
  The parity tests **skip with a reason** when `testdata/genre_reference.json` is
  absent, because regenerating it costs a TensorFlow install; the structural
  tests (frame counts, patch slicing, band spacing, compression ordering) always
  run.
- **`OnnxGenreClassifierTest`** covers everything decidable without the weights:
  the models-absent and partially-installed paths, atomic install, label parsing
  and its failure modes, argmax→label mapping, and that `alternatives` are
  ordered best-first and exclude the winner.

Inference itself is not unit-tested: `libonnxruntime.so` is an Android native
library and this project has no instrumented tests. `testdata/genre_reference.json`
carries the host-run embedding and probabilities so an on-device run can be
checked against a known-good one when that becomes worth doing.
