#!/usr/bin/env python3
"""
Convert raw YOLOv8n ONNX [1,84,8400] → TFLite [1,8400,84] with sigmoid applied.

Strategy: The raw ONNX outputs [1, 84, 8400] where the 84-dim vector contains:
  - [0:4] = cx, cy, w, h (box coordinates, direct values)
  - [4:84] = 80 class logits (need sigmoid → probabilities)

We add minimal ONNX nodes:
  1. Transpose [1, 84, 8400] → [1, 8400, 84]
  2. Slice boxes [1, 8400, 4] and class scores [1, 8400, 80]
  3. Sigmoid the class scores
  4. Concat boxes + sigmoid_scores → [1, 8400, 84]

The YoloDetector.kt already handles YOLOv5-format output [1, N, 5+C] via
the m >= 5 path (picks argmax class from columns 4+). NMS runs in Kotlin
via DetectionProcessor.nms() — no need to bake it into the TFLite graph.
"""

import os, sys, shutil, subprocess
import numpy as np
import onnx
from onnx import numpy_helper, TensorProto
import onnx.helper as oh

INPUT_MODEL = "/tmp/yolov8n.onnx"
OUTPUT_ONNX = "/tmp/yolov8n_preproc.onnx"
OUTPUT_DIR = "/tmp/tflite_output"
OUTPUT_TFLITE = "/home/z/my-project/AimbotPro/AimbotProV3/app/src/main/assets/models/yolov8n.tflite"


def add_preproc(model: onnx.ModelProto) -> onnx.ModelProto:
    """Transpose + sigmoid class scores, output [1, 8400, 84]."""
    g = model.graph
    nodes = list(g.node)
    inits = list(g.initializer)
    raw = g.output[0].name  # [1, 84, 8400]

    # 1) Transpose → [1, 8400, 84]
    t = "transposed"
    nodes.append(oh.make_node("Transpose", [raw], [t], perm=[0, 2, 1], name=t))

    # 2) Slice boxes [:, :, :4] and scores [:, :, 4:84]
    boxes = "boxes"      # [1, 8400, 4]
    sc_raw = "sc_raw"    # [1, 8400, 80]
    for tag, starts, ends, out in [
        ("bx", [0, 0, 0], [1, 8400, 4], boxes),
        ("sc", [0, 0, 4], [1, 8400, 84], sc_raw),
    ]:
        for suffix, val in [("st", starts), ("en", ends), ("ax", [0, 1, 2])]:
            inits.append(numpy_helper.from_array(np.array(val, dtype=np.int64), name=f"{tag}_{suffix}"))
        nodes.append(oh.make_node("Slice", [t, f"{tag}_st", f"{tag}_en", f"{tag}_ax"], [out], name=out))

    # 3) Sigmoid class scores
    sc_sig = "sc_sig"    # [1, 8400, 80]
    nodes.append(oh.make_node("Sigmoid", [sc_raw], [sc_sig], name=sc_sig))

    # 4) Concat boxes + sigmoid_scores → [1, 8400, 84]
    final = "output"     # [1, 8400, 84]
    nodes.append(oh.make_node("Concat", [boxes, sc_sig], [final], axis=2, name=final))

    # Replace graph output
    del g.output[:]
    g.output.append(oh.make_tensor_value_info(final, TensorProto.FLOAT, [1, 8400, 84]))

    del g.node[:]
    g.node.extend(nodes)
    del g.initializer[:]
    g.initializer.extend(inits)
    return model


def main():
    print(f"Loading {INPUT_MODEL}...")
    model = onnx.load(INPUT_MODEL)
    onnx.checker.check_model(model)
    inp_info = [(i.name, [d.dim_value for d in i.type.tensor_type.shape.dim]) for i in model.graph.input]
    out_info = [(o.name, [d.dim_value for d in o.type.tensor_type.shape.dim]) for o in model.graph.output]
    print(f"  Input:  {inp_info}")
    print(f"  Output: {out_info}")

    print("\nAdding transpose + sigmoid preprocessing...")
    model = add_preproc(model)
    onnx.checker.check_model(model)
    onnx.save(model, OUTPUT_ONNX)
    print(f"  Saved → {OUTPUT_ONNX}")
    out_info2 = [(o.name, [d.dim_value for d in o.type.tensor_type.shape.dim]) for o in model.graph.output]
    print(f"  New output: {out_info2}")

    # Clean previous output
    import shutil as sh
    if os.path.exists(OUTPUT_DIR):
        sh.rmtree(OUTPUT_DIR)

    print(f"\nConverting → TFLite...")
    env = {**os.environ, "TF_CPP_MIN_LOG_LEVEL": "3"}
    r = subprocess.run(
        [sys.executable, "-m", "onnx2tf", "-i", OUTPUT_ONNX, "-o", OUTPUT_DIR, "--non_verbose"],
        capture_output=True, text=True, timeout=300, env=env,
    )

    tflites = []
    for root, _, files in os.walk(OUTPUT_DIR):
        for f in files:
            if f.endswith(".tflite"):
                tflites.append(os.path.join(root, f))

    if r.returncode != 0:
        print(f"  onnx2tf exit {r.returncode}")
        if r.stderr:
            print(f"  stderr: {r.stderr[-1200:]}")
    if not tflites:
        print("FATAL: no .tflite produced!")
        sys.exit(1)

    for t in tflites:
        print(f"  candidate: {t} ({os.path.getsize(t):,} B)")

    best = max(tflites, key=lambda p: os.path.getsize(p))
    os.makedirs(os.path.dirname(OUTPUT_TFLITE), exist_ok=True)
    shutil.copy2(best, OUTPUT_TFLITE)
    print(f"\n✅ {OUTPUT_TFLITE} ({os.path.getsize(OUTPUT_TFLITE):,} bytes)")


if __name__ == "__main__":
    main()
