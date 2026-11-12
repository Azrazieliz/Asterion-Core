#!/usr/bin/env python3
"""SQLite regression for unique Review presentation and visible full restart."""
from pathlib import Path
import re
import sqlite3

root = Path(__file__).resolve().parents[1]
runtime = root / "android_app/app/src/main/java/com/ailm/android/runtime"
ui = root / "android_app/app/src/main/java/com/ailm/android/ui"
evidence = (runtime / "FusionImageWideTagEvidence.kt").read_text()
coordinator = (runtime / "AiWorkflowCoordinator.kt").read_text()
repository = (runtime / "LocalRepository.kt").read_text()
mapper = (runtime / "FusionVisualLabelMapper.kt").read_text()
viewmodel = (ui / "viewmodel/AppViewModel.kt").read_text()
screens = (ui / "screens/Screens.kt").read_text()

assert 'Regex("^[A-Z]{2}[0-9]{2,}(?:-[0-9]+)*$")' in evidence
assert 'taxonomy.allTags(refresh = true)' in mapper
assert 'val exactCanonical = Regex(' in coordinator and 'd{2,}(?:-' in coordinator
assert '.distinctBy { it.tagId.uppercase() }' in coordinator
assert 'restartFromBeginning: Boolean = false' in viewmodel
assert 'val resume = !restartFromBeginning && canResume' in viewmodel
assert 'restartFromBeginning = true' in screens
assert 'Text("Restart from beginning — all artwork")' in screens
assert 'confirmFullRestart' in screens and 'AlertDialog(' in screens
assert 'enabled = ready && !active && !paused' in screens
assert 'Regex("[A-Z]{2}[0-9]{2,}(?:-[0-9]+)*").matches(tagId)' in screens
assert 'COUNT(DISTINCT t.tag_id)' in repository and 'older.rowid < t.rowid' in repository

# Execute the exact production Review SQL on conflicting character/illustration
# observations. Display a canonical ID once without deleting its provenance.
section = repository.split("fun reviewQueue(", 1)[1].split("fun updateReview(", 1)[0]
match = re.search(r'val sql = """(.*?)""".trimIndent\(\)', section, re.S)
assert match, "Production Review query not found"
db = sqlite3.connect(":memory:")
db.executescript("""
CREATE TABLE images(image_id INTEGER PRIMARY KEY,filename TEXT,folder_uri TEXT);
CREATE TABLE review_items(review_id INTEGER PRIMARY KEY,image_uri TEXT,
 image_id INTEGER,status TEXT,review_type TEXT,reason TEXT,
 payload_json TEXT,correction_json TEXT,last_updated_ms INTEGER);
CREATE TABLE fusion_automation_state(image_id INTEGER PRIMARY KEY,state TEXT,
 pipeline_complete INTEGER,organization_complete INTEGER);
CREATE TABLE fusion_image_profiles(image_id INTEGER PRIMARY KEY,metadata_json TEXT);
CREATE TABLE fusion_tags(tag_id TEXT PRIMARY KEY,canonical_name TEXT);
CREATE TABLE fusion_image_canonical_tags(image_id INTEGER,tag_id TEXT,
 tag_name TEXT,tag_scope TEXT,confidence REAL,source TEXT,PRIMARY KEY(image_id,tag_id,tag_scope));
INSERT INTO images VALUES(1,'test.jpg','local');
INSERT INTO review_items VALUES(1,'image://1',1,'pending','test','example','{}','{}',10);
INSERT INTO fusion_tags VALUES('HC001','Black hair');
INSERT INTO fusion_tags VALUES('OF016-1-12','Original outfit');
INSERT INTO fusion_image_canonical_tags VALUES(1,'HC001','Black hair','illustration',0.81,'vision_classifier');
INSERT INTO fusion_image_canonical_tags VALUES(1,'HC001','Black hair','character_observed',0.85,'vision');
INSERT INTO fusion_image_canonical_tags VALUES(1,'OF016-1-12','Original outfit','illustration',0.92,'vision_classifier');
INSERT INTO fusion_image_canonical_tags VALUES(1,'OF016-1-12','Old label','character_observed',0.91,'vision');
INSERT INTO fusion_image_canonical_tags VALUES(1,'WP001','Reference only','character_reference',1,'manual');
""")
rows = db.execute(match.group(1), (5,)).fetchall()
assert len(rows) == 1, rows
assert rows[0][14] == 2, rows
labels = rows[0][15].split(" | ")
assert len(labels) == 2 and set(labels) == {"HC001: Black hair", "OF016-1-12: Original outfit"}, labels
assert db.execute("SELECT COUNT(*) FROM fusion_image_canonical_tags").fetchone()[0] == 5
print("PASS: unique IDs, hierarchical tags, source provenance retained, explicit restart")
