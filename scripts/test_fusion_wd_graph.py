"""Test a direct Fusion output on the pinned WD encoder. Synthetic fixtures only."""
import tempfile
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
from PIL import Image
from fusion_wd_training import extract, attach
from prepare_bundled_wd_vit_v3 import WORK

path = WORK / "model.onnx"
with tempfile.TemporaryDirectory() as tmp:
    rows = []
    for i in range(20):
        label = i % 2
        pixels = np.full((448, 448, 3), 245, dtype=np.uint8)
        center = 224 + i % 5
        if label:
            pixels[:, center-55:center+55] = (20, 35, 150)
        else:
            pixels[center-55:center+55, :] = (125, 35, 20)
        pixels[5+i:8+i, 10:14] = 0
        image = Path(tmp) / f"{i}.png"
        Image.fromarray(pixels).save(image)
        rows.append(dict(image_id=i, source_group=str(i), region="frame", scope="image",
                         split="train" if i < 10 else "validation",
                         labels={"OR001": label}, crop_path=str(image)))
    manifest = extract(path, {"rows": rows, "scope_by_id": {"OR001": "image"}})
    baseline = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    spec = baseline.get_inputs()[0]
    sample = np.ascontiguousarray(pixels[:, :, ::-1][None], dtype=np.float32)
    expected = baseline.run(None, {spec.name: sample})[0]
    del baseline
    model = onnx.load(path)
    ids, metrics = attach(model, model.graph.output[0].name, manifest, allow_fixture=True)
    assert ids == ["OR001"] and metrics["OR001"]["validation_rows"] == 10
    extended = ort.InferenceSession(model.SerializeToString(), providers=["CPUExecutionProvider"])
    scores = extended.run(None, {spec.name: sample})[0]
    np.testing.assert_allclose(scores[:, :-1], expected, atol=1e-5, rtol=1e-5)
    assert scores.shape == (1, 10862) and scores[0, -1] > 0.5
print("Pinned WD feature extraction, held-out synthetic head and original 10861 outputs verified")
