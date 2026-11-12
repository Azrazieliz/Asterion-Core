#!/usr/bin/env python3
"""Validate that Organized means durable Fusion organization, not a successful AI attempt."""
from pathlib import Path
import sqlite3

root = Path(__file__).resolve().parents[1]
src = root / "android_app/app/src/main/java/com/ailm/android"
query = (src / "runtime/LibraryQuery.kt").read_text()
repository = (src / "runtime/LocalRepository.kt").read_text()
runtime = (src / "runtime/StandaloneRuntime.kt").read_text()
screens = (src / "ui/screens/Screens.kt").read_text()

assert 'val organizationStatus: String = "all"' in query
predicate = """EXISTS (SELECT 1 FROM fusion_automation_state organized """
assert repository.count(predicate) == 2, "Both gallery rows and count must use indexed Fusion organization evidence"
assert repository.count("AND organized.state = 'complete' AND organized.organization_complete = 1)") == 4
assert 'organizationStatus = this["organization_status"]?.toString()' in runtime
assert 'it in setOf("organized", "not_organized")' in runtime
assert screens.count('"organization_status" to organizationStatus') == 2, "First page and pagination must retain the filter"
assert 'LaunchedEffect(query, tagsQuery, sort, organizationStatus)' in screens
assert 'query, tagsQuery, sort, organizationStatus,' in screens
assert 'organizationStatus = "organized"' in screens
assert '"No organized artwork found"' in screens
assert '"Organized means a completed, recorded Fusion file route' in screens
assert 'processed without error' not in screens
assert 'analyzed without failure' in screens

# Review approval must not silently promote an unchanged/unidentified file to
# 'complete' with organization_complete=1.
review_block = runtime.split('val organization = organizeAutonomousImage(imageId, workflow)', 1)[1].split('if (!organized)',1)[0]
assert 'organization["source_cleanup_pending"] != true' in review_block
assert '"organized", "already_organized", "routed_content", "already_routed"' in review_block
assert '"unchanged"' not in review_block

db = sqlite3.connect(":memory:")
db.executescript("""
CREATE TABLE images(image_id INTEGER PRIMARY KEY, active INTEGER NOT NULL);
CREATE TABLE fusion_automation_state(
    image_id INTEGER PRIMARY KEY, state TEXT, organization_complete INTEGER);
INSERT INTO images VALUES (1,1),(2,1),(3,1),(4,1),(5,0),(6,1);
INSERT INTO fusion_automation_state VALUES
    (1,'complete',1), (2,'complete',0), (3,'review_pending',1),
    (4,'waiting_for_knowledge',0), (5,'complete',1);
""")
where = """i.active = 1 AND EXISTS (
    SELECT 1 FROM fusion_automation_state organized
    WHERE organized.image_id = i.image_id
    AND organized.state = 'complete' AND organized.organization_complete = 1)"""
rows = db.execute(f"SELECT i.image_id FROM images i WHERE {where} ORDER BY i.image_id LIMIT 200 OFFSET 0").fetchall()
count = db.execute(f"SELECT COUNT(*) FROM images i WHERE {where}").fetchone()[0]
assert rows == [(1,)] and count == len(rows), (rows, count)
assert db.execute("SELECT COUNT(*) FROM images WHERE active=1").fetchone()[0] == 5
print("PASS: Organized-only searches and counts use actual completed Fusion routes; incomplete analyses never count as organized.")
