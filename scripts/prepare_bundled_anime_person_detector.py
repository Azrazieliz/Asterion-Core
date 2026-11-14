#!/usr/bin/env python3
"""Pin and validate the MIT-licensed YOLOv8n anime-person detector.

The model contains one 'person' class, not identities. Its boxes are used to
crop artwork for WD ViT v3. This script runs in release CI and fails closed on
any supply-chain or tensor-contract mismatch. No weights live in git.
"""
import hashlib
import json
import os
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "android_app/app/src/main/assets/bundled_anime_person_v11_n.onnx"
URL = ("https://huggingface.co/deepghs/anime_person_detection/resolve/"
       "c594ec7/person_detect_v1.1_n/model.onnx")
SHA256 = "6fcdfc6af18b3a26aca62aad0e00faffbeea23413abf868808d2d98d399999c9"
EXPECTED_BYTES = 12_102_560


def verify(path: Path) -> None:
    import onnx

    if path.stat().st_size != EXPECTED_BYTES:
        raise ValueError("Anime-person model size mismatch")
    hash_obj = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hash_obj.update(chunk)
    if hash_obj.hexdigest() != SHA256:
        raise ValueError("Anime-person model digest mismatch")
    model = onnx.load(str(path), load_external_data=False)
    onnx.checker.check_model(model)
    graph = model.graph
    inputs = [x for x in graph.input if x.name not in {i.name for i in graph.initializer}]
    outputs = list(graph.output)
    if len(inputs) != 1 or len(outputs) != 1:
        raise ValueError("Detector must have one image input and one output")
    def shape(tensor):
        return [dim.dim_value if dim.HasField("dim_value") else -1
                for dim in tensor.type.tensor_type.shape.dim]
    x, y = shape(inputs[0]), shape(outputs[0])
    # Upstream uses dynamic batch/spatial dimensions. Do not mistake ONNX
    # graph metadata [-1,3,-1,-1] -> [-1,-1,-1] for a broken model.
    if (len(x) != 4 or x[0] not in (-1, 1) or x[1] != 3 or
        x[2] not in (-1, 640) or x[3] not in (-1, 640) or
        len(y) != 3 or y[0] not in (-1, 1)):
        raise ValueError(f"Unexpected YOLOv8n graph contract: {x} -> {y}")
    if inputs[0].type.tensor_type.elem_type != 1 or outputs[0].type.tensor_type.elem_type != 1:
        raise ValueError("Only float32 detector is supported")

    # Execute the actual pinned ONNX artifact, not just a structural checker.
    # This validates the tensor contract that Android consumes at 640 px.
    import numpy as np
    import onnxruntime as ort
    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    runtime_inputs = session.get_inputs()
    if len(runtime_inputs) != 1 or runtime_inputs[0].type != "tensor(float)":
        raise ValueError("Expected one float32 input for native Android ONNX")
    output = session.run(None, {
        runtime_inputs[0].name: np.zeros((1, 3, 640, 640), dtype=np.float32)
    })
    runtime_shapes = [list(v.shape) for v in output]
    if len(output) != 1 or runtime_shapes[0] not in ([1, 5, 8400], [1, 8400, 5]):
        raise ValueError(f"Detector inference output incompatible: {runtime_shapes}")
    if not np.isfinite(output[0]).all():
        raise ValueError("Detector produced NaN/Infinity on bounded sample input")
    print(json.dumps({"model": "deepghs/person_detect_v1.1_n",
                      "sha256": SHA256, "bytes": EXPECTED_BYTES,
                      "graph_input": x, "graph_output": y,
                      "inference_output": runtime_shapes[0],
                      "runtime": "onnxruntime-cpu", "license": "MIT"}))


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    if OUT.is_file():
        try:
            verify(OUT)
            return
        except Exception:
            OUT.unlink()
    tmp = OUT.with_suffix(".onnx.part")
    tmp.unlink(missing_ok=True)
    request = urllib.request.Request(URL, headers={"User-Agent": "AsterionCore-person-detector/1.0"})
    with urllib.request.urlopen(request, timeout=150) as reader, tmp.open("wb") as writer:
        for block in iter(lambda: reader.read(1024 * 1024), b""):
            writer.write(block)
    try:
        verify(tmp)
        tmp.replace(OUT)
    except Exception:
        tmp.unlink(missing_ok=True)
        raise


if __name__ == "__main__":
    main()
