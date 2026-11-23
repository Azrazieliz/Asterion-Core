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
assert 'fun undoLastReviewUpdate(' in repository
undo = repository.split("fun undoLastReviewUpdate(", 1)[1].split(
    "fun listAutomationImageIds(", 1)[0]
assert 'put("reason", "")' not in undo, "Undo must not erase actionable review reasons"
assert 'put("status", "pending")' in undo
assert 'normalizedAction == "reject"' in runtime
assert 'val attributes = aiWorkflowCoordinator.canonicalProfile(imageId)' in runtime
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
      "review rejection, Undo and notes preserve actionable state")

# Execute the production profile update trigger against both older and missing profiles.
def exercise_profile_trigger(trigger):
    store = sqlite3.connect(":memory:")
    store.executescript("""
        CREATE TABLE images(image_id INTEGER PRIMARY KEY,uri TEXT, favorite INTEGER,rating INTEGER,tags_text TEXT,scanned_at_ms INTEGER);
        CREATE TABLE fusion_image_profiles(image_id INTEGER PRIMARY KEY,source_uri TEXT,thumbnail_uri TEXT,
            favorite INTEGER,rating INTEGER,tags_text TEXT,metadata_json TEXT,updated_at_ms INTEGER);
        INSERT INTO images VALUES(1,'source',0,24,'manual',50),(2,'other',0,0,'',10);
        INSERT INTO fusion_image_profiles VALUES(1,'source','preview',0,24,'manual','{"owner":"edit"}',90);
    """)
    store.execute(trigger)
    store.execute("UPDATE images SET favorite=1 WHERE image_id=1")
    assert store.execute("SELECT thumbnail_uri,favorite,rating,tags_text,metadata_json,updated_at_ms FROM fusion_image_profiles WHERE image_id=1").fetchone() == (
        'preview',1,24,'manual','{"owner":"edit"}',90)
    store.execute("UPDATE images SET rating=0 WHERE image_id=2")
    assert store.execute("SELECT source_uri,thumbnail_uri,rating FROM fusion_image_profiles WHERE image_id=2").fetchone() == ('other','other',0)
    store.execute("UPDATE images SET uri='renamed' WHERE image_id=2")
    assert store.execute("SELECT source_uri,thumbnail_uri FROM fusion_image_profiles WHERE image_id=2").fetchone() == ('renamed','renamed')
    store.close()

schema = (rt / "FusionDatabaseSchema.kt").read_text()
start = schema.index("CREATE TRIGGER ${ifNotExistsSql}trg_images_au_fusion_profile")
trigger = schema[start:schema.index('"""', start)].replace('${ifNotExistsSql}', '').replace('$TABLE_IMAGE_PROFILES', 'fusion_image_profiles')
exercise_profile_trigger(trigger)
print("PASS: favorite edits, rating edits and legacy profile synchronization preserve verified preview paths")

# Model scores survive a restart only for the exact image, model/catalog revision and stage.
checkpoint_start = schema.index("CREATE TABLE ${ifNotExistsSql}fusion_visual_checkpoints")
checkpoint_ddl = schema[checkpoint_start:schema.index('"""', checkpoint_start)].replace('${ifNotExistsSql}', '')
cache_source = (rt / "VisualStageCheckpoint.kt").read_text()
lookup = next(line.strip().strip('",') for line in cache_source.splitlines()
              if "SELECT scores_json FROM fusion_visual_checkpoints" in line)
with sqlite3.connect(":memory:") as cache:
    cache.execute("CREATE TABLE images(image_id INTEGER PRIMARY KEY)")
    cache.execute(checkpoint_ddl)
    cache.executemany("INSERT INTO images VALUES(?)", [(1,), (2,)])
    cache.executemany("INSERT INTO fusion_visual_checkpoints VALUES(?,?,?,?)",
                      [(1,'image','v1','global'),(1,'subject/0','v1','first'),(1,'subject/1','v1','second')])
    cache.commit()
    reopened = sqlite3.connect(":memory:")
    cache.backup(reopened)
    assert reopened.execute(lookup,(1,'subject/1','v1')).fetchone()==('second',)
    for args in [(2,'image','v1'),(1,'subject/0','v2'),(1,'subject/9','v1')]:
        assert reopened.execute(lookup,args).fetchone() is None
    reopened.close()
print("PASS: scoped stage checkpoints survive reopening and reject stale revisions")
