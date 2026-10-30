# AsterionCore Android automation — source re-audit (2026-10-30)

**Repository:** `Azrazieliz/Asterion-Core`  
**Audited source:** `main` at `4d83d03decf766166dfa8fbe334012b3afe0cde3`  
**User-facing build:** v2.0.11 signed release, package `com.ailm.android`  
**Scope:** Android `LibraryAutomationWorker`, Android `StandaloneRuntime`, JNI Qwen-VL bridge and model dispatcher, Fusion persistence, image fingerprinting, SAF file operations, taxonomy, tests. This is a **source-level audit**, not a claim of real-phone execution.

## Verdict

**Not safe for unattended production use on the original 300,000+ image library.** The implementation has real SQLite keyset pagination, checkpoints, staged AI execution, some Qwen diagnostics and review routing. However, unsafe irreversible deletion, partial-stage acceptance, stale-URI handling and non-atomic file moves remain. No evidence in current source/CI establishes successful end-to-end Qwen-VL inference on the user's phone or completion of the intentionally small 362-image test.

## Findings (verified by inspected source)

### F01 — CRITICAL: Automatic irreversible deletion of detected duplicates

In `StandaloneRuntime.kt`, `automationImagePreflight()` checks exact SHA-256 and a 128-bit perceptual hash and immediately calls `executeFileOperations(action="delete_images")` upon a match (approx. L1450–1509). That reaches `executeDeleteImage()` (L3282+) and `SafStorageProvider.delete()` (L307+), deleting the underlying document before deleting its database record. `FileOperationPlan` explicitly marks image deletion `canUndo=false`. Visual matching allows a bit distance up to four when dimensions match (`ImageFingerprinting.kt` L60+), which is not proof of identical original artwork. The earlier indexed/active candidate is not checked for current readability before deleting the other copy (`FusionResolutionStore.kt` L223+). **Impact: permanent artwork loss.** A validation/default workflow must never delete originals automatically.

### F02 — CRITICAL: Early-stage failures can be omitted from final success decision

`StandaloneRuntime.runAutonomousImageWorkflow()` uses `earlyResponse["failed_stages"]` only (approx. L1249–1270), **without** enforcing `earlyResponse["ok"] == true`. `LocalAiManager.executePipelineStages()` returns `ok=false`, status `timeout` or `incompatible`, and a `stages` array, but **no** `failed_stages` field on several early exits (approx. L1599–1660). If Qwen later succeeds, `failedStages` can be empty, the workflow can be marked `succeeded`, and organization can proceed despite unfinished required OCR/NSFW/embedding/aesthetics. **Impact: AI stage skips and unsafe routing.**

### F03 — CRITICAL: Storage move commits before database update, with no recovery transaction

`StandaloneRuntime.organizeAutonomousImage()` (approx. L1700–1720) and `organizeContentCategory()` (approx. L1780–1820) execute `storageProvider.move/rename` before `repository.updateImagePathAndClearThumbnails()`. A database failure returns `database_update_failed` without restoring the original URI. `SafStorageProvider.move()` (L343–426) handles cross-folder moves as **copy + size verification + source delete**, where the verification may only compare reported lengths, then returns a new URI. There is no crash-recovery journal or rollback to reconcile source/destination and DB. **Impact: orphaned files and incorrect catalog paths** after failure or process death.

### F04 — HIGH: Failed images advance the durable cursor and processed counter

`LibraryAutomationWorker.doWork()` (approx. L251–288) increments `processed` and sets `cursorOrder/cursorId` after failed, skipped or deferred images, then commits the cursor. A record marked `retry_required` is eligible in SQLite but falls before the cursor and cannot reappear in **the same run**. After three identical failures the worker exits `Result.failure()` (approx. L290–295). `processed` is a **visited** count, not successfully analyzed/organized count. **Impact: missing work despite apparent progress.** Subsequent fresh runs can retry, but ordinary resume skips previously failed images.

### F05 — HIGH: Inaccessible SAF documents can be falsely retired

`StandaloneRuntime.automationImagePreflight()` (approx. L1380–1407) on unreadable input asks `storageProvider.exists()`; when false it calls `repository.markImageInactive(imageId)` and marks the automation state complete. `SafStorageProvider.exists()` (L188–202) also returns false if persisted read permission is missing, not only when the file is truly deleted. **Impact: a permissions/provider problem may remove an existing artwork record from the active library.** Do not equate temporary access failure with authoritative deletion.

### F06 — HIGH: Fixed 4096 multimodal token context with unbounded taxonomy prompt

`NativeRuntimeBackends.kt` (L355–360) defaults to a 4096-token context for multimodal Qwen. `semanticQwenPrompt()` (L575–648) inserts both the **full Character and Illustration Taxonomy context** directly in every image prompt plus OCR/NSFW/schema. `KnowledgeDatabase.kt` (L677–718) builds those contexts from every matching canonical tag row without a token-budget guard. The image itself also consumes multimodal context tokens. **Impact: `multimodal_context_exceeded`, truncation, or repeated preflight failure as Knowledge grows.** Use token-aware candidate narrowing, without silently discarding canonical IDs from the matching process.

### F07 — HIGH: Expensive repeated SAF reads and unbounded end-to-end runtime

`ImageFingerprinting.inspect()` (L21–59) opens the same source **three times** for complete SHA-256 hashing, dimensions and sampled image decoding, *before* normal AI decoding. Qwen's `readSingleRgbImage()` opens the file again for bounds and bitmap (`NativeRuntimeBackends.kt` L917–990). TeraBox images can also be fully materialized to app cache (`StandaloneRuntime.kt` L4109+). Cross-folder cloud moves perform copy/read and verification. **Impact: high remote I/O overhead and latency for 300k+ images.** Memory is bounded in keyset queries, but not all I/O is bounded/cached.

### F08 — HIGH: Incomplete semantic JSON can still drive confident routes

`parseAsterionSemanticBundle()` (`NativeRuntimeBackends.kt` L731+) requires a caption, routing object with content_class, tags array and subjects array, but defaults missing routing counts and booleans to 0/false. `ContentTriagePolicy.decide()` (`ContentTriagePolicy.kt` L34–165) can route zero-person, non-scenery images directly to `Trash/No Character`; other LLM decisions can move to `Trash/Promotion`, `Trash/Junk`, etc. Missing fields do not force a review state. **Impact: mistaken or underspecified model output can move real artwork** before verification. A strict contract must require meaningful routing evidence and uncertainty handling.

### F09 — MEDIUM: One global probe can block the entire run at 0/N

`LibraryAutomationWorker` (L204–231) requires a successful early-stage and Qwen probe **before** processing any items. Any failure is reflected as run failure; on a big library auto-rescan is disallowed (>2000), even if unrelated later images are readable. The prior last user-observed failure is `multimodal_prefill_failed` at 0/362. V2.0.11 adds native diagnostics, but **no real device proof** of successful inference is available in the repository. The probe is useful only when combined with retryable per-image isolation and diagnostics.

### F10 — MEDIUM: CI verification coverage is too narrow for safe automation

`scripts/test_automation_300k_sqlite.py` verifies 300,017 SQLite rows, indexed keyset pagination, and a synthetic cursor resume; it does **not** drive Android Worker lifecycle, failed-image recovery, SAF provider behavior or model inference. `scripts/test_qwen_mtmd_native_source.py` asserts source strings/static JNI contracts and a build; neither executes the user's installed Qwen projector and model on their phone. The signed v2.0.11 GitHub release is legitimate, but success in CI must not be interpreted as successful image organization.

## Functionality/verification ledger

| Capability | Source state | Real-device acceptance |
|---|---|---|
| Indexed 300K+ DB pagination | Implemented; synthetic test exists | Unverified |
| WorkManager bounded chunks and per-image checkpoint | Implemented with **failed-image skip defect** | Unverified |
| Required OCR/NSFW/embedding/aesthetic stages | Dispatched but **failure aggregation defect** | Unverified |
| Qwen-VL GGUF/mmproj JNI and JSON parsing | Implemented with diagnostics | Unverified |
| Character Knowledge/Fusion resolution and review | Implemented in coordinator | Unverified |
| Content routing and renaming | Implemented, **unsafe storage ordering** | Unsafe |
| Duplicate detection | Implemented | **Irreversible auto-delete unsafe** |
| Signed Android update | Available on GitHub with stable certificate | Package installation/inference unverified on user's phone |
| 362-image test and long-run memory stability | No successful evidence received | Unverified |

## Required remediation order

1. **Contain data-loss risk first:** disable all automatic physical deletes during automation; require non-destructive duplicate labeling/review, and preserve originals for all validation.
2. **Make success truthful:** reject early executor `ok=false` or any incomplete required stage, validate all task receipt outputs; prevent organization until all mandatory results have succeeded and passed structural checks.
3. **Make writes recoverable:** stage and journal file moves; verify destination integrity, atomically update database, confirm recoverability before source removal, handle interrupted operations and preserve permissions.
4. **Make worker accounting exact:** separately count attempted/success/failed/skipped/review; retain failed item IDs as retry work; never describe attempted images as successful; ensure resume cannot bypass retry-required images.
5. **Remove false retirement:** distinguish permission errors, temporary cloud unavailability, and authoritative deletion; retain inaccessible entries in a recoverable state.
6. **Make Qwen prompts fit:** compute actual prompt+vision token budgets, use multi-pass Knowledge candidate filtering without ignoring canonical taxonomy, and keep generation space for full semantic JSON.
7. **Avoid repeated cloud reads** by verified staging and scoped cache reuse; maintain independent originals and deterministic cleanup.
8. **Validate on phone:** real one-image early-stage + Qwen success, 10-image test, entire intentional 362-image folder, pause/restart/checkpoint, and representative large sample with memory/throughput measurements before unattended full-library use.

## Audit constraints/status

- **No original artwork deleted or moved during this audit.**
- **No model files, signing key, installed app or user data changed.**
- This report is evidence from static repository reads, not a completed phone QA pass.
- **Do not start autonomous organization on the user's original library until F01–F05 and F08 are corrected and tested.**
