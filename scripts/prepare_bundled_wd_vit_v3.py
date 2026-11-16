#!/usr/bin/env python3
"""Create the reproducible in-app WD-ViT v3 visual-tagger package.

Used by the alternate Android release workflow. Model weights remain under
their upstream Apache-2.0 license; they are NOT committed to git. Failure to
download, hash, or validate the actual ONNX graph is fatal to the release.
"""
import csv
import hashlib
import json
import os
from pathlib import Path
import shutil
import urllib.request
from zipfile import ZipFile, ZIP_STORED

REVISION = "7f6b584d0bd3f55c4531f14ba3d4761b2bccdc0f"
REPOSITORY = "SmilingWolf/wd-vit-tagger-v3"
MODEL_SHA256 = "35f23693620b668f4d53fd3c62bf65e40af739bc52c7eb0fbc49258b58d065b6"
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "android_app/app/src/main/assets/bundled_wd_vit_v3.zip"
WORK = Path(os.environ.get("RUNNER_TEMP", "/tmp")) / "asterion-wd-v3"
WORK.mkdir(parents=True, exist_ok=True)


def download(filename: str, expected: str | None = None) -> Path:
    dest = WORK / filename
    if dest.is_file() and expected and hashlib.sha256(dest.read_bytes()).hexdigest() == expected:
        return dest
    url = f"https://huggingface.co/{REPOSITORY}/resolve/{REVISION}/{filename}"
    request = urllib.request.Request(url, headers={"User-Agent": "AsterionCore-bundle/1.0"})
    temp = dest.with_suffix(dest.suffix + ".part")
    h = hashlib.sha256()
    with urllib.request.urlopen(request, timeout=120) as response, temp.open("wb") as target:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            target.write(chunk)
            h.update(chunk)
    if expected and h.hexdigest() != expected:
        temp.unlink(missing_ok=True)
        raise RuntimeError(f"Upstream hash mismatch for {filename}: {h.hexdigest()}")
    temp.replace(dest)
    return dest


def main() -> None:
    import onnx

    model_path = download("model.onnx", MODEL_SHA256)
    csv_path = download("selected_tags.csv")
    graph = onnx.load(str(model_path), load_external_data=False).graph
    graph_inputs = [node for node in graph.input if node.name not in {x.name for x in graph.initializer}]
    if len(graph_inputs) != 1 or len(graph.output) != 1:
        raise ValueError("WD model must expose exactly one image input and one prediction output")
    inp, output = graph_inputs[0], graph.output[0]

    def shape(node):
        dims = node.type.tensor_type.shape.dim
        return [int(d.dim_value) if d.HasField("dim_value") and d.dim_value > 0 else -1 for d in dims]

    input_shape, output_shape = shape(inp), shape(output)
    if len(input_shape) != 4 or len(output_shape) != 2:
        raise ValueError(f"Unexpected WD graph shapes: {input_shape}, {output_shape}")
    layout = "nchw" if input_shape[1] == 3 else "nhwc" if input_shape[-1] == 3 else None
    if layout is None:
        raise ValueError(f"Unsupported image channels layout: {input_shape}")
    width = input_shape[3] if layout == "nchw" else input_shape[2]
    height = input_shape[2] if layout == "nchw" else input_shape[1]
    if (width, height) != (448, 448):
        raise ValueError(f"Unexpected WD image input: {width}x{height}")
    if inp.type.tensor_type.elem_type != 1 or output.type.tensor_type.elem_type != 1:
        raise ValueError("Expected float32 WD model inputs and outputs")

    with csv_path.open(newline="", encoding="utf-8-sig") as file:
        rows = list(csv.DictReader(file))
    if not rows or any("name" not in row or "category" not in row for row in rows):
        raise ValueError("Missing names/category in selected_tags.csv")
    if len(rows) != output_shape[-1]:
        raise ValueError(f"Label/graph mismatch: CSV {len(rows)}, graph {output_shape[-1]}")
    # Preserve the FOUR native artwork-rating scores under a private prefix.
    # They are NOT general visual tags, do not authorize Fusion IDs and are
    # never conflated with the output class "drawings" of another classifier.
    # Character IDs and all other non-general outputs remain excluded.
    rating_names = {
        row["name"].strip().lower() for row in rows if row["category"] == "9"
    }
    expected_ratings = {"general", "sensitive", "questionable", "explicit"}
    if rating_names != expected_ratings or sum(row["category"] == "9" for row in rows) != 4:
        raise ValueError(f"Unexpected WD rating head: {sorted(rating_names)}")
    labels = [
        row["name"].strip().replace("_", " ") if row["category"] == "0"
        else f"__wd_rating_{row['name'].strip().lower()}" if row["category"] == "9"
        else f"__wd_excluded_{i}"
        for i, row in enumerate(rows)
    ]
    if len(labels) != len(set(x.casefold() for x in labels)):
        raise ValueError("Model labels contain ambiguous normalized duplicates")
    contract = {
        "tokenizer": {"type": "none"},
        "image_preprocessing": {
            "enabled": True, "width": width, "height": height,
            "channels": 3, "color_space": "bgr", "resize_mode": "pad_square",
            "scale": 1.0, "mean": [0.0, 0.0, 0.0], "std": [1.0, 1.0, 1.0],
        },
        "inputs": [{"name": inp.name, "source": "image", "data_type": "float32",
                    "layout": layout, "shape": input_shape}],
        "outputs": [{"name": output.name, "index": 0, "data_type": "float32",
                     "shape": output_shape}],
        "output_decoder": {
            "type": "classification", "output_name": output.name,
            "labels": labels, "max_results": 500,
        },
        "confidence_scoring": {"type": "identity", "threshold": 0.20},
    }
    metadata = {
        "model_id": "asterion-wd-vit-tagger-v3",
        "model_file": "model.onnx",
        "display_name": "WD ViT v3 specialist artwork classifier",
        "task": "tag_prediction",
        "supported_tasks": ["tag_prediction"],
        "runtime": "onnx",
        "source": f"https://huggingface.co/{REPOSITORY}/tree/{REVISION}",
        "license": "Apache-2.0",
        "inference_contracts": {"tag_prediction": contract},
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    metadata_path = WORK / "metadata.json"
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    with ZipFile(OUT, "w", compression=ZIP_STORED, allowZip64=True) as zipf:
        zipf.write(model_path, arcname="model.onnx")
        zipf.write(metadata_path, arcname="metadata.json")
    print(json.dumps({
        "package": str(OUT), "bytes": OUT.stat().st_size,
        "model_sha256": MODEL_SHA256, "labels": len(labels),
        "rating_head": sorted(expected_ratings),
        "input": [inp.name, input_shape], "output": [output.name, output_shape],
    }, indent=2))


if __name__ == "__main__":
    main()
