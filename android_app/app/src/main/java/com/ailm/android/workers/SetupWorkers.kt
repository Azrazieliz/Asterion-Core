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
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
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
            setForeground(foregroundInfo(indexed, initial, "Preparing character visual index"))

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
                setForeground(foregroundInfo(indexed, initial, label))

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


class LibraryAutomationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            StandaloneRuntime.initialize(applicationContext)
            StandaloneRuntime.resumeAiQueue()

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

            fun status(label: String, message: String, imageId: Int = 0, saveCursor: Boolean = false) {
                StandaloneRuntime.updateAutomationStatus(
                    status = label, total = total, processed = processed, failed = failed,
                    review = review, skipped = skipped, currentImageId = imageId,
                    message = message,
                    cursorOrder = if (saveCursor) cursorOrder else null,
                    cursorImageId = if (saveCursor) cursorId else null,
                )
            }

            val readiness = StandaloneRuntime.automationReadiness()
            if (readiness["ready"] != true) {
                status("failed", readiness["message"]?.toString().orEmpty()
                    .ifBlank { "Automation model readiness failed." })
                return Result.failure()
            }

            val firstBatch = StandaloneRuntime.automationImageBatch(forceAll, cursorOrder, cursorId, BATCH_SIZE)
            if (firstBatch.isEmpty()) {
                status(
                    if (failed > 0 || skipped > 0) "completed_with_errors" else "completed",
                    "Attempted $processed / $total; succeeded ${(processed - failed - skipped - review).coerceAtLeast(0)}, review $review, failed $failed, deferred $skipped.",
                )
                return Result.success()
            }

            setForeground(foregroundInfo(processed, total, "Preparing library automation"))
            if (checkpoint["probed"] != true) {
                status("running", "Validating actual AI execution using a small image sample.")
                var probe = StandaloneRuntime.probeAutomationExecution(firstBatch.map { it.imageId })
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
                            if (fresh.isNotEmpty()) probe = StandaloneRuntime.probeAutomationExecution(fresh.map { it.imageId })
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
                    setForeground(foregroundInfo(processed, total, "Processing image ${processed + 1} of $total"))
                    val result = StandaloneRuntime.runAutonomousImageWorkflow(mapOf("image_id" to imageId))
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
                        }
                        pipelineFailed || organizationFailed -> {
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
                        }
                        else -> { consecutiveFailures = 0; lastFailureSignature = "" }
                    }
                    processed++
                    imagesThisWorker++
                    cursorOrder = candidate.importedOrder
                    cursorId = imageId
                    // Commit progress + cursor after every complete image. Android
                    // process death or worker restart cannot reset the 300K run.
                    status("running", "Attempted $processed / $total; success ${(processed - failed - skipped - review).coerceAtLeast(0)}, failed $failed, review $review, deferred $skipped.", imageId, saveCursor = true)
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
                status("stopped", "Automation stopped at $processed / $total.")
                return Result.success()
            }
            if (!finished && StandaloneRuntime.automationImageBatch(forceAll, cursorOrder, cursorId, 1).isEmpty()) finished = true
            if (finished) {
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
        const val UNIQUE_WORK_NAME = "asterion_library_automation"
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
