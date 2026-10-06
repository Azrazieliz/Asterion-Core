package com.ailm.android.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
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

            val readiness = StandaloneRuntime.automationReadiness()
            if (readiness["ready"] != true) {
                StandaloneRuntime.updateAutomationStatus(
                    status = "failed",
                    total = 0,
                    processed = 0,
                    failed = 0,
                    currentImageId = 0,
                    message = readiness["message"]?.toString().orEmpty()
                        .ifBlank { "Automation is not ready." },
                )
                return Result.failure()
            }

            val forceAll = inputData.getBoolean(FORCE_ALL_KEY, false)
            val imageIds = StandaloneRuntime.automationImageIds(forceAll)
            var processed = 0
            var failed = 0
            var review = 0
            var skipped = 0
            var consecutiveFailures = 0
            var lastFailureSignature = ""

            StandaloneRuntime.updateAutomationStatus(
                status = if (imageIds.isEmpty()) "completed" else "running",
                total = imageIds.size,
                processed = 0,
                failed = 0,
                review = 0,
                message = if (imageIds.isEmpty()) "Nothing to process." else "Validating real AI execution on one library image.",
            )
            setForeground(foregroundInfo(0, imageIds.size, "Validating AI execution"))

            if (imageIds.isNotEmpty()) {
                val probe = StandaloneRuntime.probeAutomationExecution(imageIds)
                if (probe["ready"] != true) {
                    val probeMessage = probe["message"]?.toString().orEmpty()
                        .ifBlank { "Automation execution probe failed." }
                    StandaloneRuntime.updateAutomationStatus(
                        status = "failed",
                        total = imageIds.size,
                        processed = 0,
                        failed = 0,
                        review = 0,
                        currentImageId = (probe["image_id"] as? Number)?.toInt() ?: 0,
                        message = probeMessage,
                    )
                    return Result.failure()
                }
            }

            StandaloneRuntime.updateAutomationStatus(
                status = if (imageIds.isEmpty()) "completed" else "running",
                total = imageIds.size,
                processed = 0,
                failed = 0,
                review = 0,
                message = if (imageIds.isEmpty()) "Nothing to process." else "AI execution verified. Starting library automation.",
            )
            setForeground(foregroundInfo(0, imageIds.size, "AI verified; starting automation"))

            imageIds.forEach { imageId ->
                if (StandaloneRuntime.automationPauseRequested()) {
                    StandaloneRuntime.updateAutomationStatus(
                        status = "paused",
                        total = imageIds.size,
                        processed = processed,
                        failed = failed,
                        review = review,
                        skipped = skipped,
                        currentImageId = imageId,
                        message = "Automation paused before the next image.",
                    )
                    return Result.success()
                }
                if (isStopped) {
                    StandaloneRuntime.updateAutomationStatus(
                        status = "stopped",
                        total = imageIds.size,
                        processed = processed,
                        failed = failed,
                        review = review,
                        skipped = skipped,
                        currentImageId = imageId,
                        message = "Automation stopped.",
                    )
                    return Result.success()
                }

                StandaloneRuntime.updateAutomationStatus(
                    status = "running",
                    total = imageIds.size,
                    processed = processed,
                    failed = failed,
                    review = review,
                    skipped = skipped,
                    currentImageId = imageId,
                    message = "Processing image ${processed + 1} of ${imageIds.size}",
                )
                setProgress(workDataOf(
                    "processed" to processed,
                    "total" to imageIds.size,
                    "failed" to failed,
                    "review" to review,
                    "current_image_id" to imageId,
                ))
                setForeground(foregroundInfo(processed, imageIds.size, "Processing image ${processed + 1} of ${imageIds.size}"))

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
                        skipped += 1
                        consecutiveFailures = 0
                        lastFailureSignature = ""
                    }
                    pipelineFailed || organizationFailed -> {
                        failed += 1
                        val failureSignature = sequenceOf(
                            result["status"]?.toString(),
                            result["message"]?.toString(),
                            organization?.get("status")?.toString(),
                            organization?.get("message")?.toString(),
                        ).filterNotNull()
                            .map(String::trim)
                            .filter(String::isNotBlank)
                            .joinToString(" | ")
                        if (failureSignature.isNotBlank() && failureSignature == lastFailureSignature) {
                            consecutiveFailures += 1
                        } else {
                            consecutiveFailures = 1
                            lastFailureSignature = failureSignature
                        }
                    }
                    needsReview -> {
                        review += 1
                        consecutiveFailures = 0
                        lastFailureSignature = ""
                    }
                    else -> {
                        consecutiveFailures = 0
                        lastFailureSignature = ""
                    }
                }
                processed += 1

                if (consecutiveFailures >= 3) {
                    val reason = lastFailureSignature.ifBlank { "The same automation failure repeated three times." }
                    StandaloneRuntime.updateAutomationStatus(
                        status = "failed",
                        total = imageIds.size,
                        processed = processed,
                        failed = failed,
                        review = review,
                        skipped = skipped,
                        currentImageId = imageId,
                        message = "Automation stopped after 3 identical failures instead of fake-processing the rest of the library: $reason",
                    )
                    setProgress(workDataOf(
                        "processed" to processed,
                        "total" to imageIds.size,
                        "failed" to failed,
                        "review" to review,
                        "current_image_id" to imageId,
                    ))
                    return Result.failure()
                }

                // Safe pause: the current image has completed its entire
                // analysis/review/organization transaction before pausing.
                if (StandaloneRuntime.automationPauseRequested()) {
                    StandaloneRuntime.updateAutomationStatus(
                        status = "paused",
                        total = imageIds.size,
                        processed = processed,
                        failed = failed,
                        review = review,
                        skipped = skipped,
                        currentImageId = 0,
                        message = "Automation paused after completing the current image.",
                    )
                    setProgress(workDataOf("processed" to processed, "total" to imageIds.size, "failed" to failed, "review" to review))
                    return Result.success()
                }
            }

            StandaloneRuntime.updateAutomationStatus(
                status = "completed",
                total = imageIds.size,
                processed = processed,
                failed = failed,
                review = review,
                skipped = skipped,
                message = when {
                    failed == 0 && review == 0 && skipped == 0 -> "Automation completed."
                    failed == 0 && review == 0 -> "Automation completed; $skipped stale/deferred item(s) were skipped without fake AI processing."
                    failed == 0 -> "Automation completed with $review image(s) waiting for Review and $skipped skipped."
                    review == 0 -> "Automation completed with $failed actual failure(s) and $skipped skipped."
                    else -> "Automation completed with $review image(s) in Review, $failed actual failure(s), and $skipped skipped."
                },
            )
            setProgress(workDataOf("processed" to processed, "total" to imageIds.size, "failed" to failed, "review" to review))
            Result.success()
        }.getOrElse { error ->
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
    }
}
