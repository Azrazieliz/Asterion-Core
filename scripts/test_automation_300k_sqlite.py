#!/usr/bin/env python3
"""300,000-row SQLite keyset-pagination and run-resumption regression."""
import sqlite3
import time

COUNT = 300_017
BATCH_SIZE = 64

db = sqlite3.connect(":memory:")
db.executescript("""
CREATE TABLE images (
    image_id INTEGER PRIMARY KEY,
    active INTEGER NOT NULL,
    imported_order INTEGER NOT NULL,
    modified_at_ms INTEGER NOT NULL,
    last_modified_ms INTEGER NOT NULL
);
CREATE INDEX idx_images_active_import_order ON images(active, imported_order);
CREATE TABLE fusion_automation_state (
    image_id INTEGER PRIMARY KEY,
    state TEXT NOT NULL,
    source_modified_at_ms INTEGER NOT NULL
);
CREATE TABLE fusion_image_fingerprints (image_id INTEGER PRIMARY KEY);
""")
with db:
    db.executemany("INSERT INTO images VALUES (?, 1, ?, 100, 100)",
                   ((i, i) for i in range(1, COUNT + 1)))
    db.executemany("INSERT INTO fusion_automation_state VALUES (?, 'complete', 100)",
                   ((i,) for i in range(3, COUNT + 1, 3)))
    db.executemany("INSERT INTO fusion_image_fingerprints VALUES (?)",
                   ((i,) for i in range(3, COUNT + 1, 3)))

PREDICATE = """
    i.active = 1 AND (
        fp.image_id IS NULL OR s.image_id IS NULL OR
        s.state IN ('pending', 'retry_required', 'failed') OR
        (s.state = 'complete' AND
         COALESCE(i.modified_at_ms, i.last_modified_ms, 0) >
         COALESCE(s.source_modified_at_ms, 0))
    )
"""
JOIN = """
    LEFT JOIN fusion_automation_state s ON s.image_id = i.image_id
    LEFT JOIN fusion_image_fingerprints fp ON fp.image_id = i.image_id
"""
def count_pending(force_all):
    predicate = "i.active = 1" if force_all else PREDICATE
    joins = "" if force_all else JOIN
    return db.execute(f"SELECT COUNT(*) FROM images i {joins} WHERE {predicate}").fetchone()[0]

def batch(cursor_order, cursor_id, force_all):
    predicate = "i.active = 1" if force_all else PREDICATE
    joins = "" if force_all else JOIN
    sql = f"""
      SELECT i.image_id, i.imported_order FROM images i {joins}
      WHERE ({predicate}) AND i.imported_order >= ? AND (
        i.imported_order > ? OR
        (i.imported_order = ? AND i.image_id > ?)
      )
      ORDER BY i.imported_order ASC, i.image_id ASC LIMIT ?
    """
    # Enforce a range on the (active, imported_order) index; without
    # imported_order >= ?, SQLite can rescan the first N rows per page.
    plan = db.execute("EXPLAIN QUERY PLAN " + sql, (cursor_order, cursor_order, cursor_order, cursor_id, BATCH_SIZE)).fetchall()
    assert any("imported_order>?" in str(step) for step in plan), plan
    return db.execute(sql, (cursor_order, cursor_order, cursor_order, cursor_id, BATCH_SIZE)).fetchall()

assert count_pending(True) == COUNT
pending = count_pending(False)
assert pending == COUNT - COUNT // 3, pending

start = time.monotonic()
cursor = (-1, 0)
seen = 0
paused = False
while True:
    rows = batch(*cursor, force_all=False)
    if not rows:
        break
    assert len(rows) <= BATCH_SIZE
    for image_id, imported_order in rows:
        assert image_id > cursor[1]
        assert image_id % 3 != 0
        cursor = (imported_order, image_id)
        seen += 1
    if not paused and seen > 300:
        saved = cursor  # Simulates a durable checkpoint between workers.
        rows_after_restart = batch(*saved, force_all=False)
        assert rows_after_restart[0][0] > saved[1]
        paused = True

assert seen == pending and paused
assert batch(COUNT, COUNT, True) == []
assert batch(-1, 0, True)[0][0] == 1
print(f"PASS: {COUNT:,} rows; {pending:,} pending, bounded batch={BATCH_SIZE}, "
      f"resumption monotonic, runtime={time.monotonic() - start:.2f}s")
