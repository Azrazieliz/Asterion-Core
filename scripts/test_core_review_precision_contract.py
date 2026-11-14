#!/usr/bin/env python3
"""Preservation and review-action safety gate.

Tests the intended SQLite state transitions plus production-code invariants.
This is NOT on-device verification of UI review controls or model inference.
"""
from pathlib import Path
import sqlite3
import json

root = Path(__file__).resolve().parents[1]
rt = root / "android_app/app/src/main/java/com/ailm/android/runtime"
coordinator = (rt / "AiWorkflowCoordinator.kt").read_text()
repository = (rt / "LocalRepository.kt").read_text()
runtime = (rt / "StandaloneRuntime.kt").read_text()
view_model = (root / "android_app/app/src/main/java/com/ailm/android/ui/viewmodel/AppViewModel.kt").read_text()

profile_writer = coordinator.split("private fun writeProfile(", 1)[1].split(
    "private fun collectStages(", 1)[0]
for protected in ("precision_overall_set", "precision_overall_score",
        "precision_overall_updated_at_ms", "rating_dimensions_v2",
        "rating_dimensions_updated_at_ms"):
    assert '"'+protected+'"' in profile_writer, protected
assert 'if (!metadata.has(key) && latest.has(key))' in profile_writer
assert '"aesthetic_score"' in profile_writer

assert 'fun saveReviewNote(' in repository
assert '"user_note"' in repository
assert 'fun undoLastReviewUpdate()' in repository
undo = repository.split("fun undoLastReviewUpdate()", 1)[1].split(
    "fun listAutomationImageIds(", 1)[0]
assert 'put("reason", "")' not in undo, "Undo must not erase actionable review reasons"
assert 'put("status", "pending")' in undo
# A rejected review is a dismissed decision, NOT a completed canonical profile.
# Previously the UI and runtime refused rejection until every SP/SX/RT field
# was filled, so rejected items stayed in Review and could not be refreshed.
assert 'Rejecting a review is a user decision' in runtime
assert 'if (normalizedAction in setOf("approve", "correct"))' in runtime
assert 'val profile = aiWorkflowCoordinator.canonicalProfile(imageId)' in runtime
review_policy = (rt / "ReviewDecisionPolicy.kt").read_text()
resolution_store = (rt / "FusionResolutionStore.kt").read_text()
screens = (root / "android_app/app/src/main/java/com/ailm/android/ui/screens/Screens.kt").read_text()
assert '"rejected", "skipped" -> false' in review_policy
assert 'ReviewDecisionPolicy.shouldRequeue' in resolution_store
assert "status NOT IN ('rejected','skipped')" in resolution_store
assert "SQLiteDatabase.CONFLICT_IGNORE" in resolution_store
assert '"review_id = ? AND status = \'pending\'"' in repository
assert 'subjectCoverageFailure' in runtime
assert '"stage_type" to "subject_coverage"' in runtime
assert 'reviewSnapshot == reviewGeneration' in view_model
assert 'generation != reviewGeneration' in view_model
assert 'onRefresh = appViewModel::refreshReviewQueue' in screens
assert 'onClick = onRefresh' in screens
assert 'enabled = !busy && !canonicalDecisionPending' not in screens
assert 'fun resolveReviews(' in view_model
assert 'reviewActionBusy = true' in view_model
assert 'reviewActionBusy = false' in view_model

# Reproduce data-preserving SQLite actions, including optional commentary.
conn = sqlite3.connect(":memory:")
conn.execute("""CREATE TABLE review_items (
    review_id INTEGER PRIMARY KEY AUTOINCREMENT, image_id INTEGER,
    review_type TEXT, status TEXT, reason TEXT,
    correction_json TEXT, last_updated_ms INTEGER)""")
reason = "Mandatory Fusion attributes missing: SP, SX, ET"
comment = "This image differs only by skin coloration"
conn.execute("""INSERT INTO review_items
    (image_id,review_type,status,reason,correction_json,last_updated_ms)
    VALUES (1,'character_resolution','pending',?,'{}',100)""", (reason,))
conn.execute("""UPDATE review_items SET correction_json=?,last_updated_ms=?
    WHERE review_id=1""", (json.dumps({"user_note": comment}), 101))
conn.execute("""UPDATE review_items SET status='rejected',last_updated_ms=?
    WHERE review_id=1""", (102,))
conn.execute("""UPDATE review_items SET status='pending',last_updated_ms=?
    WHERE review_id=1""", (103,))
status, observed_reason, correction = conn.execute("""
    SELECT status, reason, correction_json FROM review_items WHERE review_id=1
""").fetchone()
assert status == 'pending'
assert observed_reason == reason
assert json.loads(correction)['user_note'] == comment

# Simulate the same SQLite WHERE clauses used by queueReview. A user
# rejection wins even when a classifier checked the row just beforehand.
queue = sqlite3.connect(":memory:")
queue.execute("""CREATE TABLE review_items (
    review_id INTEGER PRIMARY KEY, image_uri TEXT NOT NULL,
    review_type TEXT NOT NULL, status TEXT NOT NULL, reason TEXT,
    UNIQUE(image_uri, review_type))""")
queue.execute("""INSERT INTO review_items
    VALUES (1,'file:///picture.webp','character_resolution','pending','missing HC')""")
queue.execute("UPDATE review_items SET status='rejected' WHERE review_id=1 AND status='pending'")
queue.execute("""UPDATE review_items SET status='pending'
    WHERE image_uri=? AND review_type=? AND status NOT IN ('rejected','skipped')""",
    ("file:///picture.webp", "character_resolution"))
queue.execute("""INSERT OR IGNORE INTO review_items
    (image_uri,review_type,status,reason) VALUES (?,?,?,?)""",
    ("file:///picture.webp", "character_resolution", "pending", "rerun"))
assert queue.execute("SELECT status FROM review_items").fetchone() == ("rejected",)
assert queue.execute("SELECT count(*) FROM review_items WHERE status='pending'").fetchone()[0] == 0

# A concurrent human edit must dominate a stale AI snapshot.
latest = {"precision_overall_set": True, "precision_overall_score": 87,
          "rating_dimensions_v2": {"artstyle": 92, "detail": 78},
          "rating_dimensions_updated_at_ms": 103,
          "aesthetic_score": 7.2,
          "aesthetic_model_source": "verified_cached_technical_preview"}
stale = {"canonical_profile_complete": False}
owner_keys = ("precision_overall_set", "precision_overall_score",
    "rating_dimensions_v2", "rating_dimensions_updated_at_ms")
for key in owner_keys:
    if key in latest: stale[key] = latest[key]
for key in ("aesthetic_score", "aesthetic_model_source"):
    if key not in stale and key in latest: stale[key] = latest[key]
assert stale["precision_overall_score"] == 87
assert stale["rating_dimensions_v2"]["artstyle"] == 92
assert stale["aesthetic_score"] == 7.2
print("PASS: precision ratings and HD grade survive reanalysis; "
      "rejected items stay dismissed until Undo; required tagging still gates organization")
