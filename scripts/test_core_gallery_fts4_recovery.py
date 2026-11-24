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
# The thumbnail is a bare decoded image, not a grey Material Card.
assert 'onDecodedAspectRatio = { ratio ->' in screen
assert 'modifier = tile.clickable(onClick = onOpen)' in screen
assert 'onDecodedAspectRatio?.invoke(' in screen
assert 'contentPadding = PaddingValues(bottom = 74.dp)' in screen
# The photo wall must start at the screen edge and have no black tile gutters.
assert screen.count('verticalItemSpacing = 0.dp') >= 3
assert screen.count('horizontalArrangement = Arrangement.spacedBy(0.dp)') >= 3
browser = screen.split('private fun LibraryBrowserScreen(', 1)[1].split('private fun LegacyLibraryBrowserScreen(', 1)[0]
assert 'var searchExpanded by rememberSaveable' in browser
assert 'if (searchExpanded)' in browser
assert 'if (showFilters)' in browser
assert 'modifier = Modifier.fillMaxSize()' in browser
assert 'modifier = Modifier.fillMaxSize()\\n            .padding(horizontal = 6.dp' not in browser
assert 'onBrowseCloudFiles()' in browser
assert 'text = { Text("Choose artwork source") }' in browser
assert 'onAdvancedSearch(request)' in browser
assert 'GalleryGlyph.Search' in browser and 'GalleryGlyph.Filter' in browser
navigation = (root / "android_app/app/src/main/java/com/ailm/android/ui/navigation/AppNavHost.kt").read_text()
assert "NavigationBar {" not in navigation
assert '.fillMaxWidth(0.70f)' in navigation
assert '.height(49.dp)' in navigation
assert '.background(Color(0xB127262C))' in navigation
assert 'primaryDestinations.forEachIndexed' in navigation
assert '.navigationBarsPadding()' not in screen
# Bounded collection paging now belongs to the ViewModel, not screen layout.
assert '"page_size" to 200' in vm
assert 'state.aiOverview["automation_readiness_ready"] == true' in screen
# Readiness permits queued preparation only for a safe transient Knowledge migration.
# Permanent prerequisites still disable Start and do not allocate model memory.
assert 'enabled = !active && (ready || preparing)' in screen
assert '!ready -> if (preparing) "Queue analysis" else "Analysis unavailable"' in screen
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

# Execute the production external-content triggers. A rename must remove the old posting.
import re
local_db = (root / "android_app/app/src/main/java/com/ailm/android/runtime/LocalDatabase.kt").read_text()
fts4 = local_db.split("private fun createFts4ImageTriggers", 1)[1].split("private fun isUnsupportedFts5", 1)[0]
for ddl in re.findall(r'"""(.*?)"""', fts4, re.S):
    db.execute(ddl.replace("${ifNotExistsSql}", ""))
def matches(term):
    return db.execute("SELECT docid FROM image_fts WHERE image_fts MATCH ?", (term,)).fetchall()
db.execute("INSERT INTO images VALUES(81,'oldneedle','doc','','')")
db.execute("UPDATE images SET filename='newneedle' WHERE image_id=81")
assert not matches('oldneedle') and matches('newneedle') == [(81,)]
db.execute("DELETE FROM images WHERE image_id=81")
assert not matches('newneedle')
db.execute("INSERT INTO image_fts(image_fts) VALUES('integrity-check')")

# Preview-cache upgrade must never overwrite the owner's 0..100 ratings.
# The older v16 migration silently set both stores to zero on upgrades.
migration = local_db.split("if (oldVersion < 16)", 1)[1].split("if (oldVersion < 17)", 1)[0]
assert "DELETE FROM thumbnail_cache" in migration
assert "UPDATE images SET rating" not in migration
assert "UPDATE fusion_image_profiles SET rating" not in migration
db = sqlite3.connect(":memory:")
db.executescript("""
CREATE TABLE images(image_id INTEGER PRIMARY KEY,rating INTEGER);
CREATE TABLE fusion_image_profiles(image_id INTEGER PRIMARY KEY,rating INTEGER);
CREATE TABLE thumbnail_cache(cache_path TEXT);
CREATE TABLE thumbnail_generation_state(image_uri TEXT);
INSERT INTO images VALUES(1,17),(2,93);
INSERT INTO fusion_image_profiles VALUES(1,17),(2,93);
INSERT INTO thumbnail_cache VALUES('stale-thumbnail');
INSERT INTO thumbnail_generation_state VALUES('stale-image');
""")
# Execute the actual cache-invalidation SQL used in the migration fixture.
for sql in re.findall(r'db\\.execSQL\\("(DELETE FROM [^"]+)"\\)', migration):
    db.execute(sql)
assert db.execute("SELECT rating FROM images ORDER BY image_id").fetchall() == [(17,), (93,)]
assert db.execute("SELECT rating FROM fusion_image_profiles ORDER BY image_id").fetchall() == [(17,), (93,)]
assert db.execute("SELECT count(*) FROM thumbnail_cache").fetchone()[0] == 0
assert db.execute("SELECT count(*) FROM thumbnail_generation_state").fetchone()[0] == 0
print("PASS: upgrade clears outdated preview cache without erasing owner ratings")
