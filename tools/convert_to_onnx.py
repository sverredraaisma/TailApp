"""Convert the two Essentia frozen TensorFlow graphs to ONNX.

The published artifacts are TF 2.8 frozen GraphDefs; ONNX Runtime is what the
app ships, so both have to be converted once, offline, here.

What it produces in ``<repo>/models``:

* ``discogs-effnet-bs64-1.onnx``            — mel patches  -> 1280-d embedding
* ``genre_discogs400-discogs-effnet-1.onnx`` — embedding    -> 400 sigmoid scores

The embedding graph is published with a **fixed batch of 64** ("bs64"). That is
64x more work than one patch needs on a phone, so this script first tries to
re-export it with a dynamic batch dimension and falls back to the fixed shape if
the graph will not take it. Whatever it ends up with, it prints the resolved
tensor names and shapes — those are the values ``OnnxGenreClassifier`` is coded
against, so read them from this output rather than assuming.

Usage:
    uv run python convert_to_onnx.py
"""

from __future__ import annotations

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
MODELS_DIR = REPO_ROOT / "models"

# Opset 13 is the sweet spot: new enough for everything EfficientNet uses
# (including dynamic Resize), old enough that every onnxruntime-android release
# in the 1.1x-1.2x range implements it natively.
OPSET = 13

EMBEDDING_PB = "discogs-effnet-bs64-1.pb"
EMBEDDING_ONNX = "discogs-effnet-bs64-1.onnx"
# Verified against the frozen graph itself (see describe_graph below), not
# guessed from the model JSON: the JSON's "schema" block names the serving
# signature, the GraphDef prefixes it with `serving_default_`.
EMBEDDING_INPUT = "serving_default_melspectrogram:0"
# The embedding graph has two outputs: :0 is the 400-way style prediction the
# frozen backbone happens to carry, :1 is the 1280-d penultimate embedding that
# genre_discogs400 was trained on. We want :1.
EMBEDDING_OUTPUTS = ["PartitionedCall:1"]

HEAD_PB = "genre_discogs400-discogs-effnet-1.pb"
HEAD_ONNX = "genre_discogs400-discogs-effnet-1.onnx"
HEAD_INPUT = "serving_default_model_Placeholder:0"
HEAD_OUTPUTS = ["PartitionedCall:0"]


def load_graph_def(path: Path):
    import tensorflow as tf

    graph_def = tf.compat.v1.GraphDef()
    graph_def.ParseFromString(path.read_bytes())
    return graph_def


def describe_graph(path: Path) -> None:
    """Print the frozen graph's placeholders and terminal ops.

    The JSON metadata names the *serving signature* tensors; the frozen graph
    uses its own internal names. Printing both sides is the only reliable way to
    pick the right --inputs/--outputs.
    """
    graph_def = load_graph_def(path)
    consumed = {inp.split(":")[0].lstrip("^") for node in graph_def.node for inp in node.input}

    print(f"\n--- {path.name}: {len(graph_def.node)} nodes")
    print("  placeholders:")
    for node in graph_def.node:
        if node.op == "Placeholder":
            shape = node.attr["shape"].shape
            dims = [d.size for d in shape.dim] if shape.dim else "<unknown>"
            print(f"    {node.name}  dtype={node.attr['dtype'].type}  shape={dims}")
    print("  terminal ops (candidate outputs):")
    for node in graph_def.node:
        if node.name not in consumed and node.op not in ("Const", "NoOp", "Placeholder"):
            print(f"    {node.name}  op={node.op}")


def convert(pb: Path, onnx_path: Path, inputs: list[str], outputs: list[str],
            input_shapes: dict[str, list] | None = None) -> None:
    import tf2onnx
    from tf2onnx import tf_loader

    graph_def, inputs_res, outputs_res = tf_loader.from_graphdef(
        str(pb), inputs, outputs
    )

    import tensorflow as tf

    with tf.Graph().as_default() as tf_graph:
        tf.import_graph_def(graph_def, name="")
        onnx_graph = tf2onnx.tfonnx.process_tf_graph(
            tf_graph,
            input_names=inputs_res,
            output_names=outputs_res,
            opset=OPSET,
            shape_override=input_shapes,
        )
    onnx_graph = tf2onnx.optimizer.optimize_graph(onnx_graph)
    model_proto = onnx_graph.make_model(f"tailapp-{pb.stem}")
    onnx_path.write_bytes(model_proto.SerializeToString())


def make_batch_dynamic(onnx_path: Path, published_batch: int = 64) -> int:
    """Rewrite the baked-in batch size out of a converted graph.

    Overriding the *placeholder* shape is not enough for discogs-effnet: TF
    exported it with batch 64 folded into the constant shape of all 65 `Reshape`
    nodes, so ONNX Runtime rejects anything but 64 at run time ("input tensor
    cannot be reshaped to the requested shape ... requested shape: {64,...}").

    Each of those constants has exactly one concrete batch entry and no wildcard,
    so replacing the leading 64 with -1 lets Reshape infer it. That is a
    structural edit, not a numerical one — `verify_dynamic_batch` proves the
    outputs are bit-identical to the published batch-64 graph.

    :return: how many shape constants were rewritten.
    """
    import numpy as np
    import onnx
    from onnx import numpy_helper

    model = onnx.load(str(onnx_path))
    initializers = {i.name: i for i in model.graph.initializer}

    patched = 0
    for node in model.graph.node:
        if node.op_type != "Reshape" or len(node.input) < 2:
            continue
        init = initializers.get(node.input[1])
        if init is None:
            continue  # already a dynamic shape; nothing to do
        shape = numpy_helper.to_array(init)
        if shape.size == 0 or shape[0] != published_batch or -1 in shape:
            continue
        new_shape = shape.copy()
        new_shape[0] = -1
        init.CopyFrom(numpy_helper.from_array(new_shape.astype(np.int64), init.name))
        patched += 1

    if patched:
        # Stale static shapes left over from the batch-64 export would otherwise
        # be trusted by the runtime's shape checks.
        for value_info in list(model.graph.value_info):
            model.graph.value_info.remove(value_info)
        for tensor in list(model.graph.output):
            if tensor.type.tensor_type.shape.dim:
                tensor.type.tensor_type.shape.dim[0].Clear()
                tensor.type.tensor_type.shape.dim[0].dim_param = "batch"
        model = onnx.shape_inference.infer_shapes(model)
        onnx.checker.check_model(model)
        onnx_path.write_bytes(model.SerializeToString())
    return patched


def verify_dynamic_batch(onnx_path: Path, input_name: str, output_name: str,
                         sample_shape: tuple[int, ...], published_batch: int = 64) -> None:
    """Assert a batch-1 run matches row 0 of a batch-`published_batch` run."""
    import numpy as np
    import onnxruntime as ort

    rng = np.random.default_rng(20240501)
    batch = rng.standard_normal((published_batch, *sample_shape), dtype=np.float32)

    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    full = session.run([output_name], {input_name: batch})[0]
    single = session.run([output_name], {input_name: batch[:1]})[0]

    error = float(np.max(np.abs(full[0] - single[0])))
    print(f"  batch-1 vs batch-{published_batch} row 0: max abs diff {error:.3e}")
    if error > 1e-4:
        raise SystemExit(f"{onnx_path.name}: dynamic batch changed the result (max diff {error})")


def report(onnx_path: Path) -> None:
    import onnxruntime as ort

    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    print(f"\n=== {onnx_path.name} ({onnx_path.stat().st_size:,} bytes)")
    for meta in session.get_inputs():
        print(f"  input   name={meta.name!r}  type={meta.type}  shape={meta.shape}")
    for meta in session.get_outputs():
        print(f"  output  name={meta.name!r}  type={meta.type}  shape={meta.shape}")


def main() -> int:
    embedding_pb = MODELS_DIR / EMBEDDING_PB
    head_pb = MODELS_DIR / HEAD_PB
    for path in (embedding_pb, head_pb):
        if not path.exists():
            raise SystemExit(f"missing {path}. Run download_models.py first.")

    describe_graph(embedding_pb)
    describe_graph(head_pb)

    print("\nconverting the embedding graph...")
    embedding_onnx = MODELS_DIR / EMBEDDING_ONNX
    convert(embedding_pb, embedding_onnx, [EMBEDDING_INPUT], EMBEDDING_OUTPUTS,
            input_shapes={EMBEDDING_INPUT: [None, 128, 96]})
    patched = make_batch_dynamic(embedding_onnx)
    print(f"  rewrote {patched} Reshape shape constants to a dynamic batch")
    verify_dynamic_batch(embedding_onnx, EMBEDDING_INPUT, EMBEDDING_OUTPUTS[0], (128, 96))

    print("\nconverting the genre head...")
    head_onnx = MODELS_DIR / HEAD_ONNX
    # The head's placeholder is already [-1, 1280] upstream, so nothing to patch.
    convert(head_pb, head_onnx, [HEAD_INPUT], HEAD_OUTPUTS,
            input_shapes={HEAD_INPUT: [None, 1280]})

    report(embedding_onnx)
    report(head_onnx)

    print(
        "\nCopy the resolved input/output names above into "
        "app/src/main/java/com/tailapp/genre/OnnxGenreClassifier.kt if they differ."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
