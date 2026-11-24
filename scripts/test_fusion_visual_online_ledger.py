"""Executable SQLite/provenance regression for the private Fusion visual learner.

No user images, device, model download, or synthetic successes posing as art
accuracy. The JUnit FusionVisualOnlineHeadTest separately runs the real math.
"""
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
runtime = (root / "android_app/app/src/main/java/com/ailm/android/runtime/StandaloneRuntime.kt").read_text()
backend = (root / "android_app/app/src/main/java/com/ailm/android/runtime/ai/NativeRuntimeBackends.kt").read_text()
schema = (root / "android_app/app/src/main/java/com/ailm/android/runtime/FusionDatabaseSchema.kt").read_text()
store = (root / "android_app/app/src/main/java/com/ailm/android/runtime/FusionVisualOnlineHead.kt").read_text()
pack = (root / "scripts/prepare_bundled_wd_vit_v3.py").read_text()

assert 'private lateinit var fusionVisualHead: FusionVisualOnlineHead' in runtime
assert 'fusionVisualHead.correct(imageId, target.subjectIndex, canonicalTagId, include)' in runtime
assert 'fusionVisualHead.predict(' in runtime and 'FusionVisualLinearMath.ADMISSION' in runtime
assert 'fusionVisualHead.cache(' in runtime and 'subjectIndex = index' in runtime
assert 'result["fusion_feature_vector"]' in backend and 'outputs["fusion_wd_features"]' in backend
assert '"fusion_feature_output": "fusion_wd_features"' in pack
assert '"upstream_model_sha256": MODEL_SHA256' in pack
assert '"fusion_wd_features", TensorProto.FLOAT, [-1, 768]' in pack
assert 'createVisualLearningTables(db)' in schema
assert 'CREATE TABLE IF NOT EXISTS fusion_visual_online_heads' in schema

section = schema.split("private fun createVisualLearningTables", 1)[1].split(
    "private fun createTaxonomyRevision", 1)[0]
statements = re.findall(r'db\.execSQL\("""(.*?)"""\.trimIndent\(\)\)', section, re.S)
statements += re.findall(r'db\.execSQL\("(CREATE INDEX IF NOT EXISTS [^"]+)"\)', section)
assert len(statements) == 10, (len(statements), statements)

db = sqlite3.connect(":memory:")
db.execute("PRAGMA foreign_keys=ON")
db.executescript("""
CREATE TABLE images(image_id INTEGER PRIMARY KEY);
CREATE TABLE fusion_tags(tag_id TEXT PRIMARY KEY);
CREATE TABLE fusion_image_fingerprints(image_id INTEGER PRIMARY KEY, sha256 TEXT NOT NULL);
INSERT INTO images VALUES(1),(2);
INSERT INTO fusion_tags VALUES('HC001'),('HC002');
INSERT INTO fusion_image_fingerprints VALUES(1,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa');
INSERT INTO fusion_image_fingerprints VALUES(2,'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb');
""")
for statement in statements:
    db.execute(statement.strip())
# Same photo, two characters, genuinely distinct correction decisions.
blob = bytes(768 * 4)
hash_a = "a" * 64
model_a = "c" * 64
db.execute("""
INSERT INTO fusion_visual_feature_cache VALUES(?,?,?,?,?,?)
""", (1,0,hash_a,model_a,blob,1))
db.execute("""
INSERT INTO fusion_visual_feedback VALUES(?,?,?,?,?,?,?,?)
""", (1,0,"HC001",hash_a,model_a,1,blob,1))
db.execute("""
INSERT INTO fusion_visual_feedback VALUES(?,?,?,?,?,?,?,?)
""", (1,1,"HC001",hash_a,model_a,0,blob,1))
db.execute("""
INSERT OR REPLACE INTO fusion_visual_feedback VALUES(?,?,?,?,?,?,?,?)
""", (1,0,"HC001",hash_a,model_a,0,blob,2))
assert db.execute(
    "SELECT count(*) FROM fusion_visual_feedback WHERE image_id=1 AND canonical_id='HC001'"
).fetchone()[0] == 2
assert db.execute(
    "SELECT label FROM fusion_visual_feedback WHERE image_id=1 AND subject_index=0"
).fetchone()[0] == 0
assert db.execute(
    "SELECT subject_index,label FROM fusion_visual_feedback WHERE image_id=1 ORDER BY subject_index"
).fetchall() == [(0,0),(1,0)]
# Execute the production correction lookup with warm, evicted and stale pixels.
lookup = re.search(r'(SELECT encoder_hash,feature FROM \(.*?ORDER BY priority LIMIT 1)', store, re.S).group(1)
args = (1,0,hash_a,1,0,hash_a,"HC001")
new_encoder = "e" * 64
db.execute("UPDATE fusion_visual_feature_cache SET encoder_hash=?", (new_encoder,))
assert db.execute(lookup,args).fetchone() == (new_encoder,blob)
db.execute("DELETE FROM fusion_visual_feature_cache")
assert db.execute(lookup,args).fetchone() == (model_a,blob)
assert not db.execute(lookup,(1,0,"changed",1,0,"changed","HC001")).fetchall()
assert not db.execute(lookup,(1,0,hash_a,1,0,hash_a,"HC002")).fetchall()
# Source mutation invalidates old learned heads and evicts stale features.
db.execute("INSERT INTO fusion_visual_online_heads VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
           ("HC001",0,model_a,768,blob,0.,12,12,.95,.90,1,123))
assert db.execute("SELECT enabled FROM fusion_visual_online_heads").fetchone()[0] == 1
db.execute("UPDATE fusion_image_fingerprints SET sha256=? WHERE image_id=1", ("d" * 64,))
assert db.execute("SELECT enabled FROM fusion_visual_online_heads").fetchone()[0] == 0
assert db.execute("SELECT count(*) FROM fusion_visual_feature_cache").fetchone()[0] == 0
assert db.execute("""
SELECT count(*) FROM fusion_visual_feedback f
JOIN fusion_image_fingerprints fp ON f.image_id=fp.image_id AND f.source_hash=fp.sha256
WHERE f.encoder_hash=?
""", (model_a,)).fetchone()[0] == 0
# Reversal removes the old vote but preserves image and its other subject.
db.execute("DELETE FROM fusion_visual_feedback WHERE image_id=1 AND subject_index=0 AND canonical_id='HC001'")
assert db.execute("SELECT subject_index FROM fusion_visual_feedback").fetchall() == [(1,)]
assert db.execute("SELECT count(*) FROM images").fetchone()[0] == 2
# Unrecognized IDs cannot create phantom classes.
try:
    db.execute("INSERT INTO fusion_visual_online_heads VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
               ("XX999",0,model_a,768,blob,0.,12,12,.95,.90,1,123))
except sqlite3.IntegrityError:
    pass
else:
    raise AssertionError("Unknown Fusion IDs must violate the authoritative FK")
assert db.execute("PRAGMA foreign_key_check").fetchall() == []
print("PASS: private Fusion feature schema, per-subject correction dedupe, source invalidation, deletion retraction, ID authority")

# Exercise deferred corrections against exact production DDL and replay lookup.
import re, sqlite3
from pathlib import Path
runtime = Path(__file__).resolve().parents[1] / "android_app/app/src/main/java/com/ailm/android/runtime"
schema = (runtime/"FusionDatabaseSchema.kt").read_text()
learning = (runtime/"FusionVisualOnlineHead.kt").read_text()
database = sqlite3.connect(":memory:")
database.execute("CREATE TABLE images(image_id INTEGER PRIMARY KEY)")
database.execute(re.search(r'(CREATE TABLE IF NOT EXISTS fusion_visual_pending_labels .*?)"""',schema,re.S).group(1))
write = re.search(r'"(INSERT OR REPLACE INTO fusion_visual_pending_labels [^"]+)"',learning).group(1)
read = re.search(r'"(SELECT canonical_id,label FROM fusion_visual_pending_labels [^"]+)"',learning).group(1)
for row in [(5,1,'EC002','sourceA',1),(5,1,'EC002','sourceA',0),(5,2,'EC002','sourceA',1)]:
    database.execute(write,row)
assert database.execute(read,(5,1,'sourceA')).fetchall()==[('EC002',0)]
assert database.execute(read,(5,2,'sourceA')).fetchall()==[('EC002',1)]
assert database.execute(read,(5,1,'changed')).fetchall()==[]
assert database.execute(read,(5,-1,'sourceA')).fetchall()==[]

# Exercise the actual reference index on an existing DB, through updates and retraction.
schema_src = (runtime/"LocalDatabase.kt").read_text().split("private fun ensureVisualReferenceIndex",1)[1].split("private fun createArtworkThumbnailState",1)[0]
ref = sqlite3.connect(":memory:")
ref.execute("PRAGMA foreign_keys=ON")
ref.execute("CREATE TABLE fusion_character_visual_evidence(evidence_id INTEGER PRIMARY KEY, character_id TEXT, embedding_json TEXT, validated INTEGER)")
ref.executemany("INSERT INTO fusion_character_visual_evidence VALUES(?,?,?,?)",[(1,"outside80","[1]",1),(2,"ignored","[2]",0)])
for stmt in re.findall(r'execSQL\("([^"]+)"\)',schema_src):
    ref.execute(stmt)
assert ref.execute("SELECT * FROM fusion_visual_ref_pending").fetchall()==[(1,)]
ref.execute("INSERT INTO fusion_visual_ref_bands VALUES(23,1)")
ref.execute("DELETE FROM fusion_visual_ref_pending")
ref.execute("UPDATE fusion_character_visual_evidence SET embedding_json='[3]' WHERE evidence_id=1")
assert ref.execute("SELECT * FROM fusion_visual_ref_pending").fetchall()==[(1,)]
assert ref.execute("SELECT * FROM fusion_visual_ref_bands").fetchall()==[]
ref.executemany("INSERT INTO fusion_character_visual_evidence VALUES(?,?,?,1)", [(i,str(i),"[9]") for i in range(3,1003)])
ref.executemany("INSERT INTO fusion_visual_ref_bands VALUES(23,?)",[(i,) for i in range(3,1003)])
lookup = re.search(r'rawQuery\("""(SELECT e.character_id FROM fusion_visual_ref_bands .*?)"""', (runtime/"FusionResolutionStore.kt").read_text(),re.S).group(1)
assert len(ref.execute(lookup,(23,)).fetchall())==65
assert any("PRIMARY KEY" in row[3] for row in ref.execute("EXPLAIN QUERY PLAN "+lookup,(23,)))
ref.execute("DELETE FROM fusion_character_visual_evidence WHERE evidence_id=3")
assert not ref.execute("SELECT 1 FROM fusion_visual_ref_bands WHERE evidence_id=3").fetchall()
print("PASS: indexed reference retrieval, bounded collision bucket, correction invalidation and retraction")
