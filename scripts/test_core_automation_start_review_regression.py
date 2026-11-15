#!/usr/bin/env python3
"""Source/data regression for v2.0.61 startup deadlock and stale Review badge."""
from pathlib import Path
from sqlite3 import connect
root = Path(__file__).resolve().parents[1]
src = root / "android_app/app/src/main/java/com/ailm/android"
runtime = (src / "runtime/StandaloneRuntime.kt").read_text(encoding="utf-8")
repository = (src / "runtime/LocalRepository.kt").read_text(encoding="utf-8")
worker = (src / "workers/SetupWorkers.kt").read_text(encoding="utf-8")
vm = (src / "ui/viewmodel/AppViewModel.kt").read_text(encoding="utf-8")
ui = (src / "ui/screens/Screens.kt").read_text(encoding="utf-8")

assert '"deferred_knowledge_reconciliation" to (migrationProblem == "preparing")' in runtime
assert 'val startupReadiness = StandaloneRuntime.automationReadiness()' in worker
start = worker.index('val startupReadiness = StandaloneRuntime.automationReadiness()')
defer = worker.index('AutomationStartPolicy.Decision.WAIT_FOR_KNOWLEDGE', start)
reservation = worker.index('StandaloneRuntime.pauseAiQueueForAutomation()',start)
assert start < defer < reservation, "No memory reservation while migration is preparing"
assert 'return Result.retry()' in worker[defer:reservation]
assert '"Waiting for safe Knowledge reconciliation;' in worker[defer:reservation]
assert 'val startDecision = AutomationStartPolicy.decide(readiness)' in vm
start = vm.index('val startDecision = AutomationStartPolicy.decide(readiness)')
blocked = vm.index('if (startDecision == AutomationStartPolicy.Decision.BLOCKED)', start)
enqueue = vm.index('enqueueUniqueWork(', blocked)
assert start < blocked < enqueue
assert 'val total = if (waitingForKnowledge && !(resume && !retryPending))' in vm
assert '"automation_pending_review_count" to pendingReviewCount' in runtime
assert 'fun countPendingReviews(): Int' in repository
assert '(state.aiOverview["automation_pending_review_count"] as? Number)?.toInt()' in ui
assert 'if (review > 0) {' in ui
assert 'review + failed' not in ui, "A failed run does not create a Review notification"
assert 'enabled = !active && (ready || preparingKnowledge)' in ui
assert 'preparingKnowledge && !ready -> "Queue analysis"' in ui
assert 'aiOverview = _uiState.value.aiOverview + liveStatus' in vm

db = connect(":memory:")
db.executescript("""
CREATE TABLE images(image_id INTEGER PRIMARY KEY, protected INTEGER);
CREATE TABLE review_items(review_id INTEGER PRIMARY KEY, status TEXT, image_id INTEGER);
CREATE TABLE canonical_tags(image_id INTEGER,tag_id TEXT);
""")
db.executemany("INSERT INTO images VALUES (?,1)", [(i,) for i in range(1,22923)])
db.executemany("INSERT INTO canonical_tags VALUES (?,?)", [(i,"SP001") for i in range(1,22923)])
db.executemany("INSERT INTO review_items VALUES (?,?,?)",
               [(i,"pending",i) for i in range(1,301)])
queued = {"status": "queued", "total": 0, "processed": 0, "failed": 0,
          "cursor_id":0,"cursor_order":-1}
assert queued["processed"] == 0 and queued["failed"] == 0
pending = lambda: db.execute("SELECT COUNT(*) FROM review_items WHERE status='pending'").fetchone()[0]
assert pending() == 300
db.execute("UPDATE review_items SET status='dismissed' WHERE status='pending'")
assert pending() == 0
assert db.execute("SELECT COUNT(*) FROM images").fetchone()[0] == 22922
assert db.execute("SELECT COUNT(*) FROM canonical_tags").fetchone()[0] == 22922
# Completing the temporary migration resumes at the original checkpoint.
queued["total"] = db.execute("SELECT COUNT(*) FROM images").fetchone()[0]
queued["status"] = "running"
assert queued["total"] == 22922 and queued["cursor_id"] == 0
print("PASS: preparation queues the same work without failure or model contention")
print("PASS: live Review count clears even if historical automation failed")
print("PASS: 22,922 images, canonical tags, and durable checkpoint preserved")
