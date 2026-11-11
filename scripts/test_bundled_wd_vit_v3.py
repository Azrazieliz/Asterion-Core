#!/usr/bin/env python3
"""Real ONNX Runtime smoke acceptance for the APK-bundled WD ViT v3 tagger.

Runs an authentic inference over two deterministic synthetic 448x448 images.
It validates graph compatibility, independent multi-label probabilities and
the exact taxonomy vocabulary packaged with the release. It deliberately does
not claim user-photo classification accuracy or Android device acceptance.
"""
import hashlib
import json
import math
from pathlib import Path
from zipfile import ZipFile

import numpy as np
import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / "android_app/app/src/main/assets/bundled_wd_vit_v3.zip"
EXPECTED_MODEL_SHA256 = "35f23693620b668f4d53fd3c62bf65e40af739bc52c7eb0fbc49258b58d065b6"


def main():
    assert PACKAGE.is_file(), "Model bundle was not prepared for testing"
    with ZipFile(PACKAGE) as archive:
        metadata = json.loads(archive.read("metadata.json"))
        graph = archive.read("model.onnx")
    digest = hashlib.sha256(graph).hexdigest()
    assert digest == EXPECTED_MODEL_SHA256, "Model weights were modified or corrupted"
    contract = metadata["inference_contracts"]["tag_prediction"]
    labels = contract["output_decoder"]["labels"]
    assert len(labels) == 10861, "Model vocabulary length changed"
    assert len(set(labels)) == len(labels), "Duplicate output labels"
    assert contract["image_preprocessing"]["color_space"] == "bgr"
    assert contract["image_preprocessing"]["resize_mode"] == "pad_square"
    assert contract["confidence_scoring"]["type"] == "identity"
    assert any(x == "1girl" or x == "1 girl" for x in labels), "Danbooru visual vocabulary missing"
    assert any(label.startswith("__wd_excluded_") for label in labels), "Rating/identity heads must not leak into generic tags"

    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    session = ort.InferenceSession(graph, sess_options=options, providers=["CPUExecutionProvider"])
    inp = session.get_inputs()
    out = session.get_outputs()
    assert len(inp) == 1 and len(out) == 1, "Tagger must have one input/output"
    assert inp[0].name == contract["inputs"][0]["name"]
    assert out[0].name == contract["outputs"][0]["name"]
    assert inp[0].shape[-3:] == [448, 448, 3] or inp[0].shape[-3:] == [3, 448, 448], "Incorrect image tensor shape"
    assert out[0].shape[-1] == len(labels)

    # The supported preprocessing is BGR, 0..255, white-square pad, NHWC.
    white = np.full((1, 448, 448, 3), 255.0, dtype=np.float32)
    contrast = white.copy()
    contrast[:, 80:368, 80:368, :] = 0
    contrast[:, 160:288, 160:288, 0] = 255
    contrast[:, 190:258, 190:258, 1] = 128
    assert contract["inputs"][0]["layout"] == "nhwc"
    outputs = []
    for image in (white, contrast):
        values = session.run([out[0].name], {inp[0].name: image})[0]
        assert values.shape == (1, len(labels)), f"Incorrect native classifier shape: {values.shape}"
        assert np.isfinite(values).all(), "NaN/Inf classifier probabilities"
        assert values.min() >= -1e-6 and values.max() <= 1 + 1e-6, (
            f"Expected sigmoid probabilities, received range [{values.min()}, {values.max()}]"
        )
        outputs.append(values[0])
    change = float(np.max(np.abs(outputs[1] - outputs[0])))
    assert change > 1e-5, "Outputs are insensitive to fundamentally different input images"
    general = [(labels[i], float(v)) for i,v in enumerate(outputs[1]) if not labels[i].startswith("__wd_excluded_")]
    top = sorted(general, key=lambda row: row[1], reverse=True)[:12]
    result = {
        "status": "passed",
        "scope": "actual CPU ONNX inference; synthetic input only",
        "model_sha256": digest,
        "model_id": metadata["model_id"],
        "label_count": len(labels),
        "general_label_count": len(general),
        "input_shape": inp[0].shape,
        "output_shape": out[0].shape,
        "max_probability_delta_between_test_inputs": change,
        "top_synthetic_pattern_labels": top,
        "not_tested": ["Samsung ARM64 execution", "user-photo accuracy", "Fusion live database coverage", "autonomous gallery organization"],
    }
    report = ROOT / "model_inference_smoke.json"
    report.write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
