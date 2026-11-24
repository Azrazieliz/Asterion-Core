"""Behaviour regression for scoped, masked, held-out Fusion head training."""
from copy import deepcopy
from fusion_wd_training import DEPLOYMENT_SCORE_THRESHOLD, fit

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
# actually accepts, not at the lower 0.20 decoder collection floor.
assert DEPLOYMENT_SCORE_THRESHOLD == 0.32
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
