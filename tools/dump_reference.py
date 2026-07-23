"""Produce the reference the Kotlin unit tests diff against.

Runs the whole context-tier pipeline once, in Python, on a signal this script
synthesises deterministically (no audio file is committed, and none is needed):

    signal -> mel patches -> discogs-effnet embedding -> genre_discogs400 head

and writes every intermediate to ``<repo>/testdata/genre_reference.json``.
``EffnetMelSpectrogramTest`` reads the signal and the mel matrix out of that file
and asserts the Kotlin front-end reproduces it; the embedding and the class
probabilities are there so an on-device run can be checked against a known-good
host run.

Arrays travel as base64-encoded little-endian float32 rather than JSON numbers,
so the round trip is bit-exact and the file stays small (~350 KB).

The mel front-end below is a line-by-line transcription of what Essentia's
``TensorflowPredictEffnetDiscogs`` does — see docs/genre-model.md for the source
references. It is duplicated in Kotlin as ``EffnetMelSpectrogram``; that
duplication is the thing this script exists to police.

Usage:
    uv run python dump_reference.py
"""

from __future__ import annotations

import base64
import json
import math
import sys
from pathlib import Path

import numpy as np

REPO_ROOT = Path(__file__).resolve().parent.parent
MODELS_DIR = REPO_ROOT / "models"
TESTDATA_DIR = REPO_ROOT / "testdata"

# --- Front-end constants, all from Essentia -----------------------------------
# TensorflowInputMusiCNN (src/algorithms/spectral/tensorflowinputmusicnn.cpp) and
# TensorflowPredictEffnetDiscogs (.../machinelearning/, _frameSize / _hopSize).
SAMPLE_RATE = 16000
FRAME_SIZE = 512
HOP_SIZE = 256
NUM_BANDS = 96
LOW_FREQUENCY_BOUND = 0.0
HIGH_FREQUENCY_BOUND = SAMPLE_RATE / 2.0
COMPRESSION_SCALE = 10000.0
COMPRESSION_SHIFT = 1.0
PATCH_FRAMES = 128
PATCH_HOP = 128

WINDOW_SECONDS = 3.0

EMBEDDING_ONNX = MODELS_DIR / "discogs-effnet-bs64-1.onnx"
EMBEDDING_INPUT = "serving_default_melspectrogram:0"
EMBEDDING_OUTPUT = "PartitionedCall:1"

HEAD_ONNX = MODELS_DIR / "genre_discogs400-discogs-effnet-1.onnx"
HEAD_INPUT = "serving_default_model_Placeholder:0"
HEAD_OUTPUT = "PartitionedCall:0"

LABELS_JSON = MODELS_DIR / "genre_discogs400-discogs-effnet-1.json"


# --- Slaney mel scale (essentia/src/essentia/essentiamath.h) -------------------

_LIN_SLOPE = 3.0 / 200.0
_MIN_LOG_HZ = 1000.0
_MIN_LOG_MEL = _MIN_LOG_HZ * _LIN_SLOPE
_LOG_STEP = math.log(6.4) / 27.0


def hz_to_mel_slaney(hz: float) -> float:
    if hz < _MIN_LOG_HZ:
        return hz * _LIN_SLOPE
    return _MIN_LOG_MEL + math.log(hz / _MIN_LOG_HZ) / _LOG_STEP


def mel_to_hz_slaney(mel: float) -> float:
    if mel < _MIN_LOG_MEL:
        return mel / _LIN_SLOPE
    return _MIN_LOG_HZ * math.exp((mel - _MIN_LOG_MEL) * _LOG_STEP)


# --- The front-end ------------------------------------------------------------

def band_edges() -> np.ndarray:
    """The 98 triangle corner frequencies, equally spaced on the Slaney mel scale.

    Mirrors MelBands::calculateFilterFrequencies, including its running
    accumulation of the increment (rather than i * increment) so the Kotlin side
    can accumulate the same way and land on the same floating-point values.
    """
    increment = (hz_to_mel_slaney(HIGH_FREQUENCY_BOUND) - hz_to_mel_slaney(LOW_FREQUENCY_BOUND)) / (NUM_BANDS + 1)
    edges = np.empty(NUM_BANDS + 2, dtype=np.float64)
    mel = hz_to_mel_slaney(LOW_FREQUENCY_BOUND)
    for i in range(NUM_BANDS + 2):
        edges[i] = mel_to_hz_slaney(mel)
        mel += increment
    return edges


def filterbank() -> np.ndarray:
    """96 x 257 triangular weights: linear weighting, `unit_tri` normalization.

    Transcribes TriangularBands::createFilters. `weighting=linear` means the
    triangle slopes are linear in Hz even though the corners are mel-spaced, and
    `unit_tri` divides by the *theoretical* triangle area (fstep1 + fstep2) / 2
    rather than by the summed bin weights.
    """
    spectrum_size = FRAME_SIZE // 2 + 1
    edges = band_edges()
    frequency_scale = (SAMPLE_RATE / 2.0) / (spectrum_size - 1)
    weights = np.zeros((NUM_BANDS, spectrum_size), dtype=np.float64)

    for i in range(NUM_BANDS):
        low, centre, high = edges[i], edges[i + 1], edges[i + 2]
        step_up = centre - low
        step_down = high - centre

        j_begin = math.ceil(low / frequency_scale)
        j_end = math.floor(high / frequency_scale)
        if j_end >= spectrum_size:
            raise SystemExit("band edge above Nyquist")

        for j in range(j_begin, j_end + 1):
            bin_frequency = j * frequency_scale
            if bin_frequency < centre:
                weights[i, j] = (bin_frequency - low) / step_up
            else:
                weights[i, j] = (high - bin_frequency) / step_down

        weights[i] /= (step_up + step_down) / 2.0

    return weights


def hann_window() -> np.ndarray:
    """Essentia's symmetric Hann, unnormalized (`normalized=false`).

    Note the `size - 1` denominator: this is the symmetric window, not numpy's
    periodic `np.hanning` equivalent... which is in fact exactly np.hanning.
    Spelled out anyway so the Kotlin side has something literal to match.
    """
    n = np.arange(FRAME_SIZE, dtype=np.float64)
    return 0.5 - 0.5 * np.cos(2.0 * np.pi * n / (FRAME_SIZE - 1))


def frame_signal(samples: np.ndarray) -> np.ndarray:
    """Cut `samples` into 512-sample frames at hop 256, first frame centred at 0.

    Essentia's FrameCutter defaults to `startFromZero=false`, i.e. the first
    frame spans [-256, 256) — equivalent to prepending frameSize/2 zeros. Unlike
    Essentia we stop at the last *complete* frame instead of zero-padding the
    tail: the padded tail frames are never part of a patch we use, and dropping
    them keeps the frame count a closed-form expression on both sides.
    """
    padded = np.concatenate([np.zeros(FRAME_SIZE // 2, dtype=np.float64), samples.astype(np.float64)])
    if padded.size < FRAME_SIZE:
        return np.zeros((0, FRAME_SIZE), dtype=np.float64)
    count = 1 + (padded.size - FRAME_SIZE) // HOP_SIZE
    strides = (padded.strides[0] * HOP_SIZE, padded.strides[0])
    return np.lib.stride_tricks.as_strided(padded, shape=(count, FRAME_SIZE), strides=strides).copy()


def mel_spectrogram(samples: np.ndarray) -> np.ndarray:
    """float32 [frames, 96] log-compressed mel bands."""
    frames = frame_signal(samples)
    if frames.shape[0] == 0:
        return np.zeros((0, NUM_BANDS), dtype=np.float32)

    windowed = frames * hann_window()
    # Essentia rotates the frame by half its length (`zeroPhase=true`). That is a
    # circular shift, so it changes only the phase — the magnitudes below are
    # identical with or without it, and neither side bothers.
    magnitude = np.abs(np.fft.rfft(windowed, n=FRAME_SIZE, axis=1))
    # MelBands defaults to type='power': the filterbank is applied to the squared
    # magnitudes, not the magnitudes.
    bands = (magnitude ** 2) @ filterbank().T
    compressed = np.log10(COMPRESSION_SCALE * bands + COMPRESSION_SHIFT)
    return compressed.astype(np.float32)


def to_patches(mel: np.ndarray) -> np.ndarray:
    """[patches, 128, 96]; empty when the window is shorter than one patch."""
    if mel.shape[0] < PATCH_FRAMES:
        return np.zeros((0, PATCH_FRAMES, NUM_BANDS), dtype=np.float32)
    count = 1 + (mel.shape[0] - PATCH_FRAMES) // PATCH_HOP
    return np.stack([mel[i * PATCH_HOP: i * PATCH_HOP + PATCH_FRAMES] for i in range(count)])


# --- The test signal ----------------------------------------------------------

def synthesise(seconds: float = WINDOW_SECONDS, sample_rate: int = SAMPLE_RATE) -> np.ndarray:
    """A deterministic, broadband, rhythmic test signal.

    Not music, but it exercises everything the front-end can get wrong: a
    transient with strong low-frequency content (the kick), stable harmonic
    partials across the mel range (the chord), and seeded broadband noise bursts
    (the hats). A pure sine would leave most of the 96 bands at the log-of-zero
    floor and hide any filterbank error.
    """
    n = int(round(seconds * sample_rate))
    t = np.arange(n, dtype=np.float64) / sample_rate
    rng = np.random.default_rng(0x7A11)

    signal = np.zeros(n, dtype=np.float64)

    # A sustained A2 minor triad, three partials each.
    for root in (110.0, 130.81, 164.81):
        for harmonic, gain in ((1, 0.30), (2, 0.12), (3, 0.05)):
            signal += gain * np.sin(2.0 * np.pi * root * harmonic * t + harmonic * 0.7)

    beat = 60.0 / 120.0  # 120 BPM
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


# --- Serialisation ------------------------------------------------------------

def blob(array: np.ndarray) -> str:
    return base64.b64encode(np.ascontiguousarray(array, dtype=np.float32).tobytes()).decode("ascii")


def main() -> int:
    import onnxruntime as ort

    for path in (EMBEDDING_ONNX, HEAD_ONNX, LABELS_JSON):
        if not path.exists():
            raise SystemExit(f"missing {path}. Run download_models.py then convert_to_onnx.py first.")

    labels = json.loads(LABELS_JSON.read_text(encoding="utf-8"))["classes"]

    signal = synthesise()
    mel = mel_spectrogram(signal)
    patches = to_patches(mel)
    print(f"signal   {signal.shape} @ {SAMPLE_RATE} Hz")
    print(f"mel      {mel.shape}  min {mel.min():.4f}  max {mel.max():.4f}  mean {mel.mean():.4f}")
    print(f"patches  {patches.shape}")
    if patches.shape[0] == 0:
        raise SystemExit("window is shorter than one 128-frame patch")

    embedder = ort.InferenceSession(str(EMBEDDING_ONNX), providers=["CPUExecutionProvider"])
    head = ort.InferenceSession(str(HEAD_ONNX), providers=["CPUExecutionProvider"])

    embeddings = embedder.run([EMBEDDING_OUTPUT], {EMBEDDING_INPUT: patches})[0]
    probabilities = head.run([HEAD_OUTPUT], {HEAD_INPUT: embeddings})[0]
    # More than one patch would be averaged; at 3 s there is exactly one.
    pooled = probabilities.mean(axis=0)
    print(f"embedding {embeddings.shape}  head {probabilities.shape}")

    order = np.argsort(pooled)[::-1][:10]
    print("\ntop 10 (a synthetic signal is out of distribution; the ranking is a "
          "regression fixture, not a claim about the audio):")
    for rank, index in enumerate(order, start=1):
        print(f"  {rank:2d}. {pooled[index]:.6f}  {labels[index]}")

    TESTDATA_DIR.mkdir(parents=True, exist_ok=True)
    payload = {
        "_comment": (
            "Generated by tools/dump_reference.py. Arrays are base64 little-endian float32. "
            "Regenerate after any change to the front-end constants."
        ),
        "sampleRate": SAMPLE_RATE,
        "frameSize": FRAME_SIZE,
        "hopSize": HOP_SIZE,
        "numBands": NUM_BANDS,
        "patchFrames": PATCH_FRAMES,
        "patchHop": PATCH_HOP,
        "signalSamples": int(signal.size),
        "melFrames": int(mel.shape[0]),
        "patchCount": int(patches.shape[0]),
        "embeddingSize": int(embeddings.shape[1]),
        "classCount": int(pooled.size),
        "signal": blob(signal),
        "mel": blob(mel),
        "embedding": blob(embeddings[0]),
        "probabilities": blob(pooled),
        "topLabels": [labels[int(i)] for i in order],
        "topScores": [float(pooled[int(i)]) for i in order],
    }
    target = TESTDATA_DIR / "genre_reference.json"
    target.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"\nwrote {target} ({target.stat().st_size:,} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
