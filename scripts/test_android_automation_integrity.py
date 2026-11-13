#!/usr/bin/env python3
"""Fail CI if unattended Android automation regains destructive or false-success paths."""
from pathlib import Path

root = Path("android_app/app/src/main/java/com/ailm/android")
runtime = (root / "runtime/StandaloneRuntime.kt").read_text()
worker = (root / "workers/SetupWorkers.kt").read_text()
viewmodel = (root / "ui/viewmodel/AppViewModel.kt").read_text()
policy = (root / "runtime/ContentTriagePolicy.kt").read_text()
backends = (root / "runtime/ai/NativeRuntimeBackends.kt").read_text()
saf = (root / "runtime/SafStorageProvider.kt").read_text()

preflight = runtime.split("private fun automationImagePreflight(", 1)[1].split(
    "private data class NativeTaggerModel(", 1)[0]
assert '"delete_images"' not in preflight, "Automation must not delete original artwork"
assert '"duplicate_review_pending"' in preflight
assert '"corrupt_review_pending"' in preflight
assert "repository.markImageInactive(imageId)" not in preflight
assert 'state = "retry_required"' in preflight

flow = runtime.split("fun runAutonomousImageWorkflow(", 1)[1].split(
    "private fun automationImagePreflight(", 1
)[0]
# Optional enrichment errors remain recorded while native visual tagging can
# proceed; neither missing stage output nor a failed optional model is success.
assert "val earlyStageProblems = incompleteEarlyStages.map" in flow
assert "val failedStages = earlyFailed + taggingFailed + subjectFailure" in flow
assert '"stage_type" to stage' in flow
assert "haveRecoverableTags" in flow
assert '"partial_tag_recovery" to failedStages.isNotEmpty()' in flow
assert "AutomationStageFailurePolicy.blocking(" in flow
assert "val blockingStageFailures =" in flow
assert '"optional_failed_stages" to optionalStageFailures' in flow
assert "val fusionClassifier = mapNativeClassifierToFusion(" in flow
assert "classifierResponse, nativeTagger, userEnabledSpecies(imageId)" in flow
assert "verifiedSpeciesIds = verifiedSpeciesIds" in runtime
assert "userEnabledSpecies(imageId)" in runtime
assert "private fun mapNativeClassifierToFusion(" in runtime
assert "val actualTags = nativeTagIds" in flow
# OCR-only promotional routing must not erase pre-existing canonical tags.
fast_review = flow.split('val fastWorkflow = aiWorkflowCoordinator.applyImageWorkflow(', 1)[1].split('val fastOrganize =', 1)[0]
assert '"partial_tag_recovery" to true' in fast_review
assert '"verified_nonblank_image" to verifiedNonblankImage' in fast_review
assert 'else blockingStageFailures.isNotEmpty()' in flow
assert 'state = "retry_required"' in flow
assert '"deferred_incomplete_analysis"' in flow
assert flow.index('needsReview -> "review_pending"') < flow.index('organized -> "complete"')
assert 'organizationComplete = !sourceCleanupPending && organized' in flow
assert '"ok" to (!pipelineFailed && !organizationFailed)' in flow
stage_policy = (root / "runtime/AutomationStageFailurePolicy.kt").read_text()
assert '"ocr", "embedding_generation", "aesthetic_scoring"' in stage_policy
assert 'stageType !in optionalEnrichment' in stage_policy

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
# Current triage is stricter than older automated Trash routing: OCR or
# classifier suspicion cannot authorize permanent source removal.
assert "val promotion = unequivocalPaidPreviewOcr(observation.ocrText)" in policy
assert '"Likely promotional paid preview. Review before quarantine or removal."' in policy
assert '"Possible blank, broken or promotional image. Original retained pending review."' in policy
assert "route = ROUTE_WAITING_FOR_KNOWLEDGE" in policy
for name in ("ROUTE_PROMOTION_TRASH", "ROUTE_JUNK_TRASH", "ROUTE_NO_CHARACTER_TRASH"):
    assert f"route = {name}," not in policy, name
assert "required nonnegative routing." in backends
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
# The actual native/OCR stage must execute, not remain stranded as pending.
inline = manager.split("private fun executePipelineStages(", 1)[1].split(
    "private fun resolvePipelineStages(", 1
)[0]
assert 'payload["direct_automation_dispatch"] == true' in inline
assert "executionQueue.enqueueTask(" in inline
assert "runBlocking { taskDispatcher.dispatch(task) }" in inline
assert inline.index("runBlocking { taskDispatcher.dispatch(task) }") < inline.index("awaitTaskTerminalState(task.taskId")
assert runtime.count('"direct_automation_dispatch" to true') >= 3
# Qwen cannot be selected for automation; only a fixed-head native tagger
# with Fusion family coverage may authorize approved observations.
assert 'val nativeTagger = resolvedNativeTaggerModel() ?: return mapOf(' in runtime
assert '"native_visual_tagger"' in runtime
assert '"recognition_mode" to "native_classifier"' in runtime
assert "resolvedQwenSemanticModel" not in runtime
assert 'if (nativeTagger != null && classifierResponse["ok"] == true)' in runtime
assert '"result" to fusionClassifier' in runtime
assert 'val earlyResponse: Map<String, Any> = if (earlyStages.isEmpty())' in runtime
assert 'coverage["evaluated_families"]' in runtime
assert "FusionVisualLabelMapper(fusionTaxonomy).evaluate(" in runtime
assert "val actualTags = nativeTagIds" in runtime

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
# The gallery must not relabel attempted/deferred processing as successful.
assert 'val processed = (state.aiOverview["automation_processed"]' in ui
assert 'val failed = (state.aiOverview["automation_failed"]' in ui
assert 'val review = (state.aiOverview["automation_review"]' in ui
assert 'val skipped = (state.aiOverview["automation_skipped"]' in ui
assert "ExistingWorkPolicy.KEEP" in viewmodel
assert "updateAutomationStage(" in runtime
# Frozen Samsung Gallery reference (2026-11-10): image-first, continuous
# scrolling, no grey Card shell or persistent Material NavigationBar slab.
gallery = ui.split("private fun DashboardScreen(", 1)[1].split(
    "private fun NativeSplashScreen()", 1)[0]
gallery_tile = ui.split("private fun ArtworkAspectCard(", 1)[1].split(
    "fun ScreenScaffold(", 1)[0]
search_gallery = ui.split("private fun LibraryBrowserScreen(", 1)[1].split(
    "private fun LegacyLibraryBrowserScreen(", 1)[0]
organize_screen = ui.split("private fun AiAutomationScreen(", 1)[1].split(
    "private fun AiModelManagerScreen(", 1)[0]
navigation = (root / "ui/navigation/AppNavHost.kt").read_text()
gallery_icons = (root / "ui/components/GalleryGlyph.kt").read_text()
for essential in ("LazyVerticalStaggeredGrid(", "GalleryLineIcon(GalleryGlyph.Search)",
                  "GalleryLineIcon(GalleryGlyph.Filter)", "GalleryLineIcon(GalleryGlyph.More)",
                  "onLoadMore()", "Choose artwork source"):
    assert essential in gallery, essential
# The source chooser remains functional in the compact overflow; removing
# its 44dp toolbar icon is the approved fix for oversized gallery controls.
assert "onNavigate(AppDestination.FolderBrowser)" in gallery
assert "onBrowseCloudFiles()" in search_gallery
assert "GalleryLineIcon(GalleryGlyph.Cloud)" not in gallery
assert "GalleryLineIcon(GalleryGlyph.Cloud)" not in search_gallery
assert '"Find artwork"' not in gallery
assert "Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen))" not in gallery_tile
assert "onDecodedAspectRatio = { ratio ->" in gallery_tile
assert "onDecodedAspectRatio?.invoke(" in ui
assert '"Previous"' not in search_gallery and '"Next"' not in search_gallery
assert '"append" to true' in search_gallery
assert "NavigationBar {" not in navigation
assert "GalleryLineIcon(" in navigation and "navigationBarsPadding()" in navigation
assert "reviewOnlyMode -> \"Analyze for review\"" in organize_screen
assert 'state.lastActionMessage?.let { AsterionStatusNotice(it) }' not in organize_screen
assert 'AsterionStatusNotice(it, isError = true)' not in organize_screen
assert "enum class GalleryGlyph" in gallery_icons
assert 'result["automation_state"] == "review_pending"' in worker

print("PASS: non-destructive cloud staging, OCR session reuse, durable background recovery and truthful automation counts")
