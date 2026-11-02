package com.ailm.android.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import android.os.SystemClock
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Constraints
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ailm.android.runtime.StandaloneRuntime

class InitialSetupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            StandaloneRuntime.detectAiHardwareProfile()
            StandaloneRuntime.validateLocalAiInfrastructure()
            StandaloneRuntime.resumeAiQueue()
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }
}

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val installId = inputData.getString(INSTALL_ID_KEY)?.trim().orEmpty()
        if (installId.isBlank()) {
            return Result.failure()
        }
        return runCatching {
            val result = StandaloneRuntime.downloadAiModel(installId)
            when {
                result["ok"] == true -> Result.success()
                result["retryable"] == true && runAttemptCount < MAX_RETRY_ATTEMPTS -> Result.retry()
                else -> Result.failure()
            }
        }.getOrElse {
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val INSTALL_ID_KEY = "install_id"
        private const val MAX_RETRY_ATTEMPTS = 3
    }
}


class CharacterSheetIndexWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            StandaloneRuntime.initialize(applicationContext)
            var remaining = StandaloneRuntime.countPendingCharacterSheetIndexes()
            if (remaining <= 0) return Result.success()

            val initial = remaining
            var indexed = 0
            var failed = 0
            safeForeground(indexed, initial, "Preparing character visual index")

            while (!isStopped && remaining > 0 && indexed + failed < MAX_SHEETS_PER_RUN) {
                val result = StandaloneRuntime.indexPendingCharacterSheets(BATCH_SIZE)
                val added = (result["indexed"] as? Number)?.toInt() ?: 0
                val batchFailed = (result["failed"] as? Number)?.toInt() ?: 0
                indexed += added
                failed += batchFailed
                remaining = (result["remaining"] as? Number)?.toInt()
                    ?: StandaloneRuntime.countPendingCharacterSheetIndexes()

                setProgress(
                    workDataOf(
                        "indexed" to indexed,
                        "failed" to failed,
                        "remaining" to remaining,
                        "initial" to initial,
                    ),
                )
                val label = "Indexed " + indexed + " character sheet" +
                    (if (indexed == 1) "" else "s") + "; " + remaining + " remaining"
                safeForeground(indexed, initial, label)

                if (added == 0 && batchFailed > 0) break
                if (added == 0 && batchFailed == 0) break
            }

            when {
                isStopped -> Result.success()
                remaining <= 0 -> Result.success(
                    workDataOf("indexed" to indexed, "failed" to failed, "remaining" to 0),
                )
                failed > 0 && runAttemptCount >= MAX_RETRY_ATTEMPTS -> Result.failure(
                    workDataOf("indexed" to indexed, "failed" to failed, "remaining" to remaining),
                )
                else -> Result.retry()
            }
        }.getOrElse {
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private suspend fun safeForeground(indexed: Int, total: Int, label: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        try {
            setForeground(foregroundInfo(indexed, total, label))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("AsterionCharacterIndex", "Foreground promotion unavailable; continuing WorkManager job", error)
        }
    }

    private fun foregroundInfo(indexed: Int, total: Int, text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "AsterionCore character indexing",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val max = total.coerceAtLeast(1)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("AsterionCore character visual index")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(indexed < total)
            .setProgress(max, indexed.coerceIn(0, max), false)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "asterion_character_sheet_index"
        private const val CHANNEL_ID = "asterion_character_index"
        private const val NOTIFICATION_ID = 4211
        private const val BATCH_SIZE = 12
        private const val MAX_SHEETS_PER_RUN = 240
        private const val MAX_RETRY_ATTEMPTS = 4
    }
}


/**
 * Low-frequency recovery for images intentionally deferred after a complete
 * pass. Only pending/retry-required images are retried; analyzed artwork and
 * the immutable Canonical Knowledge are never reset. Never resumes an
 * explicitly paused/stopped run.
 */
class AutomationRecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            StandaloneRuntime.initialize(applicationContext)
            val state = StandaloneRuntime.automationStatus()
            val priorStatus = state["automation_status"]?.toString().orEmpty()
            // Never override an explicit user Pause or Stop.
            if (priorStatus !in setOf("running", "queued", "failed", "completed_with_errors") ||
                StandaloneRuntime.automationPauseRequested()) return@withContext Result.success()

            val workManager = WorkManager.getInstance(applicationContext)
            if (workManager.getWorkInfosForUniqueWork(LibraryAutomationWorker.UNIQUE_WORK_NAME)
                    .get().any { !it.state.isFinished }) return@withContext Result.success()

            val unfinishedOnly = priorStatus in setOf("failed", "completed_with_errors")
            val checkpoint = StandaloneRuntime.automationCheckpoint()
            val forceAll = !unfinishedOnly && checkpoint["force_all"] == true
            if (StandaloneRuntime.automationReadiness()["ready"] != true) return@withContext Result.success()
            val pending = StandaloneRuntime.countAutomationImages(false)
            if (pending <= 0 && unfinishedOnly) {
                workManager.cancelUniqueWork(LibraryAutomationWorker.RECOVERY_WORK_NAME)
                return@withContext Result.success()
            }
            if (unfinishedOnly) StandaloneRuntime.resetAutomationCheckpoint(false)
            val priorTotal = (state["automation_total"] as? Number)?.toInt() ?: 0
            val processed = if (unfinishedOnly) 0 else (state["automation_processed"] as? Number)?.toInt() ?: 0
            StandaloneRuntime.updateAutomationStatus(
                status = "queued",
                total = if (unfinishedOnly) pending else priorTotal,
                processed = processed,
                failed = if (unfinishedOnly) 0 else (state["automation_failed"] as? Number)?.toInt() ?: 0,
                review = if (unfinishedOnly) 0 else (state["automation_review"] as? Number)?.toInt() ?: 0,
                skipped = if (unfinishedOnly) 0 else (state["automation_skipped"] as? Number)?.toInt() ?: 0,
                message = if (unfinishedOnly)
                    "Retrying $pending unfinished image(s) after an earlier failure."
                    else "Recovering interrupted automation at image checkpoint $processed / $priorTotal.",
            )
            workManager.enqueueUniqueWork(
                LibraryAutomationWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LibraryAutomationWorker>()
                    .setInputData(workDataOf(LibraryAutomationWorker.FORCE_ALL_KEY to forceAll))
                    .build(),
            ).result.get()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("AsterionAutomation", "Deferred retry scheduling failed", e)
            Result.retry()
        }
    }
}

class LibraryAutomationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // WorkManager may cancel and restart a worker while a native model
        // dispatch is still completing. Never overlap two automation probes:
        // the first worker must release its memory reservations before another
        // can start. Mutex.lock is cancellable for any replacement worker.
        automationExecutionGate.lock()
        var ownsAutomationQueue = false
        try {
        return runCatching {
            StandaloneRuntime.initialize(applicationContext)
            // The generic queue can hold >2.8 GiB of reservations while OCR
            // needs another 232 MiB. Do not let it compete with this workflow.
            StandaloneRuntime.pauseAiQueueForAutomation()
            ownsAutomationQueue = true
            // Watchdog is independent of the active WorkManager chain. If
            // Android stops the last job and never dispatches its successor,
            // a future recovery can requeue from the durable cursor.
            scheduleDeferredRecovery()

            val forceAll = inputData.getBoolean(FORCE_ALL_KEY, false)
            val initial = StandaloneRuntime.automationStatus()
            val checkpoint = StandaloneRuntime.automationCheckpoint()
            var cursorOrder = (checkpoint["cursor_order"] as? Number)?.toLong() ?: -1L
            var cursorId = (checkpoint["cursor_id"] as? Number)?.toInt() ?: 0
            var total = (initial["automation_total"] as? Number)?.toInt() ?: 0
            if (total <= 0 && cursorId == 0) total = StandaloneRuntime.countAutomationImages(forceAll)
            var processed = (initial["automation_processed"] as? Number)?.toInt() ?: 0
            var failed = (initial["automation_failed"] as? Number)?.toInt() ?: 0
            var review = (initial["automation_review"] as? Number)?.toInt() ?: 0
            var skipped = (initial["automation_skipped"] as? Number)?.toInt() ?: 0
            var consecutiveFailures = 0
            var lastFailureSignature = ""
            var consecutiveSourceDeferrals = 0
            var lastSourceFailure = ""

            fun status(label: String, message: String, imageId: Int = 0, saveCursor: Boolean = false) {
                StandaloneRuntime.updateAutomationStatus(
                    status = label, total = total, processed = processed, failed = failed,
                    review = review, skipped = skipped, currentImageId = imageId,
                    message = message,
                    cursorOrder = if (saveCursor) cursorOrder else null,
                    cursorImageId = if (saveCursor) cursorId else null,
                )
                // ADB-accessible counters for production APKs where run-as is
                // unavailable. Log no artwork paths or private AI outputs.
                Log.i("AsterionAutomation",
                    "state=$label attempted=$processed total=$total failed=$failed review=$review " +
                        "deferred=$skipped currentImageId=$imageId cursorSaved=$saveCursor")
            }

            val readiness = StandaloneRuntime.automationReadiness()
            if (readiness["ready"] != true) {
                status("failed", readiness["message"]?.toString().orEmpty()
                    .ifBlank { "Automation model readiness failed." })
                return Result.failure()
            }

            val firstBatch = StandaloneRuntime.automationImageBatch(forceAll, cursorOrder, cursorId, BATCH_SIZE)
            if (firstBatch.isEmpty()) {
                if (failed > 0 || skipped > 0) scheduleDeferredRecovery()
                else cancelDeferredRecovery()
                status(
                    if (failed > 0 || skipped > 0) "completed_with_errors" else "completed",
                    "Attempted $processed / $total; succeeded ${(processed - failed - skipped - review).coerceAtLeast(0)}, review $review, failed $failed, deferred $skipped.",
                )
                return Result.success()
            }

            safeForeground(processed, total, "Preparing library automation")
            status("running", "Waiting for existing AI tasks to release memory before validation.")
            val backgroundIdle = withContext(Dispatchers.IO) {
                StandaloneRuntime.awaitAutomationAiQueueIdle(180_000L)
            }
            if (!backgroundIdle) {
                status("failed", "Background AI tasks still hold memory after 180 seconds. Retry when those model tasks finish; no artwork was changed.")
                return Result.failure()
            }
            if (checkpoint["probed"] != true) {
                status("running", "Validating actual AI execution using a small image sample.")
                var probe = withContext(Dispatchers.IO) { StandaloneRuntime.probeAutomationExecution(firstBatch.map { it.imageId }) }
                if (probe["ready"] != true && probe["recoverable"] == true) {
                    // For a huge library, a full remote rescan may take days. Do not
                    // silently rescan 300K cloud files because 16 URIs are stale.
                    if (total > MAX_AUTO_RESCAN_IMAGES) {
                        probe = probe + mapOf(
                            "message" to "No readable image in the probe sample. Rescan the affected library folders manually. Automatic whole-library rescanning is disabled above $MAX_AUTO_RESCAN_IMAGES images.",
                        )
                    } else {
                        status("running", "Refreshing stale document access before inference.")
                        val rescans = StandaloneRuntime.rescanEnabledFolders()
                        if (rescans.any { it["ok"] == true }) {
                            val fresh = StandaloneRuntime.automationImageBatch(forceAll, cursorOrder, cursorId, BATCH_SIZE)
                            if (fresh.isNotEmpty()) probe = withContext(Dispatchers.IO) { StandaloneRuntime.probeAutomationExecution(fresh.map { it.imageId }) }
                        }
                    }
                }
                if (probe["ready"] != true) {
                    status("failed", probe["message"]?.toString().orEmpty()
                        .ifBlank { "Actual AI inference probe failed." },
                        (probe["image_id"] as? Number)?.toInt() ?: 0)
                    return Result.failure()
                }
                StandaloneRuntime.markAutomationProbeComplete()
            }

            val startedAt = SystemClock.elapsedRealtime()
            var imagesThisWorker = 0
            var finished = false
            while (!isStopped &&
                !StandaloneRuntime.automationPauseRequested() &&
                imagesThisWorker < MAX_IMAGES_PER_WORKER &&
                SystemClock.elapsedRealtime() - startedAt < SOFT_TIME_SLICE_MS
            ) {
                val batch = StandaloneRuntime.automationImageBatch(
                    forceAll, cursorOrder, cursorId,
                    minOf(BATCH_SIZE, MAX_IMAGES_PER_WORKER - imagesThisWorker),
                )
                if (batch.isEmpty()) { finished = true; break }
                for (candidate in batch) {
                    if (isStopped || StandaloneRuntime.automationPauseRequested()) break
                    val imageId = candidate.imageId
                    status("running", "Attempting image ${processed + 1} of $total", imageId)
                    setProgress(workDataOf("processed" to processed, "total" to total,
                        "failed" to failed, "review" to review, "current_image_id" to imageId))
                    safeForeground(processed, total, "Processing image ${processed + 1} of $total")
                    val result = withContext(Dispatchers.IO) {
                        StandaloneRuntime.runAutonomousImageWorkflow(mapOf("image_id" to imageId))
                    }
                    val workflow = result["workflow"] as? Map<*, *>
                    val organization = result["organization"] as? Map<*, *>
                    val needsReview = workflow?.get("queued_for_review") == true
                    val organizationFailed = organization?.get("ok") == false &&
                        organization["status"]?.toString() !in setOf("skipped", "unchanged", "waiting_for_knowledge")
                    val stageFailures = (result["automation_stage_failures"] as? Number)?.toInt() ?: 0
                    val deferredOrSkipped = ((result["automation_skipped"] as? Number)?.toInt() ?: 0) > 0
                    val pipelineFailed = !deferredOrSkipped && (result["ok"] != true || stageFailures > 0)
                    when {
                        deferredOrSkipped -> {
                            skipped++; consecutiveFailures = 0; lastFailureSignature = ""
                            val sourceFailure = result["source_provider"]?.toString().orEmpty() +
                                ":" + result["source_failure_code"]?.toString().orEmpty()
                            consecutiveSourceDeferrals = if (sourceFailure == lastSourceFailure) {
                                consecutiveSourceDeferrals + 1
                            } else 1
                            lastSourceFailure = sourceFailure
                        }
                        pipelineFailed || organizationFailed -> {
                            consecutiveSourceDeferrals = 0; lastSourceFailure = ""
                            failed++
                            val signature = sequenceOf(
                                result["status"]?.toString(), result["message"]?.toString(),
                                organization?.get("status")?.toString(), organization?.get("message")?.toString(),
                            ).filterNotNull().map(String::trim).filter(String::isNotBlank).joinToString(" | ")
                            consecutiveFailures = if (signature.isNotBlank() && signature == lastFailureSignature) consecutiveFailures + 1 else 1
                            lastFailureSignature = signature
                        }
                        needsReview -> {
                            review++; consecutiveFailures = 0; lastFailureSignature = ""
                            consecutiveSourceDeferrals = 0; lastSourceFailure = ""
                        }
                        else -> {
                            consecutiveFailures = 0; lastFailureSignature = ""
                            consecutiveSourceDeferrals = 0; lastSourceFailure = ""
                        }
                    }
                    processed++
                    imagesThisWorker++
                    cursorOrder = candidate.importedOrder
                    cursorId = imageId
                    // Commit progress + cursor after every complete image. Android
                    // process death or worker restart cannot reset the 300K run.
                    status("running", "Attempted $processed / $total; success ${(processed - failed - skipped - review).coerceAtLeast(0)}, failed $failed, review $review, deferred $skipped.", imageId, saveCursor = true)
                    // Stop a systemic provider outage early. Retain every
                    // deferred catalog entry for the retry worker.
                    if (consecutiveSourceDeferrals >= 5) {
                        status("failed",
                            "Source access blocked for $lastSourceFailure after " +
                                "$consecutiveSourceDeferrals attempts; originals retained.",
                            imageId)
                        return Result.failure()
                    }
                    if (consecutiveFailures >= 3) {
                        status("failed", "Stopped after three identical failures; failed image IDs remain pending for next retry run: $lastFailureSignature", imageId)
                        return Result.failure()
                    }
                    if (SystemClock.elapsedRealtime() - startedAt >= SOFT_TIME_SLICE_MS) break
                }
            }

            if (StandaloneRuntime.automationPauseRequested()) {
                status("paused", "Paused at $processed / $total; resume continues from the saved image cursor.")
                return Result.success()
            }
            if (isStopped) {
                Log.w("AsterionAutomation", "Worker interrupted: workId=$id stopReason=$stopReason")
                // An Android scheduler interruption is not a user Stop.
                status("queued", "Android interrupted worker at $processed / $total; " +
                    "checkpoint retained for automatic recovery.")
                return Result.retry()
            }
            if (!finished && StandaloneRuntime.automationImageBatch(forceAll, cursorOrder, cursorId, 1).isEmpty()) finished = true
            if (finished) {
                if (failed > 0 || skipped > 0) scheduleDeferredRecovery()
                else cancelDeferredRecovery()
                status(
                    if (failed > 0 || skipped > 0) "completed_with_errors" else "completed",
                    "Attempted $processed / $total; succeeded ${(processed - failed - skipped - review).coerceAtLeast(0)}, review $review, failed $failed, deferred $skipped.",
                )
                setProgress(workDataOf("processed" to processed, "total" to total,
                    "failed" to failed, "review" to review, "skipped" to skipped))
                Result.success()
            } else {
                // Chain a bounded continuation. Each WorkRequest runs for a short
                // timeslice; WorkManager persists the chain across app process death.
                val next = OneTimeWorkRequestBuilder<LibraryAutomationWorker>()
                    .setInputData(workDataOf(FORCE_ALL_KEY to forceAll))
                    .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30L, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                    UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, next,
                ).result.get()
                status("running", "Checkpoint saved at $processed / $total; next automation batch queued.")
                Result.success()
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            val current = runCatching { StandaloneRuntime.automationStatus() }.getOrDefault(emptyMap())
            StandaloneRuntime.updateAutomationStatus(
                status = "failed",
                total = (current["automation_total"] as? Number)?.toInt() ?: 0,
                processed = (current["automation_processed"] as? Number)?.toInt() ?: 0,
                failed = ((current["automation_failed"] as? Number)?.toInt() ?: 0) + 1,
                review = (current["automation_review"] as? Number)?.toInt() ?: 0,
                skipped = (current["automation_skipped"] as? Number)?.toInt() ?: 0,
                currentImageId = (current["automation_current_image_id"] as? Number)?.toInt() ?: 0,
                message = error.message ?: error.javaClass.simpleName,
            )
            if (runAttemptCount < MAX_AUTOMATION_RETRIES) Result.retry() else Result.failure()
        }
        } finally {
            if (ownsAutomationQueue) {
                // Never hold a process-local AI queue lock across WorkManager
                // jobs: the next job may be delayed or canceled by Android.
                // Each continuation reacquires exclusivity and waits for
                // already-running ordinary AI tasks to finish.
                runCatching { StandaloneRuntime.resumeAiQueueAfterAutomation() }
            }
            automationExecutionGate.unlock()
        }
    }

    private fun scheduleDeferredRecovery() {
        val req = PeriodicWorkRequestBuilder<AutomationRecoveryWorker>(
            2, java.util.concurrent.TimeUnit.HOURS,
        ).setConstraints(
            Constraints.Builder().setRequiresBatteryNotLow(true).build()
        ).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            RECOVERY_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req,
        )
    }

    private fun cancelDeferredRecovery() {
        WorkManager.getInstance(applicationContext).cancelUniqueWork(RECOVERY_WORK_NAME)
    }

    /**
     * Promote user-initiated local image processing to foreground execution
     * whenever Android allows it. A scheduled background continuation might
     * be denied promotion, in which case its durable WorkManager checkpoint
     * remains authoritative and OS quotas still apply.
     */
    private suspend fun safeForeground(processed: Int, total: Int, label: String) {
        try {
            setForeground(foregroundInfo(processed, total, label))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("AsterionAutomation", "Foreground promotion denied; continuing scheduled worker", error)
        }
    }

    private fun foregroundInfo(processed: Int, total: Int, text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "AsterionCore automation",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val max = total.coerceAtLeast(1)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("AsterionCore automation")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(total > 0 && processed < total)
            .setProgress(max, processed.coerceIn(0, max), total <= 0)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        private val automationExecutionGate = Mutex()
        const val UNIQUE_WORK_NAME = "asterion_library_automation"
        const val RECOVERY_WORK_NAME = "asterion_library_automation_deferred_recovery"
        const val FORCE_ALL_KEY = "force_all"
        private const val CHANNEL_ID = "asterion_automation"
        private const val NOTIFICATION_ID = 4207
        private const val MAX_AUTOMATION_RETRIES = 2
        private const val BATCH_SIZE = 16
        private const val MAX_IMAGES_PER_WORKER = 32
        private const val SOFT_TIME_SLICE_MS = 6L * 60L * 1000L
        private const val MAX_AUTO_RESCAN_IMAGES = 2_000
    }
}
