"""Fit direct Fusion outputs from reviewed, region-scoped WD feature vectors.

Manifest JSON contains scope_by_id (Fusion ID -> "subject" or "image") and rows.
Each row has source_group (same for derivatives), region, scope, split ("train"
or "validation"), features (pooled float vector), and labels (ID -> 0 or 1).
Only explicit labels are supervised. Regenerate from current reviewed rows on
correction/retraction. Feature vectors must come from the pinned graph below.
"""
import argparse
import json
import re
from pathlib import Path

import numpy as np

# Core's WD inference path admits these per-candidate probabilities at 0.32.
# A validation gate at a lower threshold would approve heads the app rejects.
# Change the host and Android gates together, not independently.
DEPLOYMENT_SCORE_THRESHOLD = 0.40



def verify_deployment_manifest(manifest):
    """Only owner-approved visible pixels and explicit labels can train a released head."""
    if manifest.get("dataset_kind") != "owner_reviewed":
        raise ValueError("Production Fusion training requires reviewed artwork")
    if not manifest.get("rows"):
        raise ValueError("No verified training pixels")
    fingerprint = re.compile(r"[a-f0-9]{64}")
    for row in manifest["rows"]:
        if (row.get("owner_reviewed") is not True or
                row.get("rights") not in ("owner_approved", "licensed_for_training") or
                not fingerprint.fullmatch(str(row.get("source_sha256", "")).lower())):
            raise ValueError("Unverified review, image rights or source fingerprint")
        pos, neg = row.get("positive_ids"), row.get("negative_ids")
        if not isinstance(pos, list) or not isinstance(neg, list):
            raise ValueError("Explicit visually positive and negative IDs required")
        if len(pos) != len(set(pos)) or len(neg) != len(set(neg)) or set(pos) & set(neg):
            raise ValueError("Duplicate or contradictory canonical ID labels")
        labels = row.get("labels", {})
        if set(labels) != set(pos) | set(neg):
            raise ValueError("Unreviewed workbook entries are NOT negatives")
        if any(labels[tag] != (1 if tag in pos else 0) for tag in labels):
            raise ValueError("Review polarity does not match the supervised target")
    return True

def feature_input(graph, output):
    """Trace the actual sigmoid classifier to its pre-projection feature input."""
    producer = {name: node for node in graph.node for name in node.output}
    constants = {value.name for value in graph.initializer}
    sigmoid = producer[output]
    if sigmoid.op_type != "Sigmoid":
        raise ValueError("Unrecognized WD probability output")
    affine = producer[sigmoid.input[0]]
    if affine.op_type == "Add":
        dynamic = [name for name in affine.input if name not in constants]
        if len(dynamic) != 1:
            raise ValueError("Unrecognized WD classifier bias")
        affine = producer[dynamic[0]]
    if affine.op_type not in ("Gemm", "MatMul") or affine.input[1] not in constants:
        raise ValueError("Unrecognized WD projection")
    if any(a.name == "transA" and a.i for a in affine.attribute):
        raise ValueError("Transposed batch input unsupported")
    return affine.input[0]


def fit(manifest):
    scopes = manifest["scope_by_id"]
    if any(not re.fullmatch(r"[A-Z]{2}[0-9]{2,}(?:-[0-9]+)*", tag) for tag in scopes):
        raise ValueError("Expected canonical Fusion IDs")
    samples = {}
    splits = {}
    width = None
    seen = set()
    for row in manifest["rows"]:
        group, region, scope, split = (row[k] for k in ("source_group", "region", "scope", "split"))
        if not group or not region or scope not in ("image", "subject") or split not in ("train", "validation", "test"):
            raise ValueError("Invalid review provenance")
        if splits.setdefault(group, split) != split:
            raise ValueError("Source leakage across splits")
        key = (group, row["image_id"], region, scope)
        if key in seen:
            raise ValueError("Duplicate reviewed observation")
        seen.add(key)
        x = np.asarray(row["features"], dtype=np.float64)
        if x.ndim != 1 or not x.size or not np.isfinite(x).all() or (width and width != x.size):
            raise ValueError("Invalid feature vector")
        width = x.size
        for tag, label in row["labels"].items():
            if scopes.get(tag) != scope or type(label) is not int or label not in (0, 1):
                raise ValueError("Invalid scoped Fusion supervision")
            samples.setdefault(tag, {}).setdefault(split, []).append((x, label, group))

    heads = {}
    for tag, parts in sorted(samples.items()):
        # Count independent sources, including negative evidence, in both splits.
        if any(len({g for _, y, g in parts.get(split, []) if y == value}) < minimum
               for split, minimum in (("train", 5), ("validation", 5)) for value in (0, 1)):
            continue
        x = np.stack([r[0] for r in parts["train"]])
        y = np.array([r[1] for r in parts["train"]])
        mean = x.mean(axis=0)
        scale = np.maximum(x.std(axis=0), 1e-4)
        z = (x - mean) / scale
        w = np.zeros(x.shape[1])
        b = 0.0
        # Spectral-norm upper bound via Frobenius norm, including bias.
        step = 1 / (0.25 * (1 + np.square(z).sum() / len(y)) + 0.01)
        for _ in range(400):
            error = 1 / (1 + np.exp(-np.clip(z @ w + b, -60, 60))) - y
            w -= step * (z.T @ error / len(y) + 0.01 * w)
            b -= step * error.mean()
        weights = w / scale
        bias = float(b - mean @ weights)
        validation = parts["validation"]
        logits = np.array([v @ weights + bias for v, _, _ in validation])
        labels = np.array([y for _, y, _ in validation])
        # Measure performance at the ACTUAL native admission threshold.
        # Lower decoder collection floors are not acceptance thresholds.
        positive = logits >= np.log(
            DEPLOYMENT_SCORE_THRESHOLD / (1.0 - DEPLOYMENT_SCORE_THRESHOLD)
        )
        tp = int((positive & (labels == 1)).sum())
        fp = int((positive & (labels == 0)).sum())
        tn = int(((~positive) & (labels == 0)).sum())
        fn = int(((~positive) & (labels == 1)).sum())
        precision = tp / max(1, tp + fp)
        recall = tp / max(1, tp + fn)
        if precision >= 0.90 and recall >= 0.60:
            heads[tag] = {
                "weights": weights.tolist(), "bias": bias,
                "precision": precision, "recall": recall,
                "true_positive": tp, "false_positive": fp,
                "true_negative": tn, "false_negative": fn,
                "decision_threshold": DEPLOYMENT_SCORE_THRESHOLD,
                "train_positive_sources": len({g for _, y, g in parts["train"] if y == 1}),
                "train_negative_sources": len({g for _, y, g in parts["train"] if y == 0}),
                "validation_positive_sources": len({g for _, y, g in validation if y == 1}),
                "validation_negative_sources": len({g for _, y, g in validation if y == 0}),
                "validation_rows": len(validation),
            }
            # This third split is excluded from both fitting and head selection.
            test = parts.get("test", [])
            if test:
                logits_test = np.array([v @ weights + bias for v, _, _ in test])
                targets = np.array([y for _, y, _ in test])
                accepted = logits_test >= np.log(
                    DEPLOYMENT_SCORE_THRESHOLD / (1.0 - DEPLOYMENT_SCORE_THRESHOLD)
                )
                tp_test = int((accepted & (targets == 1)).sum())
                fp_test = int((accepted & (targets == 0)).sum())
                fn_test = int(((~accepted) & (targets == 1)).sum())
                heads[tag].update({
                    "test_precision": tp_test / max(1, tp_test + fp_test),
                    "test_recall": tp_test / max(1, tp_test + fn_test),
                    "test_positive_sources": len({g for _, y, g in test if y == 1}),
                    "test_negative_sources": len({g for _, y, g in test if y == 0}),
                    "test_rows": len(test),
                })
    return heads


def attach(model, output, manifest, allow_fixture=False):
    from onnx import TensorProto, checker, helper, numpy_helper
    from prepare_bundled_wd_vit_v3 import MODEL_SHA256

    feature = feature_input(model.graph, output)
    if manifest["model_sha256"] != MODEL_SHA256 or manifest["feature_tensor"] != feature:
        raise ValueError("Training vectors were produced by a different encoder")
    if not allow_fixture:
        verify_deployment_manifest(manifest)
    heads = fit(manifest)
    if not allow_fixture:
        heads = {tag: h for tag, h in heads.items()
                 if h.get("test_positive_sources", 0) >= 5 and
                    h.get("test_negative_sources", 0) >= 5 and
                    h.get("test_precision", 0.0) >= 0.90 and
                    h.get("test_recall", 0.0) >= 0.60}
    if not heads:
        raise ValueError("No Fusion outputs qualified on independent reviewed sources")
    ids = sorted(heads)
    weights = np.array([heads[k]["weights"] for k in ids], dtype=np.float32).T.copy()
    bias = np.array([heads[k]["bias"] for k in ids], dtype=np.float32)
    graph = model.graph
    graph.initializer.extend([numpy_helper.from_array(weights, "fusion_weights"),
                              numpy_helper.from_array(bias, "fusion_bias")])
    graph.node.extend([helper.make_node("Gemm", [feature, "fusion_weights", "fusion_bias"], ["fusion_logits"]),
                       helper.make_node("Sigmoid", ["fusion_logits"], ["fusion_scores"]),
                       helper.make_node("Concat", [output, "fusion_scores"], ["wd_and_fusion"], axis=1)])
    previous = graph.output[0].type.tensor_type.shape.dim[-1].dim_value
    del graph.output[:]
    graph.output.append(helper.make_tensor_value_info("wd_and_fusion", TensorProto.FLOAT, [None, previous + len(ids)]))
    checker.check_model(model, full_check=True)
    return ids, {k: {field: value for field, value in heads[k].items() if field not in ("weights", "bias")} for k in ids}


def extract(model_path, manifest):
    """Input images are already reviewed crops or whole artworks as indicated."""
    import hashlib
    import onnx
    import onnxruntime as ort
    from PIL import Image, ImageOps
    from onnx import TensorProto, helper
    from prepare_bundled_wd_vit_v3 import MODEL_SHA256

    with open(model_path, "rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != MODEL_SHA256:
            raise ValueError("Unexpected WD graph")
    model = onnx.load(model_path)
    graph = model.graph
    feature = feature_input(graph, graph.output[0].name)
    del graph.output[:]
    graph.output.append(helper.make_tensor_value_info(feature, TensorProto.FLOAT, [None, None]))
    session = ort.InferenceSession(model.SerializeToString(), providers=["CPUExecutionProvider"])
    spec = session.get_inputs()[0]
    for row in manifest["rows"]:
        with Image.open(row["crop_path"]) as image:
            # The review exporter must supply pixels in the same orientation as Core.
            image = image.convert("RGBA")
            image = ImageOps.contain(image, (448, 448), Image.Resampling.BILINEAR)
            canvas = Image.new("RGB", (448, 448), "white")
            canvas.paste(image, ((448 - image.width) // 2, (448 - image.height) // 2), image)
            packed = np.asarray(canvas, dtype=np.float32)[:, :, ::-1]
        if spec.shape[1] == 3:
            packed = packed.transpose(2, 0, 1)
        row["features"] = session.run([feature], {spec.name: np.ascontiguousarray(packed[None])})[0][0].tolist()
    manifest.update(model_sha256=MODEL_SHA256, feature_tensor=feature)
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("model")
    parser.add_argument("review_manifest")
    parser.add_argument("features_manifest")
    args = parser.parse_args()
    source = json.loads(Path(args.review_manifest).read_text())
    Path(args.features_manifest).write_text(json.dumps(extract(args.model, source), allow_nan=False))
