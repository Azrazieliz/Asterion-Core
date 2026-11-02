#!/usr/bin/env python3
"""Prove preview generation can page over a 300K library without OFFSET/reopens."""
import sqlite3
import time
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
runtime = (repo / "android_app/app/src/main/java/com/ailm/android/runtime/StandaloneRuntime.kt").read_text()
worker = (repo / "android_app/app/src/main/java/com/ailm/android/workers/ArtworkThumbnailWarmupWorker.kt").read_text()
images = (repo / "android_app/app/src/main/java/com/ailm/android/runtime/LocalRepository.kt").read_text()
ui = (repo / "android_app/app/src/main/java/com/ailm/android/ui/screens/Screens.kt").read_text()
assert "pendingArtworkPreviewBatch(" in images
assert "ORDER BY i.imported_order DESC, i.image_id DESC" in images
assert "LIMIT ?" in images and "OFFSET" not in images.split("fun pendingArtworkPreviewBatch(", 1)[1].split("fun markArtworkPreviewFailure(", 1)[0]
assert "UNIQUE_WORK_NAME" in worker and "APPEND_OR_REPLACE" in worker
assert "setRequiresBatteryNotLow(true)" in worker
assert "generateIndexedArtworkPreview(" in runtime
assert "remote_provider_thumbnail_unavailable" in runtime
assert "contentResolver.loadThumbnail(" in runtime
assert "scheduleArtworkPreviewWarmup()" in runtime
assert "thumbnail_committed" in ui and "preferPreview = true" in ui

db = sqlite3.connect(":memory:")
db.executescript("""
CREATE TABLE images(image_id INTEGER PRIMARY KEY, uri TEXT NOT NULL UNIQUE,
  active INT NOT NULL, imported_order INTEGER NOT NULL);
CREATE TABLE thumbnail_cache(image_uri TEXT NOT NULL PRIMARY KEY);
CREATE TABLE thumbnail_generation_state(
  image_uri TEXT NOT NULL PRIMARY KEY,state TEXT NOT NULL,updated_at_ms INT NOT NULL);
CREATE INDEX idx_images_active_import_order ON images(active, imported_order);
""")
count = 300_017
db.executemany("INSERT INTO images VALUES(?,?,?,?)", (
    (i, "content://docs/img/%s" % i, 1, i // 2) for i in range(1, count + 1)
))
db.executemany("INSERT INTO thumbnail_cache VALUES(?)", (
    ("content://docs/img/%s" % i,) for i in range(1, count + 1, 4)
))
now = int(time.time() * 1000)
# Failed previews must be cooled down, but re-eligible after six hours.
db.execute("INSERT INTO thumbnail_generation_state VALUES(?,?,?)",
           ("content://docs/img/2", "failed", now))
db.execute("INSERT INTO thumbnail_generation_state VALUES(?,?,?)",
           ("content://docs/img/4", "failed", now - 7 * 60 * 60 * 1000))
sql = """
SELECT i.image_id, i.uri, i.imported_order FROM images i
LEFT JOIN thumbnail_cache cache ON cache.image_uri = i.uri
LEFT JOIN thumbnail_generation_state generation ON generation.image_uri = i.uri
WHERE i.active = 1
  AND (i.imported_order < ? OR (i.imported_order = ? AND i.image_id < ?))
  AND cache.image_uri IS NULL
  AND (generation.state IS NULL OR generation.state != 'failed'
       OR generation.updated_at_ms < ?)
ORDER BY i.imported_order DESC, i.image_id DESC
LIMIT ?
"""
def batch(order, image_id, limit=48):
    return db.execute(sql, (order, order, image_id, now - 6 * 60 * 60 * 1000, limit)).fetchall()

assert [x[0] for x in batch(2**63 - 1, 2**31 - 1, 5)] == [300016, 300015, 300014, 300012, 300011]
cursor = (2**63 - 1, 2**31 - 1)
visited = set()
begin = time.monotonic()
while rows := batch(*cursor, limit=64):
    for row in rows:
        assert row[0] not in visited, "Preview page repeated an image"
        visited.add(row[0])
    cursor = (rows[-1][2], rows[-1][0])
assert 2 not in visited and 4 in visited
expected = count - ((count + 3) // 4) - 1
assert len(visited) == expected, (len(visited), expected)
# Changed files become eligible when their previous saved preview is invalidated.
db.execute("DELETE FROM thumbnail_cache WHERE image_uri = ?", ("content://docs/img/1",))
assert batch(1, 2, 5)[0][0] == 1
plan = db.execute("EXPLAIN QUERY PLAN " + sql, (-1, -1, 0, now, 16)).fetchall()
assert any("idx_images_active_import_order" in str(row) for row in plan), plan
print(f"PASS: indexed preview keyset {count:,} rows, {len(visited):,} pending, "
      f"bounded 64-row batches, stable order, cooldown and invalidation, "
      f"runtime={time.monotonic()-begin:.2f}s")
