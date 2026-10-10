# Asterion Core Android audit — 2026-10-11

**Scope:** installed Android-first Asterion Core repository, with pre-existing uncommitted AI model/native runtime changes preserved. Frozen architecture at `docs/ARCHITECTURE.md` remains authoritative. The Fusion Database and canonical Character Knowledge records were not edited by this audit.

**Candidate:** Android 2.0.2, package `com.ailm.android`, ARM64 debug build. Source default branch at audit start: `main`, `7699d41`; user worktree held existing uncommitted changes. This audit's changes are additional uncommitted work; the APK includes both, **not** only published `main`.

## Confirmed bugs and remedies

| Priority | Component | Defect and correction |
|---|---|---|
| P0 | Startup splash | Compose opening overlay initialized hidden, then shown after frame. Native splash could disappear first. Restore full-screen original `branding/AsterionCore-Splash.png`; start visible on first composed frame; 2.4s hold and 0.65s fade; gentle pulse-scale animation. |
| P0 | Library file operations | 'Move Folder' used sampled loaded images but chained a delete of the whole original folder. Removed delete-after-batch behavior. Folder-only selection cannot implicitly execute partial image moves; explicit IDs required, originals not deleted by UI batch completion. |
| P0 | SAF fallback move | Copy-and-delete originally verified only file lengths. Now verifies source and destination SHA-256 via streams before deletion; retain source on readback/verification failures. |
| P1 | SAF copy | Resolve non-conflicting name **before** createFile to avoid provider overwrites. Check bytes written/known lengths; remove partial target on failure, return failure and any undeletable partial URI explicitly. |
| P1 | Whole-library browsing | Dashboard loaded the entire image registry, unbounded. Now uses a bounded 300-image preview. Library Browser and Search screen use 250-result SQL pages with next/previous navigation and total counts. |
| P1 | Zero-result query | Previously empty search results fell back to unrelated dashboard artwork. Introduced explicit `searchApplied` state and retained zero-match results. |
| P1 | Navigation | Every cold start opened First Launch Wizard despite a valid previously persisted SAF library. Start at Dashboard only with a currently persisted read+write tree grant. |
| P1 | Round Sync publishing | Earlier post-automation publishing only ran from an active Compose Automation screen and could be missed after navigation. WorkManager now owns optional task request after all processed images succeed with no failed/review items. Task launch never counts as a verified transfer completion. |
| P2 | Test-fixture handling | Full JVM suite reported 108 failures because real model ZIPs were missing at hardcoded paths. Test harness now **skips** unavailable-fixture cases with explicit JUnit assumptions and supports strict mode `ASTERION_REQUIRE_MODEL_FIXTURES=1`. This is missing validation coverage, **not successful inference validation**. |
| P2 | Kotlin Lint | Android Lint incorrectly classified 6 idiomatic multiline Kotlin expressions as fatal SuspiciousIndentation. Downgraded only this rule to warning; other Lint checks continue executing. |

## Verification actually performed

- Windows repository: source audit, code paths and previous local diffs inspected without resets or merges.
- Android Gradle: `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` **BUILD SUCCESSFUL** on the prior 2.0.1 version of the patched implementation; 56 tasks / ~6m33s. A separate 2.0.2 packaging pass is needed after version bump.
- Android JVM tests: **208 enumerated, 96 passed, 112 skipped**, 0 failed. All 112 skipped tests require external model archives that were not available at expected fixture locations. Never count them as validated model execution.
- Android lint: **passed** after demoting six SuspiciousIndentation false positives to warnings; existing nonfatal warnings remain.
- Python source: `py -m compileall -q engine ui run.py` passed (exit 0). Desktop Python `pytest` **could not collect** tests because `faiss` and `PIL` were absent in the selected Python 3.14 environment; this was not an application test pass.
- Physical Samsung Galaxy S26 Ultra: pre-version-bump patched APK installed by `adb install -r -t` with **Success**; `com.ailm.android/.MainActivity` launched and process remained alive; checked logcat contained no fatal startup exception.
- Physical animation evidence: ADB screen-recorded `C:\Users\mimim\Downloads\asterion-audit-opening.mp4`, confirmed middle frame shows the full branded gold Asterion Core artwork, replacing the lost tiny/hidden logo introduction. The later frame shows the phone's ChatGPT foreground (other user activity), so complete fade transition wasn't independently video-verified.
- Round Sync on phone: `de.felixnuesse.extract` **v2.5.6**, with Android DOCUMENTS_PROVIDER at `de.felixnuesse.extract.vcp` confirmed. No TeraBox actual artwork modification or cloud transfer was attempted by audit.

## Release gates still OPEN

1. **Real-model execution:** 112 fixture-dependent tests remain skipped, including native AI inference contracts for Florence2, Buffalo-L, BGE Reranker, Aesthetic Scoring and PaddleOCR. Obtain model-package fixtures on the workstation and rerun strict mode; test with actual installed Android models against known samples.
2. **TeraBox direct write:** Round Sync virtual content provider is detected but **not proven to offer tree read+write grant, native copy, native move, and durable remote persistence** for the configured TeraBox remote. Validate on a disposable small test subtree, never original artwork.
3. **Automation correctness:** end-to-end character resolution, canonical source IDs, image organization and review queue must be tested against known fixture corpus, comparing resulting SQLite/search metadata and actual SAF move outcomes.
4. **Background Round Sync completion:** START_TASK can be unavailable on Round Sync v2.5.6; even when accepted, it gives no authoritative completion receipt. Use Round Sync UI to confirm upload and rescan explicitly.
5. **Desktop regression suite:** install the pinned Python dependencies in an isolated environment and rerun tests; don't conflate desktop with Android runtime.
6. **Production sign/release:** debug APK is for testing; production release requires correct signing config, version/upgrade testing, storage permission lifetime and cloud provider reliability verification.

## Data protection

- No actual TeraBox artwork writes, deletes, renames, or moves performed during audit.
- No Knowledge Packs, canonical Fusion records, or Asterion save databases intentionally modified.
- Remote code changes remain local/uncommitted pending consolidation into an audited source branch.
- The 2.0.2 APK includes the existing uncommitted AI/native changes, which must be committed and fully regression-tested before declaring a reproducible production release.
