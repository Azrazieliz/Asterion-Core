"""Behaviour regression for scoped, masked, held-out Fusion head training."""
from copy import deepcopy
from fusion_wd_training import DEPLOYMENT_SCORE_THRESHOLD, fit, verify_deployment_manifest

rows = []
for split in ("train", "validation"):
    for i in range(14):
        label = i % 2
        rows.append(dict(source_group=f"{split}-{i}", image_id=i, region="A",
                         scope="subject", split=split, features=[2 * label - 1, i % 3],
                         labels={"HC001": label}))
manifest = {"scope_by_id": {"HC001": "subject", "HC002": "subject", "EN001": "image"}, "rows": rows}
result = fit(manifest)
assert set(result) == {"HC001"}
assert result["HC001"]["precision"] == result["HC001"]["recall"] == 1
# Precision and recall must be checked at the same score the Android mapper
# actually accepts after feedback, not at the lower 0.20 collection floor.
from pathlib import Path
runtime = (Path(__file__).resolve().parents[1] /
           "android_app/app/src/main/java/com/ailm/android/runtime/StandaloneRuntime.kt").read_text()
assert 'calibrate(mapped, if (model?.id == "asterion-wd-vit-tagger-v3") 0.40' in runtime
assert DEPLOYMENT_SCORE_THRESHOLD == 0.40
head = result["HC001"]
assert head["decision_threshold"] == DEPLOYMENT_SCORE_THRESHOLD
assert head["true_positive"] == 7 and head["false_positive"] == 0
assert head["true_negative"] == 7 and head["false_negative"] == 0
assert head["train_positive_sources"] == head["train_negative_sources"] == 7
assert head["validation_positive_sources"] == head["validation_negative_sources"] == 7
assert head["validation_rows"] == 14
retracted = deepcopy(manifest)
for row in retracted["rows"]:
    row["labels"].clear()
assert not fit(retracted)
for problem in ("leak", "scope", "duplicate"):
    bad = deepcopy(manifest)
    if problem == "leak":
        bad["rows"][-1]["source_group"] = bad["rows"][0]["source_group"]
    elif problem == "scope":
        bad["rows"][0]["labels"]["EN001"] = 1
    else:
        bad["rows"].append(bad["rows"][0])
    try:
        fit(bad)
    except ValueError:
        continue
    raise AssertionError(problem)
print("Passed masked training, withheld labels, retraction, split leakage, duplicate and scope checks")

# Structural provenance negative cases. Not a real-artwork accuracy claim.
import hashlib
verified = deepcopy(manifest)
verified["dataset_kind"] = "owner_reviewed"
for record in verified["rows"]:
    id_, label = next(iter(record["labels"].items()))
    record.update(owner_reviewed=True, rights="owner_approved",
                  source_sha256=hashlib.sha256(record["source_group"].encode()).hexdigest(),
                  positive_ids=[id_] if label else [],
                  negative_ids=[] if label else [id_])
assert verify_deployment_manifest(verified)
for fault in ("synthetic", "missing_negative", "unreviewed", "unapproved_rights"):
    bad = deepcopy(verified)
    if fault == "synthetic": bad["dataset_kind"] = "synthetic_fixture"
    elif fault == "missing_negative":
        next(r for r in bad["rows"] if 0 in r["labels"].values())["negative_ids"].clear()
    elif fault == "unreviewed": bad["rows"][0]["owner_reviewed"] = False
    else: bad["rows"][0]["rights"] = "unknown"
    try: verify_deployment_manifest(bad)
    except ValueError: continue
    raise AssertionError("Production training admitted " + fault)
print("PASS: owner approval, rights and explicit negative masks guard production training")
