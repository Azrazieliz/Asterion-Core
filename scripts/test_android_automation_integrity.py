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
for name in ("ROUTE_PROMOTION_TRASH", "ROUTE_JUNK_TRASH", "ROUTE_NO_CHARACTER_TRASH"):
    chunk = policy.split(f"route = {name},", 1)[1].split(")", 1)[0]
    assert "requiresReview = true" in chunk, name
assert "required nonnegative routing." in backends
assert "requested_generation >= n_context_tokens - n_prompt_tokens" in native
print("PASS: non-destructive image automation, complete-stage gating, verified relocation, retry recovery and routing safety")
