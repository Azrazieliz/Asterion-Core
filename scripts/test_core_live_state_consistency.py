#!/usr/bin/env python3
"""Source contract for real automation readiness, review counts and checkpoint safety."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
src = root / "android_app/app/src/main/java/com/ailm/android"
screens = (src / "ui/screens/Screens.kt").read_text()
vm = (src / "ui/viewmodel/AppViewModel.kt").read_text()
runtime = (src / "runtime/StandaloneRuntime.kt").read_text()
worker = (src / "workers/SetupWorkers.kt").read_text()
assert 'val pendingReviewCount = (state.aiOverview["automation_pending_review_count"] as? Number)?.toInt()' in screens
assert '?: state.reviewQueue.size' in screens
assert 'val pendingReviewCount = repository.countPendingReviews()' in runtime
assert 'Text("Review $pendingReviewCount items")' in screens
assert 'Text("Review ${review + failed} items")' not in screens
assert 'onRefreshQueue = appViewModel::refreshReviewQueue' in screens
assert 'LaunchedEffect(Unit) {' in screens
assert 'onRefreshQueue()' in screens and 'onFindLowRated(lowRatingThreshold.toInt(), true)' in screens
assert 'fun refreshReviewQueue()' in vm
assert 'reviewQueue = refreshed' in vm
assert '"state" to if (migrationProblem == "preparing") "initializing" else "blocked"' in runtime
assert '"state" to "ready"' in runtime
assert 'val preparing = state.aiOverview["automation_readiness_state"] == "initializing"' in screens
assert '"ready" to false,' in vm and '"state" to "unavailable"' in vm
assert 'failed = ((current["automation_failed"] as? Number)?.toInt() ?: 0) + 1' not in worker
assert 'status = if (runAttemptCount < MAX_AUTOMATION_RETRIES) "queued" else "failed"' in worker
assert 'Log.e("AsterionAutomation", "Worker execution failed; checkpoint retained.", error)' in worker
assert '"Automation stopped by user."' in vm and 'errorMessage = null,' in vm
assert 'val lastTaggingState = state.aiOverview["automation_last_tagging_state"]' in screens
assert 'CoroutineExceptionHandler { _, error ->' in runtime
assert 'Background task failed; gallery preserved' in runtime
assert 'Provider thumbnail lookup deferred; gallery continues' in screens
assert 'Thumbnail enqueue deferred; gallery continues' in screens
print("PASS: durable review count, initializing/blocked/ready state, no stale ready and per-image failure accounting")
