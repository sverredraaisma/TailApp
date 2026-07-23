"""Run BeatNet's own front-end and CRNN on a synthetic signal; dump the result.

This is the fixture ``app/src/test/java/com/tailapp/audio/BeatNetFrontEndParityTest.kt``
and ``.../beat/CrnnActivationSourceTest.kt`` diff against. It exists because a
neural front-end has no self-evident right answer: the only meaningful check is
"do we produce the numbers the model was trained on".

What it writes to ``<repo>/testdata/beat_reference.json``:

* ``signal``        the synthetic audio, float32 @ 22050 Hz (no audio file is
                    committed, and none is needed)
* ``features``      madmom's output for that signal, ``[frames, 272]`` — the
                    exact tensor BeatNet feeds its CRNN. Columns 0..135 are the
                    log-filtered spectrogram, 136..271 its positive difference.
* ``activations``   ``[frames, 3]`` — the exported ONNX graph's softmax output,
                    streamed one frame at a time from zero state
* the resolved front-end geometry (window, hop, filter count, centre
  frequencies) and the ONNX graph's input/output names, shapes and dtypes

Everything upstream runs unmodified: madmom's own pure-Python feature modules and
BeatNet's own ``LOG_SPECT``. See ``download_beatnet.py`` for why madmom is
imported out of an unpacked sdist rather than pip-installed.

Usage:
    uv run tools/download_beatnet.py
    uv run tools/export_beatnet.py
    uv run tools/dump_beat_reference.py [--model 1|2|3]

BeatNet is CC BY 4.0, (c) Mojtaba Heydari et al. — see tools/README.md.
"""

# /// script
# requires-python = ">=3.9,<3.10"
# dependencies = [
#     "numpy==1.23.5",
#     "scipy==1.10.1",
#     "librosa==0.10.2.post1",
#     "mido==1.3.2",
#     "onnxruntime==1.19.2",
# ]
# ///

from __future__ import annotations

import argparse
import base64
import json
import sys
import types
from pathlib import Path

import numpy as np

REPO_ROOT = Path(__file__).resolve().parent.parent
MODELS_DIR = REPO_ROOT / "models"
TESTDATA_DIR = REPO_ROOT / "testdata"
BEATNET_PKG = MODELS_DIR / "beatnet" / "pkg"
MADMOM_SRC = MODELS_DIR / "madmom" / "madmom-0.16.1"

# BeatNet's own front-end constants, from BeatNet/BeatNet.py's constructor:
#   log_spec_hop_length = int(20 * 0.001 * 22050)  ->  441   (50 fps)
#   log_spec_win_length = int(64 * 0.001 * 22050)  ->  1411  (64 ms)
# and from log_spect.py: n_bands=[24], fmin=30, fmax=17000, norm_filters=True,
# log10(1*x + 1), positive stacked differences with diff_ratio=0.5.
SAMPLE_RATE = 22050
WIN_LENGTH = 1411
HOP_SIZE = 441
BANDS_PER_OCTAVE = 24
F_MIN = 30.0
F_MAX = 17000.0
LOG_MUL = 1.0
LOG_ADD = 1.0
DIFF_RATIO = 0.5

NUM_LAYERS = 2
NUM_CELLS = 150
CLASS_NAMES = ("beat", "downbeat", "nonbeat")

SECONDS = 6.0


def _install_madmom_stubs() -> None:
    """Makes madmom's pure-Python feature modules importable without building it.

    ``madmom/__init__.py`` imports every subpackage, and ``madmom/audio/__init__.py``
    imports ``comb_filters`` — both drag in Cython extensions that need a C
    toolchain (and a Windows SDK, which is what actually stopped this on the
    machine it was written on). Nothing on the feature path needs them.

    Registering namespace stubs for those two packages *before* the first import
    means Python resolves ``madmom.audio.signal`` through the stub's ``__path__``
    and never executes either ``__init__``. The modules that do run —
    ``processors``, ``utils``, ``audio.{signal,stft,filters,spectrogram}`` — are
    upstream's files, unmodified. Nothing is monkey-patched.
    """
    if not (MADMOM_SRC / "madmom" / "audio" / "spectrogram.py").is_file():
        raise SystemExit(f"{MADMOM_SRC} is not unpacked — run tools/download_beatnet.py first")
    for name, path in (
        ("madmom", MADMOM_SRC / "madmom"),
        ("madmom.audio", MADMOM_SRC / "madmom" / "audio"),
    ):
        stub = types.ModuleType(name)
        stub.__path__ = [str(path)]
        sys.modules[name] = stub


def synthesise(seconds: float = SECONDS, sample_rate: int = SAMPLE_RATE) -> np.ndarray:
    """A deterministic, broadband, rhythmic test signal.

    Not music, but it exercises everything a beat front-end can get wrong: a
    transient with strong low-frequency content (the kick), stable harmonic
    partials spanning the filterbank (the chord), and seeded broadband noise
    bursts (the hats). A pure sine would leave most of the 136 bands at the
    log-of-zero floor and hide any filterbank error; a signal with no transients
    would leave the whole difference half of the feature vector at zero.

    Deliberately the same recipe as ``dump_reference.py``'s, at this pipeline's
    sample rate: two fixtures that disagree about what "the test signal" sounds
    like are two fixtures nobody can reason about together.
    """
    n = int(round(seconds * sample_rate))
    t = np.arange(n, dtype=np.float64) / sample_rate
    rng = np.random.default_rng(0x7A11)

    signal = np.zeros(n, dtype=np.float64)

    # A sustained A2 minor triad, three partials each.
    for root in (110.0, 130.81, 164.81):
        for harmonic, gain in ((1, 0.30), (2, 0.12), (3, 0.05)):
            signal += gain * np.sin(2.0 * np.pi * root * harmonic * t + harmonic * 0.7)

    beat = 60.0 / 120.0  # 120 BPM -> a beat every 25 frames at 50 fps
    for index in range(int(seconds / beat) + 1):
        start = int(index * beat * sample_rate)
        if start >= n:
            break

        # Kick: 60 Hz sine with an exponential decay and a short click on top.
        length = min(int(0.18 * sample_rate), n - start)
        env = np.exp(-np.arange(length) / (0.045 * sample_rate))
        local = np.arange(length) / sample_rate
        signal[start:start + length] += 0.9 * env * np.sin(2.0 * np.pi * 60.0 * local)
        signal[start:start + length] += 0.25 * np.exp(-np.arange(length) / (0.002 * sample_rate)) \
            * rng.standard_normal(length)

        # Hat on the off-beat: high-passed noise, one-pole difference.
        offset = start + int(0.5 * beat * sample_rate)
        length = min(int(0.05 * sample_rate), max(0, n - offset))
        if length > 1:
            noise = rng.standard_normal(length)
            noise = np.diff(noise, prepend=noise[0])
            env = np.exp(-np.arange(length) / (0.010 * sample_rate))
            signal[offset:offset + length] += 0.35 * env * noise

    peak = float(np.max(np.abs(signal)))
    signal *= 0.85 / peak
    return signal.astype(np.float32)


def beatnet_features(signal: np.ndarray) -> np.ndarray:
    """BeatNet's ``LOG_SPECT``, verbatim, on ``signal``. Returns ``[frames, 272]``."""
    sys.path.insert(0, str(BEATNET_PKG))
    from BeatNet.log_spect import LOG_SPECT  # noqa: E402  (after the sys.path edit)

    proc = LOG_SPECT(
        sample_rate=SAMPLE_RATE,
        win_length=WIN_LENGTH,
        hop_size=HOP_SIZE,
        n_bands=[BANDS_PER_OCTAVE],
        mode="online",
    )
    # process_audio returns [features, frames]; the model consumes [frames, features].
    return np.ascontiguousarray(proc.process_audio(signal).T, dtype=np.float32)


def filterbank_geometry() -> dict:
    """madmom's resolved filterbank, so the Kotlin test can compare geometry, not just numbers."""
    from madmom.audio.filters import LogarithmicFilterbank
    from madmom.audio.stft import fft_frequencies

    bin_frequencies = fft_frequencies(WIN_LENGTH // 2 + 1, SAMPLE_RATE)
    bank = LogarithmicFilterbank(
        bin_frequencies,
        num_bands=BANDS_PER_OCTAVE,
        fmin=F_MIN,
        fmax=F_MAX,
        norm_filters=True,
    )
    matrix = np.asarray(bank)
    corners = np.asarray(bank.corner_frequencies, dtype=np.float64)
    return {
        "numBins": int(matrix.shape[0]),
        "numFilters": int(matrix.shape[1]),
        "hzPerBin": float(bin_frequencies[1]),
        "centerHz": np.asarray(bank.center_frequencies, dtype=np.float32),
        "cornerLowHz": corners[:, 0].astype(np.float32),
        "cornerHighHz": corners[:, 1].astype(np.float32),
        # norm_filters=True makes every filter sum to 1 over its bins (unit
        # *area*, not unit peak). Dumping the sums lets the Kotlin test assert
        # that rather than take it on trust.
        "filterSums": matrix.sum(axis=0).astype(np.float32),
        "filterPeaks": matrix.max(axis=0).astype(np.float32),
    }


def diff_frames() -> int:
    """madmom's ``diff_ratio`` -> "how many frames back the difference is taken"."""
    from madmom.audio.spectrogram import _diff_frames

    return int(_diff_frames(DIFF_RATIO, hop_size=HOP_SIZE, frame_size=WIN_LENGTH))


def stream_activations(onnx_path: Path, features: np.ndarray):
    """Runs the exported graph one frame at a time, threading the state through."""
    import onnxruntime as ort

    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    inputs = [
        {"name": t.name, "shape": [str(d) for d in t.shape], "type": t.type}
        for t in session.get_inputs()
    ]
    outputs = [
        {"name": t.name, "shape": [str(d) for d in t.shape], "type": t.type}
        for t in session.get_outputs()
    ]

    frames = features.shape[0]
    h = np.zeros((NUM_LAYERS, 1, NUM_CELLS), dtype=np.float32)
    c = np.zeros((NUM_LAYERS, 1, NUM_CELLS), dtype=np.float32)
    activations = np.zeros((frames, len(CLASS_NAMES)), dtype=np.float32)
    for t in range(frames):
        probs, h, c = session.run(
            ["probs", "hn", "cn"],
            {"features": features[np.newaxis, t : t + 1, :], "h0": h, "c0": c},
        )
        activations[t] = probs[0, :, 0]

    # The same graph fed the whole sequence at once must agree — that is the
    # streaming contract, re-checked here on *real* features rather than on the
    # random ones export_beatnet.py used.
    whole = session.run(
        ["probs"],
        {
            "features": features[np.newaxis, :, :],
            "h0": np.zeros((NUM_LAYERS, 1, NUM_CELLS), dtype=np.float32),
            "c0": np.zeros((NUM_LAYERS, 1, NUM_CELLS), dtype=np.float32),
        },
    )[0][0].T
    delta = float(np.max(np.abs(whole - activations)))
    return activations, inputs, outputs, delta


def blob(array: np.ndarray) -> str:
    return base64.b64encode(np.ascontiguousarray(array, dtype=np.float32).tobytes()).decode("ascii")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=int, choices=(1, 2, 3), default=1)
    args = parser.parse_args()

    onnx_path = MODELS_DIR / f"beatnet-crnn-model{args.model}.onnx"
    if not onnx_path.is_file():
        raise SystemExit(f"missing {onnx_path}. Run tools/export_beatnet.py first.")

    _install_madmom_stubs()

    signal = synthesise()
    features = beatnet_features(signal)
    geometry = filterbank_geometry()
    num_filters = geometry["numFilters"]
    frames = features.shape[0]

    print(f"signal    {signal.shape} @ {SAMPLE_RATE} Hz ({SECONDS:g} s)")
    print(f"features  {features.shape}  min {features.min():.4f}  max {features.max():.4f}")
    print(f"filterbank {num_filters} filters over {geometry['numBins']} bins "
          f"({geometry['hzPerBin']:.3f} Hz/bin)")
    print(f"           centres {geometry['centerHz'][0]:.2f} .. {geometry['centerHz'][-1]:.2f} Hz")
    print(f"           filter sums min {geometry['filterSums'].min():.4f} "
          f"max {geometry['filterSums'].max():.4f}  (norm_filters=True -> unit area)")
    print(f"diff      {diff_frames()} frame(s) back, positive part only")

    if features.shape[1] != 2 * num_filters:
        raise SystemExit(
            f"feature width {features.shape[1]} is not 2 x {num_filters}; the spectrogram/difference "
            "split assumed by the Kotlin side no longer holds"
        )

    activations, inputs, outputs, stream_delta = stream_activations(onnx_path, features)
    print(f"\nonnx      {onnx_path.name}")
    for tensor in inputs:
        print(f"  input   name={tensor['name']!r:<12} shape={tensor['shape']} type={tensor['type']}")
    for tensor in outputs:
        print(f"  output  name={tensor['name']!r:<12} shape={tensor['shape']} type={tensor['type']}")
    print(f"  frame-by-frame vs whole-sequence: max abs diff {stream_delta:.3e}")
    if stream_delta > 1e-5:
        raise SystemExit("the exported graph does not stream equivalently on real features")

    beat = activations[:, 0]
    downbeat = activations[:, 1]
    print(f"\nactivations {activations.shape}")
    print(f"  beat      mean {beat.mean():.4f}  max {beat.max():.4f}  frames>0.5: {(beat > 0.5).sum()}")
    print(f"  downbeat  mean {downbeat.mean():.4f}  max {downbeat.max():.4f}  frames>0.5: {(downbeat > 0.5).sum()}")
    peaks = [t for t in range(1, frames - 1) if beat[t] > 0.5 and beat[t] >= beat[t - 1] and beat[t] > beat[t + 1]]
    print(f"  beat peaks at frames {peaks}")
    print("  (a synthetic signal is out of distribution; these are a regression "
          "fixture, not a claim about the audio)")

    TESTDATA_DIR.mkdir(parents=True, exist_ok=True)
    payload = {
        "_comment": (
            "Generated by tools/dump_beat_reference.py from BeatNet 1.1.3 (CC BY 4.0, "
            "(c) Mojtaba Heydari et al.) and madmom 0.16.1. Arrays are base64 "
            "little-endian float32. Regenerate after any change to the front-end."
        ),
        "modelIndex": args.model,
        "onnxFile": onnx_path.name,
        "sampleRate": SAMPLE_RATE,
        "winLength": WIN_LENGTH,
        "hopSize": HOP_SIZE,
        "bandsPerOctave": BANDS_PER_OCTAVE,
        "fMin": F_MIN,
        "fMax": F_MAX,
        "logMultiplier": LOG_MUL,
        "logAdd": LOG_ADD,
        "normFilters": True,
        "diffRatio": DIFF_RATIO,
        "diffFrames": diff_frames(),
        "positiveDiffs": True,
        "framesCentred": True,
        "numBins": geometry["numBins"],
        "numFilters": num_filters,
        "hzPerBin": geometry["hzPerBin"],
        "featureDim": int(features.shape[1]),
        "frames": frames,
        "signalSamples": int(signal.size),
        "classNames": list(CLASS_NAMES),
        "hiddenLayers": NUM_LAYERS,
        "hiddenSize": NUM_CELLS,
        "onnxInputs": inputs,
        "onnxOutputs": outputs,
        "signal": blob(signal),
        # [frames, 272] row-major. Columns 0..numFilters-1 are the log-filtered
        # spectrogram and the rest its positive difference; the Kotlin side
        # slices rather than being handed the same numbers twice.
        "features": blob(features),
        "activations": blob(activations),
        "filterCenterHz": blob(geometry["centerHz"]),
        "filterCornerLowHz": blob(geometry["cornerLowHz"]),
        "filterCornerHighHz": blob(geometry["cornerHighHz"]),
        "filterSums": blob(geometry["filterSums"]),
        "filterPeaks": blob(geometry["filterPeaks"]),
    }
    target = TESTDATA_DIR / "beat_reference.json"
    target.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"\nwrote {target} ({target.stat().st_size:,} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
