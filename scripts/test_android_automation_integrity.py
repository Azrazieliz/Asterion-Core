#!/usr/bin/env python3
"""Fail CI if unattended Android automation regains destructive or false-success paths."""
from pathlib import Path

root = Path("android_app/app/src/main/java/com/ailm/android")
runtime = (root / "runtime/StandaloneRuntime.kt").read_text()
worker = (root / "workers/SetupWorkers.kt").read_text()
viewmodel = (root / "ui/viewmodel/AppViewModel.kt").read_text()
policy = (root / "runtime/ContentTriagePolicy.kt").read_text()
backends = (root / "runtime/ai/NativeRuntimeBackends.kt").read_text()
native = Path("android_app/app/src/main/cpp/qwen_runtime_jni.cpp").read_text()
saf = (root / "runtime/SafStorageProvider.kt").read_text()

preflight = runtime.split("private fun automationImagePreflight(", 1)[1].split(
    "private fun resolvedQwenSemanticModel(", 1)[0]
assert '"delete_images"' not in preflight, "Automation must not delete original artwork"
assert '"duplicate_review_pending"' in preflight
assert '"corrupt_review_pending"' in preflight
assert "repository.markImageInactive(imageId)" not in preflight
assert 'state = "retry_required"' in preflight

flow = runtime.split("fun runAutonomousImageWorkflow(", 1)[1].split(
    "private fun automationImagePreflight(", 1
)[0]
assert 'earlyResponse["ok"] != true' in flow
assert "incompleteEarlyStages.isNotEmpty()" in flow
assert 'state = "retry_required"' in flow
assert '"deferred_incomplete_analysis"' in flow
assert flow.index('needsReview -> "review_pending"') < flow.index('terminalContentRoute -> "complete"')

assert "commitVerifiedAutomationRelocation" in runtime
relocation = runtime.split("private fun commitVerifiedAutomationRelocation(", 1)[1].split(
    "private fun organizeAutonomousImage(", 1
)[0]
for item in ["storageProvider.copy(", "ImageFingerprinting.contentSha256(",
             "repository.updateImagePathAndClearThumbnails(", "storageProvider.delete(record.uri)"]:
    assert item in relocation, item
assert relocation.index("storageProvider.copy(") < relocation.index("repository.updateImagePathAndClearThumbnails(")
assert relocation.index("repository.updateImagePathAndClearThumbnails(") < relocation.index("storageProvider.delete(record.uri)")
assert '"source_cleanup_pending"' in relocation
assert "cleanupPreparedRemoteImage(stagedPayload)" in runtime
assert "ImageFingerprinting.inspectLocal(stagedRemote" in runtime
assert "partial destination may remain" in saf

assert '"completed_with_errors"' in worker
assert "val retryPending =" in viewmodel and "resetAutomationCheckpoint(effectiveForceAll)" in viewmodel
assert "if (retryPending) effectiveForceAll = false" in viewmodel
# Confirmed paid-preview advertisements may use reversible Trash/Promotion routing.
# An unverified promotion still requires manual review; the other Trash
# categories must always remain review-gated.
assert "verifiedPromotion = unequivocalPaidPreviewOcr(observation.ocrText)" in policy
promotion = policy.split("route = ROUTE_PROMOTION_TRASH,", 1)[1].split("reason =", 1)[0]
assert "requiresReview = !verifiedPromotion" in promotion
for name in ("ROUTE_JUNK_TRASH", "ROUTE_NO_CHARACTER_TRASH"):
    chunk = policy.split(f"route = {name},", 1)[1].split("reason =", 1)[0]
    assert "requiresReview = true" in chunk, name
assert "required nonnegative routing." in backends
assert "requested_generation >= n_context_tokens - n_prompt_tokens" in native
# Regressions observed on Android 17 with the real 13,908-image library.
# ForegroundServiceStartNotAllowedException must never abort the run.
assert "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return" not in worker.split("private suspend fun safeForeground(processed: Int", 1)[1]
assert worker.count("safeForeground(") >= 6
assert "withContext(Dispatchers.IO) { StandaloneRuntime.probeAutomationExecution(" in worker
assert "StandaloneRuntime.runAutonomousImageWorkflow(mapOf(" in worker
manager = (root / "runtime/ai/LocalAiManager.kt").read_text()
await_method = manager.split("private fun awaitTaskTerminalState(", 1)[1].split(
    "private fun extractSemanticMatches(", 1
)[0]
assert "latest.status !in TERMINAL_TASK_STATUSES" in await_method
assert "executionScheduler.cancelTask(taskId)" in await_method
assert "return null" in await_method
assert "executionScheduler.resume()" in await_method
# The actual OCR/Qwen stage must execute, not remain stranded as pending.
inline = manager.split("private fun executePipelineStages(", 1)[1].split(
    "private fun resolvePipelineStages(", 1
)[0]
assert 'payload["direct_automation_dispatch"] == true' in inline
assert "executionQueue.enqueueTask(" in inline
assert "runBlocking { taskDispatcher.dispatch(task) }" in inline
assert inline.index("runBlocking { taskDispatcher.dispatch(task) }") < inline.index("awaitTaskTerminalState(task.taskId")
assert runtime.count('"direct_automation_dispatch" to true') == 2
assert "resolveTagByFamily" in runtime
assert 'val recoveredTags = emptyList<String>()' in runtime
assert 'Full Qwen inference in the preflight' in runtime
# AsterionCore v2.0.16: Android device found memory-admission failure
# (OCR needs 232 MiB; only 40 MiB left after generic queue reservations).
# The worker must own model dispatch throughout its real-image probe/run.
scheduler = (root / "runtime/ai/LocalAiRuntimePipeline.kt").read_text()
assert "fun pauseForAutomation()" in scheduler
assert "fun resumeAfterAutomation()" in scheduler
assert "suspend fun awaitAutomationIdle(timeoutMs: Long)" in scheduler
assert "if (automationExclusive || schedulerPaused) return@forEach" in scheduler
assert "if (automationExclusive) return" in scheduler
assert "StandaloneRuntime.pauseAiQueueForAutomation()" in worker
assert "StandaloneRuntime.awaitAutomationAiQueueIdle(180_000L)" in worker
assert "StandaloneRuntime.resumeAiQueueAfterAutomation()" in worker
assert worker.index("StandaloneRuntime.pauseAiQueueForAutomation()") < worker.index("StandaloneRuntime.probeAutomationExecution(")
assert "if (ownsAutomationQueue)" in worker and "} finally {" in worker
assert "AI memory is busy after 180 seconds" in worker
assert "return Result.retry()" in worker.split("if (!backgroundIdle)", 1)[1].split("if (checkpoint", 1)[0]
assert '"queued"' in worker.split("if (!backgroundIdle)", 1)[1].split("if (checkpoint", 1)[0]
assert "automationExecutionGate.lock()" in worker
assert "automationExecutionGate.unlock()" in worker
assert "private val automationExecutionGate = Mutex()" in worker
assert "continuationQueued" not in worker
assert "Worker interrupted: workId=$id stopReason=$stopReason" in worker
assert "setBackoffCriteria(" in worker
assert worker.index("automationExecutionGate.lock()") < worker.index("StandaloneRuntime.pauseAiQueueForAutomation()")
source = (root / "runtime/ImageFingerprinting.kt").read_text()
ocr = (root / "runtime/ai/PaddleOcrRuntime.kt").read_text()
fusion = (root / "runtime/FusionResolutionStore.kt").read_text()
db = (root / "runtime/LocalDatabase.kt").read_text()
coordinator = (root / "runtime/AiWorkflowCoordinator.kt").read_text()
ui = (root / "ui/screens/Screens.kt").read_text()
assert "com.openlocal.office.terabox.documents" in runtime
assert "requiresAiStaging(sourceUri)" in runtime
assert "requiresAiStaging(originalUri)" in runtime
assert 'parsed.scheme.equals("content", ignoreCase = true)' in runtime
assert "stageAiImageSource(imageId, record.uri, record.filename)" in runtime
assert "source_failure_code" in runtime
assert "failureCode" in source
assert "withModelSessionSuspend(recognizerModel)" in ocr
assert "runDetailedWithSession(recognizerSession" in ocr
assert "ocrDictionary(dictionaryPath)" in ocr
assert "aesthetic_score" in coordinator
assert "migrateReviewsToV14(db)" in db
assert "UNIQUE(image_uri, review_type)" in db
assert '"image_uri = ? AND review_type = ?"' in fusion
assert "val successful = (processed - failed - skipped - review)" in ui
assert "ExistingWorkPolicy.KEEP" in viewmodel
assert "updateAutomationStage(" in runtime
print("PASS: non-destructive cloud staging, OCR session reuse, durable background recovery and truthful automation counts")
