"""Behaviour regression for scoped, masked, held-out Fusion head training."""
from copy import deepcopy
from fusion_wd_training import fit

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
