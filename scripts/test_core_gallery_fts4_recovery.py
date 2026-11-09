#!/usr/bin/env python3
"""Regression gate for non-destructive FTS4 recovery and compact gallery."""
from pathlib import Path
import sqlite3

root = Path(__file__).resolve().parents[1]
src = (root / "android_app/app/src/main/java/com/ailm/android/runtime/FusionMigrationBackup.kt").read_text()
screen = (root / "android_app/app/src/main/java/com/ailm/android/ui/screens/Screens.kt").read_text()
vm = (root / "android_app/app/src/main/java/com/ailm/android/ui/viewmodel/AppViewModel.kt").read_text()
assert 'VACUUM INTO ?' in src
assert 'derivedFtsCorrupt = failures.isNotEmpty() && failures.all' in src
assert 'malformed inverted index for FTS4 table main.image_fts' in src
assert 'if (!checkpointCommitted) backup.delete()' in src
assert 'db.execSQL("INSERT INTO image_fts(image_fts) VALUES (\'rebuild\')")' in src
assert 'require(before == after)' in src
assert 'val invalidForeignKeys' in src
assert 'LazyVerticalStaggeredGrid(' in screen
assert 'contentScale = ContentScale.Fit' in screen
assert 'modifier = displayShape' in screen
assert '.navigationBarsPadding()' not in screen
# Bounded collection paging now belongs to the ViewModel, not screen layout.
assert '"page_size" to 200' in vm
assert 'state.aiOverview["automation_readiness_ready"] == true' in screen
assert 'enabled = !active && ready' in screen
assert 'onExportFusion = { fusionExportLauncher.launch("Asterion-Fusion.sqlite") }' in screen
assert 'StandaloneRuntime.automationReadiness()' in vm

# The indexed text is DERIVED and can be rebuilt without changing source rows.
db = sqlite3.connect(":memory:")
db.execute("CREATE TABLE images(image_id INTEGER PRIMARY KEY,filename TEXT,uri TEXT,tags_text TEXT,metadata_text TEXT)")
db.execute("CREATE VIRTUAL TABLE image_fts USING fts4(filename,uri,tags_text,metadata_text,content='images')")
db.executemany("INSERT INTO images VALUES(?,?,?,?,?)", [
    (i, f"dragon artwork {i}", f"art://{i}", "tag", "") for i in range(1, 81)
])
db.execute("INSERT INTO image_fts(image_fts) VALUES ('rebuild')")
db.commit()
db.execute("DELETE FROM image_fts WHERE docid=12")
db.commit()
assert db.execute("SELECT count(*) FROM image_fts WHERE image_fts MATCH 'dragon'").fetchone()[0] == 79
before = db.execute("SELECT count(*) FROM images").fetchone()[0]
db.execute("INSERT INTO image_fts(image_fts) VALUES ('rebuild')")
db.commit()
assert db.execute("SELECT count(*) FROM images").fetchone()[0] == before
assert db.execute("SELECT count(*) FROM image_fts WHERE image_fts MATCH 'dragon'").fetchone()[0] == 80
assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
print("PASS: FTS4 can be rebuilt with source artwork unchanged; compact gallery and safety gates present")
