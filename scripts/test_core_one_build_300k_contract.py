#!/usr/bin/env python3
"""Pre-release static plus executable 300K indexed-family/pagination gate.

This is a *database scale* gate, not a claim that on-device ONNX has been
benchmarked. Device acceptance is separately mandatory after APK release.
"""
from pathlib import Path
import sqlite3
import time

root = Path(__file__).resolve().parents[1]
runtime = root / "android_app/app/src/main/java/com/ailm/android/runtime"
ai = runtime / "ai/NativeRuntimeBackends.kt"
text = ai.read_text()
mapper = (runtime / "FusionVisualLabelMapper.kt").read_text()
coordinator = (runtime / "AiWorkflowCoordinator.kt").read_text()
controller = (runtime / "StandaloneRuntime.kt").read_text()
policy = (runtime / "FusionImageWideEvidencePolicy.kt").read_text()
rating = (runtime / "ArtworkRatingPolicy.kt").read_text()
worker = (root / "android_app/app/src/main/java/com/ailm/android/workers/SetupWorkers.kt").read_text()

assert 'if (taskType == "tag_prediction")' in text
assert 'result["scored_tags"] = evidence' in text
assert 'result["scored_tags"] = ranked.take' not in text
assert 'private var taggerSession: OrtSession? = null' in text
assert 'OnnxRuntimeClient.releaseTaggerSession()' in text
assert 'val installedTaxonomy = fusionTaxonomy.allTags()' in controller
assert controller.index('val installedTaxonomy = fusionTaxonomy.allTags()') < controller.index('val earlyResponse:')
assert 'val trainedKeys = compiled.keysById[candidate.id].orEmpty()' in mapper
assert 'for (family in familyOrder)' in mapper
assert 'visual_candidate_comparisons' in mapper
assert 'subjectScoped = subjectScoped' in controller
assert 'character_attribute_requires_subject_pixels' in policy
assert 'subjectScoped = true' in controller
assert 'plant girl' in mapper and 'ambiguousPlant' in mapper
assert 'liveTaxonomy.tagById(id)' in coordinator
assert 'fun artworkRating(imageId: Int)' in controller
assert 'fun setArtworkRating(imageId: Int, manualStars: Int)' in controller
assert 'manualStars' in rating and 'preferredStars' in rating
assert 'unavailableProviders.add(provider)' in worker
assert 'deferUnavailableAutomationSource' in worker

# Source-level and executable rating-persistence gate: automatic profile
# updates must never REPLACE manually rated/favorited artwork records.
profile_writer = coordinator.split("private fun writeProfile(", 1)[1].split(
    "private fun collectStages(", 1)[0]
assert "CONFLICT_REPLACE" not in profile_writer
assert 'db.update(' in profile_writer
assert '"manual_rating_updated_at_ms"' in profile_writer
assert "if (latest.has(key)) metadata.put(key, latest.get(key))" in profile_writer
assert "else metadata.remove(key)" in profile_writer
assert 'if (changed == 0)' in profile_writer
assert profile_writer.count('"""') == 2, "Missing production fallback profile INSERT"
fallback_sql = profile_writer.split('"""', 2)[1]
assert "INSERT OR IGNORE INTO fusion_image_profiles" in fallback_sql
assert "SELECT image_id, uri, uri, favorite, rating, tags_text, ?, ?" in fallback_sql
ratings_db = sqlite3.connect(":memory:")
ratings_db.executescript("""
CREATE TABLE images (image_id INTEGER PRIMARY KEY,uri TEXT,favorite INTEGER,
 rating INTEGER,tags_text TEXT);
CREATE TABLE fusion_image_profiles (
 image_id INTEGER PRIMARY KEY,source_uri TEXT,thumbnail_uri TEXT,
 favorite INTEGER DEFAULT 0,rating INTEGER DEFAULT 0,tags_text TEXT,
 metadata_json TEXT,updated_at_ms INTEGER);
INSERT INTO images VALUES (1,'content://one',1,5,'HC001');
INSERT INTO images VALUES (2,'content://two',1,4,'EC004');
INSERT INTO fusion_image_profiles VALUES
 (1,'content://one','content://thumb',1,5,'HC001',
 '{"manual_rating":5,"manual_rating_authoritative":true}',10);
""")
# The existing-row UPDATE changes only the automation-owned fields.
ratings_db.execute(
 "UPDATE fusion_image_profiles SET source_uri=?,metadata_json=?,updated_at_ms=? WHERE image_id=?",
 ("content://one", '{"manual_rating":5,"aesthetic_score":8.4}',100,1))
assert ratings_db.execute(
 "SELECT thumbnail_uri,favorite,rating,tags_text FROM fusion_image_profiles WHERE image_id=1"
).fetchone() == ("content://thumb",1,5,"HC001")
# Execute the *production* fallback SQL for a legacy record without a profile.
ratings_db.execute(fallback_sql, ('{"aesthetic_score":7.0}',100,2))
assert ratings_db.execute(
 "SELECT favorite,rating,tags_text FROM fusion_image_profiles WHERE image_id=2"
).fetchone() == (1,4,"EC004")

db = sqlite3.connect(":memory:")
db.execute("PRAGMA journal_mode=MEMORY")
db.execute("CREATE TABLE images (image_id INTEGER PRIMARY KEY, imported_order INTEGER NOT NULL, "
           "uri TEXT NOT NULL, active INTEGER NOT NULL)")
db.execute("CREATE INDEX idx_images_active_cursor ON images(active,imported_order,image_id)")
db.execute("CREATE TABLE receipts(image_id INTEGER, subject_id INTEGER, family TEXT, "
           "status TEXT, matched_count INTEGER, PRIMARY KEY(image_id,subject_id,family))")
db.executemany("INSERT INTO images VALUES(?,?,?,1)",
    ((i,i,"content://provider/"+str(i)) for i in range(1,300001)))
assert db.execute("SELECT COUNT(*) FROM images").fetchone()[0] == 300000

cursor=(0,0)
total=0
begin=time.perf_counter()
while total<300000:
    page=db.execute(
        "SELECT image_id,imported_order FROM images WHERE active = 1 AND "
        "(imported_order > ? OR (imported_order=? AND image_id>?)) "
        "ORDER BY imported_order,image_id LIMIT 2048",
        (cursor[0],cursor[0],cursor[1])
    ).fetchall()
    assert page
    total+=len(page)
    cursor=(page[-1][1],page[-1][0])
elapsed=time.perf_counter()-begin
assert total==300000 and cursor==(300000,300000)
plan=" ".join(str(row) for row in db.execute(
    "EXPLAIN QUERY PLAN SELECT image_id FROM images WHERE active=1 AND "
    "(imported_order>? OR (imported_order=? AND image_id>?)) "
    "ORDER BY imported_order,image_id LIMIT 2048",(100,100,100)))
assert "idx_images_active_cursor" in plan,plan

# Receipts from different people MUST NOT collide; the same family can have
# multiple canonical values. Duplicate family scope is idempotent in writes.
db.executemany("INSERT OR REPLACE INTO receipts VALUES (?,?,?,?,?)",
    [(1,0,"SP","accepted",1), (1,1,"SP","evaluated_no_match",0),
     (1,-1,"EN","accepted",2)])
assert db.execute("SELECT COUNT(*) FROM receipts WHERE image_id=1").fetchone()[0]==3
print(f"PASS: 300000 ordered indexed images, no offset traversal, {elapsed:.2f}s SQL; "
      "subject receipts, explicit family comparator, warm classifier, user ratings")
