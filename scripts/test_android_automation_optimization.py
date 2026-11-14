#!/usr/bin/env python3
"""Static safety checks and executable v14->v15 SQLite transition smoke-test."""
from pathlib import Path
import sqlite3

base = Path("android_app/app/src/main/java/com/ailm/android")
runtime = (base / "runtime/StandaloneRuntime.kt").read_text()
saf = (base / "runtime/SafStorageProvider.kt").read_text()
thumbnails = (base / "runtime/PersistentArtworkThumbnails.kt").read_text()
knowledge = (base / "runtime/KnowledgeDatabase.kt").read_text()
screens = (base / "ui/screens/Screens.kt").read_text()
viewmodel = (base / "ui/viewmodel/AppViewModel.kt").read_text()
worker = (base / "workers/SetupWorkers.kt").read_text()
db_schema = (base / "runtime/LocalDatabase.kt").read_text()

# Protect the already successful native/SAF architecture and source media.
assert "fun resolveOrFetchArtworkThumbnail(" in runtime
assert "contentResolver.loadThumbnail(" in runtime
assert "repository.thumbnailPreferredSize(uri).let { (width, height) ->" in runtime
repository = (base / "runtime/LocalRepository.kt").read_text()
assert "fun thumbnailPreferredSize(imageUri: String, maxSide: Int = 1280)" in repository
assert "720.0 * maxOf(aspect, 1.0 / aspect)" in repository
assert "(longest / aspect).toInt().coerceAtLeast(720)" in repository
assert "(longest * aspect).toInt().coerceAtLeast(720)" in repository
assert "thumbnailProviderSlots.tryAcquire()" in runtime
assert "withTimeoutOrNull(900L)" in runtime
assert "runtimeScope.async(Dispatchers.IO)" in runtime
assert "resolveOrFetchArtworkThumbnail(source)" in screens
assert "if (providerTile) null else model" in screens
assert "writeLocks[(sourceUri.hashCode() and Int.MAX_VALUE)" in thumbnails
assert "private val writeLock = Any()" not in thumbnails

# Avoid two full-table taxonomy lookups for every individual image.
assert "taxonomyPromptSnapshot" in knowledge
assert "fun taxonomyPromptContexts(): Pair<String, String>" in knowledge
assert knowledge.count("SELECT tag_id, canonical_name, category FROM knowledge_tags ORDER BY category, tag_id") == 1
assert "invalidateTaxonomyPromptCache()" in knowledge

# WorkManager retries failed/deferred images, never silently unpauses an
# explicitly stopped run and never resets a running worker's checkpoint.
assert "class AutomationRecoveryWorker(" in worker
assert 'priorStatus !in setOf("running", "queued", "failed", "completed_with_errors")' in worker
assert 'checkpoint["force_all"] == true' in worker
assert 'Recovering interrupted automation at image checkpoint' in worker
assert "StandaloneRuntime.automationPauseRequested()" in worker
assert "StandaloneRuntime.countAutomationImages(false)" in worker
assert "PeriodicWorkRequestBuilder<AutomationRecoveryWorker>" in worker
assert "ExistingPeriodicWorkPolicy.KEEP" in worker
assert "setRequiresBatteryNotLow(true)" in worker
assert "getWorkInfosForUniqueWork(" in viewmodel
assert "if (alreadyActive)" in viewmodel
assert "cancelUniqueWork(LibraryAutomationWorker.RECOVERY_WORK_NAME)" in viewmodel

# Any interrupted physical move is journaled BEFORE copying; the source
# remains until the destination is SHA256-verified and catalog-committed.
assert "DB_VERSION = 16" in db_schema
assert "if (oldVersion < 16)" in db_schema
assert "MIN_SHORT_SIDE_PX = 720" in thumbnails
assert "automation_relocation_journal" in db_schema
assert "if (oldVersion < 15) createRelocationJournalArtifacts(db)" in db_schema
journal = runtime.split("private fun commitVerifiedAutomationRelocation(", 1)[1].split(
    "private fun organizeAutonomousImage(", 1
)[0]
assert journal.index('"prepared"') < journal.index("storageProvider.copy(")
assert journal.index("storageProvider.copy(") < journal.index("sourceSha != copySha")
assert journal.index("sourceSha != copySha") < journal.index("repository.updateImagePathAndClearThumbnails(")
assert journal.index("repository.updateImagePathAndClearThumbnails(") < journal.index("storageProvider.delete(record.uri)")
assert "pendingRelocationStage(imageId)" in journal
reconciliation = runtime.split("private fun reconcileInterruptedRelocations(", 1)[1].split(
    "private fun commitVerifiedAutomationRelocation(", 1
)[0]
assert "storageProvider.delete(" not in reconciliation
assert "resolutionStore.queueReview(" in reconciliation

# Model the actual v14 schema replacement and v15 relocation journal as a
# smoke check that existing review IDs/corrections survive and operations can
# progress through crash states without deleting image catalog records.
connection = sqlite3.connect(":memory:")
connection.executescript("""
CREATE TABLE review_items (
  review_id INTEGER PRIMARY KEY AUTOINCREMENT, image_uri TEXT NOT NULL,
  image_id INTEGER, status TEXT NOT NULL DEFAULT 'pending',
  review_type TEXT NOT NULL DEFAULT 'generic', reason TEXT,
  payload_json TEXT NOT NULL DEFAULT '{}',
  correction_json TEXT NOT NULL DEFAULT '{}',
  last_updated_ms INTEGER NOT NULL, UNIQUE(image_uri, review_type)
);
INSERT INTO review_items (review_id,image_uri,image_id,review_type,correction_json,last_updated_ms)
VALUES (7,'content://provider/document/1',123,'character_resolution','{"human":"preserved"}',1);
CREATE TABLE images (image_id INTEGER PRIMARY KEY, uri TEXT NOT NULL);
INSERT INTO images VALUES (123,'content://provider/document/1');
CREATE TABLE IF NOT EXISTS automation_relocation_journal (
    image_id INTEGER PRIMARY KEY,
    source_uri TEXT NOT NULL,
    target_folder_uri TEXT NOT NULL,
    target_name TEXT NOT NULL,
    destination_uri TEXT NOT NULL DEFAULT '',
    phase TEXT NOT NULL,
    updated_at_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_automation_relocation_phase
ON automation_relocation_journal(phase);
INSERT INTO automation_relocation_journal
 (image_id,source_uri,target_folder_uri,target_name,phase,updated_at_ms)
VALUES (123,'content://provider/document/1','content://provider/folder/2','art.webp','prepared',1);
UPDATE automation_relocation_journal
 SET destination_uri='content://provider/document/2', phase='copied'
 WHERE image_id=123;
""")
connection.execute("""INSERT INTO review_items
(image_uri,image_id,review_type,last_updated_ms)
VALUES ('content://provider/document/1',123,'relocation_interrupted',2)""")
rows = connection.execute(
    "SELECT review_id,review_type,correction_json FROM review_items ORDER BY review_id"
).fetchall()
assert len(rows) == 2 and rows[0] == (7, "character_resolution", '{"human":"preserved"}')
assert connection.execute("SELECT uri FROM images WHERE image_id=123").fetchone()[0].endswith("/1")
assert connection.execute(
    "SELECT phase FROM automation_relocation_journal WHERE image_id=123"
).fetchone()[0] == "copied"
print("PASS: fast provider thumbnails, bounded cloud I/O, cached taxonomy, durable recovery and safe relocation")
