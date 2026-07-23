"""Export BeatNet's CRNN to ONNX as a *streaming* graph.

BeatNet ships two halves: a CRNN that turns one 272-dimensional feature vector
into ``[beat, downbeat, non-beat]`` probabilities, and a two-stage particle
filter that decodes those activations into beat times. **Only the CRNN is
exported here.** TailApp keeps its own decoder (``beat/BeatTracker.kt``), and
``ActivationSource`` is the seam the CRNN drops into.

Why a streaming export
----------------------
``BeatNet.model.BDA`` is causal but its ``forward`` keeps the recurrent state in
*instance attributes* (``self.hidden`` / ``self.cell``), reassigned on every
call. Exported as-is, that state would be frozen into the graph as a constant
zero initialiser and every frame would be decoded as if it were the first.
Worse, in the original the state is never reset between files, so a second
``process()`` call starts from wherever the previous one left off.

So this script rewraps the module: hidden and cell state become explicit inputs
and explicit outputs, and the softmax that ``BDA.final_pred`` applies separately
is folded in. The result is a pure function
``(features, h0, c0) -> (probs, hn, cn)`` — no hidden mutable state anywhere,
which is what makes ``CrnnActivationSource.reset()`` able to guarantee a clean
session.

Note for anyone expecting a GRU: **BeatNet's recurrent layer is an LSTM**
(``nn.LSTM(150, 150, num_layers=2, batch_first=True)``), so there are *two*
state tensors, not one. The project plan says GRU; the checkpoint says
otherwise.

What is asserted before the file is written
-------------------------------------------
1. The checkpoint loads with no missing keys (BeatNet itself loads with
   ``strict=False``, which would silently tolerate a renamed layer).
2. Feeding the exported graph one frame at a time, threading ``hn``/``cn``
   back in, reproduces a single whole-sequence PyTorch run over the same input
   to within a tight tolerance. That is the streaming contract; if it does not
   hold the file is not written.
3. ONNX Runtime and PyTorch agree on the same input.

Usage:
    uv run tools/download_beatnet.py       # first — unpacks the checkpoints
    uv run tools/export_beatnet.py [--model 1|2|3]

Output: ``<repo>/models/beatnet-crnn-model{N}.onnx`` (~2.0 MB).

BeatNet is CC BY 4.0, (c) Mojtaba Heydari et al. — see tools/README.md.
"""

# /// script
# requires-python = ">=3.9,<3.10"
# dependencies = [
#     "torch==2.5.1",
#     "numpy==1.23.5",
#     "onnx==1.16.1",
#     "onnxruntime==1.19.2",
# ]
# ///

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
import torch.nn as nn
import torch.nn.functional as F

REPO_ROOT = Path(__file__).resolve().parent.parent
MODELS_DIR = REPO_ROOT / "models"
BEATNET_PKG = MODELS_DIR / "beatnet" / "pkg"

# BeatNet's own constants, transcribed from BeatNet/BeatNet.py's constructor.
# They are asserted against the checkpoint below rather than trusted.
DIM_IN = 272
NUM_CELLS = 150
NUM_LAYERS = 2
NUM_CLASSES = 3  # [beat, downbeat, non-beat]

OPSET = 17

INPUT_NAMES = ("features", "h0", "c0")
OUTPUT_NAMES = ("probs", "hn", "cn")


def _load_bda(model_index: int):
    """Imports BeatNet's own module definition and loads a checkpoint into it."""
    if not (BEATNET_PKG / "BeatNet" / "model.py").is_file():
        raise SystemExit(f"{BEATNET_PKG} is not unpacked — run tools/download_beatnet.py first")
    sys.path.insert(0, str(BEATNET_PKG))
    from BeatNet.model import BDA  # noqa: E402  (deliberately after the sys.path edit)

    model = BDA(DIM_IN, NUM_CELLS, NUM_LAYERS, "cpu")
    weights = BEATNET_PKG / "BeatNet" / "models" / f"model_{model_index}_weights.pt"
    if not weights.is_file():
        raise SystemExit(f"missing checkpoint {weights}")
    state = torch.load(weights, map_location="cpu")

    # BeatNet loads with strict=False. Doing the same but *reporting* what it
    # tolerated is the difference between "the weights loaded" and "some of the
    # weights loaded and the rest are random".
    result = model.load_state_dict(state, strict=False)
    if result.missing_keys:
        raise SystemExit(
            f"checkpoint {weights.name} is missing {result.missing_keys}; those layers would be "
            "left at their random initialisation. Refusing to export."
        )
    if result.unexpected_keys:
        print(f"  note     checkpoint carries unused keys {result.unexpected_keys}")
    model.eval()
    return model


class StreamingBDA(nn.Module):
    """``BDA`` with its recurrent state lifted out of the instance and into the signature.

    The layers are shared with the loaded ``BDA`` (not copied), so this is the
    same arithmetic, not a reimplementation of it. The one addition is the
    softmax over the class axis, which the original applies separately in
    ``final_pred``.

    ``features`` is ``[1, frames, 272]``. ``frames`` is a dynamic axis: the phone
    always passes 1, and the verification below passes a whole sequence to prove
    the two agree.
    """

    def __init__(self, bda: nn.Module):
        super().__init__()
        self.dim_in = bda.dim_in
        self.conv_out = bda.conv_out
        self.conv1 = bda.conv1
        self.linear0 = bda.linear0
        self.lstm = bda.lstm
        self.linear = bda.linear

    def forward(self, features: torch.Tensor, h0: torch.Tensor, c0: torch.Tensor):
        # Exactly BDA.forward's shape dance, with -1s where BDA used values read
        # off the input's concrete shape, so the frame axis stays dynamic.
        x = torch.reshape(features, (-1, self.dim_in))       # [frames, 272]
        x = x.unsqueeze(0).transpose(0, 1)                   # [frames, 1, 272]
        x = F.max_pool1d(F.relu(self.conv1(x)), 2)           # [frames, 2, 131]
        x = torch.reshape(x, (x.shape[0], -1))               # [frames, 262]
        x = self.linear0(x)                                  # [frames, 150]
        x = torch.reshape(x, (1, -1, self.conv_out))         # [1, frames, 150]
        x, (hn, cn) = self.lstm(x, (h0, c0))
        out = self.linear(x)                                 # [1, frames, 3]
        out = out.transpose(1, 2)                            # [1, 3, frames]
        # BDA.final_pred is Softmax(dim=0) applied to out[0], i.e. over the class
        # axis of a [3, frames] tensor. Here that axis is 1.
        probs = torch.softmax(out, dim=1)
        return probs, hn, cn


def _zero_state() -> tuple[torch.Tensor, torch.Tensor]:
    h = torch.zeros(NUM_LAYERS, 1, NUM_CELLS, dtype=torch.float32)
    return h, h.clone()


def _sequence_reference(model: StreamingBDA, features: torch.Tensor) -> np.ndarray:
    """One whole-sequence PyTorch run from zero state — the thing streaming must match."""
    h0, c0 = _zero_state()
    with torch.no_grad():
        probs, _, _ = model(features, h0, c0)
    return probs.numpy()


def _stream_through_onnx(session: ort.InferenceSession, features: torch.Tensor) -> np.ndarray:
    """Runs the exported graph frame by frame, threading the state back in."""
    frames = features.shape[1]
    h = np.zeros((NUM_LAYERS, 1, NUM_CELLS), dtype=np.float32)
    c = np.zeros((NUM_LAYERS, 1, NUM_CELLS), dtype=np.float32)
    out = np.zeros((1, NUM_CLASSES, frames), dtype=np.float32)
    data = features.numpy()
    for t in range(frames):
        probs, h, c = session.run(
            list(OUTPUT_NAMES),
            {
                INPUT_NAMES[0]: data[:, t : t + 1, :],
                INPUT_NAMES[1]: h,
                INPUT_NAMES[2]: c,
            },
        )
        out[:, :, t] = probs[:, :, 0]
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--model",
        type=int,
        choices=(1, 2, 3),
        default=1,
        help="which BeatNet checkpoint: 1 = GTZAN (default, most general), 2 = Ballroom, 3 = Rock corpus",
    )
    parser.add_argument("--frames", type=int, default=200, help="sequence length used for verification")
    args = parser.parse_args()

    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    torch.manual_seed(0)

    print(f"loading BeatNet checkpoint {args.model}")
    bda = _load_bda(args.model)

    # Shape sanity: the constants above are BeatNet's, but the checkpoint is the
    # authority on what the graph actually is.
    assert bda.linear.out_features == NUM_CLASSES, bda.linear.out_features
    assert bda.lstm.hidden_size == NUM_CELLS and bda.lstm.num_layers == NUM_LAYERS
    assert bda.lstm.bidirectional is False, "a bidirectional LSTM cannot stream"
    print(
        f"  arch     conv1={tuple(bda.conv1.weight.shape)} linear0={tuple(bda.linear0.weight.shape)} "
        f"lstm=LSTM({bda.lstm.input_size}, {bda.lstm.hidden_size}, layers={bda.lstm.num_layers}) "
        f"linear={tuple(bda.linear.weight.shape)}"
    )

    model = StreamingBDA(bda).eval()

    # A deterministic stand-in for real features. Absolute values do not matter
    # here — this checks that streaming and whole-sequence evaluation agree, and
    # for that any input with enough dynamic range will do. dump_beat_reference.py
    # is where real madmom features go through the graph.
    features = torch.rand(1, args.frames, DIM_IN, dtype=torch.float32) * 3.0

    reference = _sequence_reference(model, features)

    # --- torch-side streaming check, before anything is written ---------------
    h, c = _zero_state()
    streamed = np.zeros_like(reference)
    with torch.no_grad():
        for t in range(args.frames):
            probs, h, c = model(features[:, t : t + 1, :], h, c)
            streamed[:, :, t] = probs.numpy()[:, :, 0]
    torch_delta = float(np.max(np.abs(streamed - reference)))
    print(f"  streaming vs whole-sequence (torch):     max abs diff {torch_delta:.3e}")
    if torch_delta > 1e-5:
        raise SystemExit("the rewrapped module does not stream equivalently; refusing to export")

    # --- export ---------------------------------------------------------------
    target = MODELS_DIR / f"beatnet-crnn-model{args.model}.onnx"
    h0, c0 = _zero_state()
    torch.onnx.export(
        model,
        (features[:, :1, :], h0, c0),
        str(target),
        input_names=list(INPUT_NAMES),
        output_names=list(OUTPUT_NAMES),
        # Only the frame axis moves. Batch is pinned to 1: the recurrent state is
        # per-session and the phone has exactly one session.
        dynamic_axes={"features": {1: "frames"}, "probs": {2: "frames"}},
        opset_version=OPSET,
        do_constant_folding=True,
    )
    onnx.checker.check_model(onnx.load(str(target)))
    print(f"  exported {target.name} ({target.stat().st_size:,} bytes, opset {OPSET})")

    # --- ONNX Runtime verification -------------------------------------------
    session = ort.InferenceSession(str(target), providers=["CPUExecutionProvider"])
    for tensor in session.get_inputs():
        print(f"  input    name={tensor.name!r:<12} shape={tensor.shape} type={tensor.type}")
    for tensor in session.get_outputs():
        print(f"  output   name={tensor.name!r:<12} shape={tensor.shape} type={tensor.type}")

    whole = session.run(
        list(OUTPUT_NAMES),
        {INPUT_NAMES[0]: features.numpy(), INPUT_NAMES[1]: h0.numpy(), INPUT_NAMES[2]: c0.numpy()},
    )[0]
    ort_delta = float(np.max(np.abs(whole - reference)))
    print(f"  whole-sequence ORT vs torch:             max abs diff {ort_delta:.3e}")

    frame_by_frame = _stream_through_onnx(session, features)
    stream_delta = float(np.max(np.abs(frame_by_frame - reference)))
    print(f"  frame-by-frame ORT vs whole-sequence:    max abs diff {stream_delta:.3e}")

    if max(ort_delta, stream_delta) > 1e-5:
        target.unlink(missing_ok=True)
        raise SystemExit("the exported graph does not agree with PyTorch; the file has been removed")

    print(
        "\nBeatNet is CC BY 4.0, (c) Mojtaba Heydari et al. (ISMIR 2021). "
        "Do not commit this file; /models/ is gitignored."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
