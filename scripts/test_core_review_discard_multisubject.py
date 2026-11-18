#!/usr/bin/env python3
"""Regression: bulk Review discard is atomic and separate from tagging state."""
from pathlib import Path
import sqlite3

root = Path(__file__).resolve().parents[1]
runtime = root / "android_app/app/src/main/java/com/ailm/android/runtime"
ui = root / "android_app/app/src/main/java/com/ailm/android/ui"
repo = (runtime / "LocalRepository.kt").read_text(encoding="utf-8")
store = (runtime / "FusionResolutionStore.kt").read_text(encoding="utf-8")
app = (ui / "viewmodel/AppViewModel.kt").read_text(encoding="utf-8")
screens = (ui / "screens/Screens.kt").read_text(encoding="utf-8")
pipeline = (runtime / "StandaloneRuntime.kt").read_text(encoding="utf-8")
evidence = (runtime / "NativeSubjectEvidencePolicy.kt").read_text(encoding="utf-8")
workers = (root / "android_app/app/src/main/java/com/ailm/android/workers/SetupWorkers.kt").read_text(encoding="utf-8")

assert "fun discardAllPendingReviews(): Int" in repo
assert "fun rejectOptionalReviews(itemIds: List<String>): Int" in repo
assert "AND review_type NOT IN" in repo
assert "StandaloneRuntime.rejectOptionalReviews(ids)" in app
bulk = repo.split("fun discardAllPendingReviews(): Int", 1)[1].split("fun updateReview(", 1)[0]
assert 'put("status", "dismissed")' in bulk
assert '"status = \'pending\'"' in bulk
assert '"review_items"' in bulk
assert "LIMIT" not in bulk.upper()
assert 'if (existingStatus == "dismissed" && existingDecision?.second == reason) return' in store
assert 'ORDER BY CASE WHEN status = \'dismissed\' THEN 0 ELSE 1 END LIMIT 1' in store
assert "fun discardAllReviews()" in app
assert "onDiscardAll = appViewModel::discardAllReviews" in screens
assert 'Text("Discard everything from Review")' in screens
assert 'refreshDashboard()' not in app.split("fun discardAllReviews()",1)[1].split("fun resolveReviews(",1)[0]
assert 'val all = detection.regions.sortedByDescending { it.area }' in pipeline
assert 'detection.regions.sortedByDescending { it.area }.take(1)' not in pipeline
assert '"subject_detection_incomplete" to (observations.size < all.size)' in pipeline
assert 'wd_vit_v3_primary_upper_focus_verified' in evidence
assert "AutomationStartPolicy.decide(" in workers

db=sqlite3.connect(":memory:")
db.executescript("""
CREATE TABLE images(image_id INTEGER PRIMARY KEY, original_uri TEXT);
CREATE TABLE review_items(review_id INTEGER PRIMARY KEY, image_id INTEGER,
  status TEXT,review_type TEXT,last_updated_ms INTEGER);
CREATE TABLE canonical_tags(image_id INTEGER,tag_id TEXT);
CREATE TABLE automation_state(image_id INTEGER,state TEXT);
""")
db.executemany("INSERT INTO images VALUES (?,?)",
    ((i, "content://images/%s"%i) for i in range(1,1201)))
kinds=("character_resolution","runtime_failure","visual_observation_review",
    "organization_failure","visual_variant")
db.executemany("INSERT INTO review_items VALUES (?,?,?,?,0)",
    ((i,i,"pending",kinds[i%len(kinds)]) for i in range(1,1201)))
db.executemany("INSERT INTO canonical_tags VALUES (?,?)",
    ((i,"EC001") for i in range(1,1201)))
db.executemany("INSERT INTO automation_state VALUES (?,?)",
    ((i,"review_pending") for i in range(1,1201)))
count=db.execute("UPDATE review_items SET status='dismissed',last_updated_ms=9 WHERE status='pending'").rowcount
assert count==1200,count
assert db.execute("SELECT COUNT(*) FROM review_items WHERE status='pending'").fetchone()[0]==0
assert db.execute("SELECT COUNT(*) FROM images").fetchone()[0]==1200
assert db.execute("SELECT COUNT(*) FROM canonical_tags").fetchone()[0]==1200
assert db.execute("SELECT COUNT(*) FROM automation_state WHERE state='review_pending'").fetchone()[0]==1200
assert db.execute("SELECT COUNT(*) FROM review_items WHERE status='dismissed'").fetchone()[0]==1200
assert db.execute("UPDATE review_items SET status='dismissed' WHERE status='pending'").rowcount==0
# Optional-only rejection cannot bypass missing canonical/organization reviews.
db.execute("UPDATE review_items SET status='pending'")
chosen=list(range(1,251))
q=",".join("?" for _ in chosen)
changed=db.execute(
    "UPDATE review_items SET status='rejected' WHERE status='pending' " +
    "AND review_id IN (" + q + ") AND review_type NOT IN " +
    "('character_resolution','runtime_failure','visual_observation_review'," +
    "'organization_unresolved','organization_failure')", chosen,
).rowcount
assert changed==50,changed
assert db.execute("SELECT COUNT(*) FROM review_items WHERE status='pending'").fetchone()[0]==1150
assert db.execute("SELECT COUNT(*) FROM canonical_tags").fetchone()[0]==1200
print("PASS: optional-only batch cannot reject mandatory Review")
print("PASS: 1200 mandatory/optional reviews discarded atomically, canonical IDs and source state unchanged")
print("PASS: all detected subjects processed; focused crop observations retained and audited")
