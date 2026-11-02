package com.ailm.android.workers

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ailm.android.runtime.StandaloneRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Pre-generates durable app-owned small previews without waiting for a gallery
 * tile to become visible. Never accesses a source with rename/move/delete.
 *
 * WorkManager input carries a keyset continuation so even hundreds of
 * thousands of indexed files are processed in bounded, restartable slices.
 * Per-source failures are cooled down in SQLite; one dead cloud provider
 * must not block every other preview.
 */
class ArtworkThumbnailWarmupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            StandaloneRuntime.initialize(applicationContext)
            val afterOrder = inputData.getLong(CURSOR_ORDER_KEY, -1L)
            val afterId = inputData.getInt(CURSOR_ID_KEY, 0)
            var cursorOrder = afterOrder
            var cursorId = afterId
            var succeeded = 0
            var deferred = 0
            var attempted = 0
            val start = SystemClock.elapsedRealtime()

            if (afterOrder < 0L) {
                // Reconcile SQLite entries whose app-owned preview files were
                // evicted or lost before querying new work. No originals touched.
                StandaloneRuntime.manageThumbnailCache(Long.MAX_VALUE)
            }

            while (!isStopped && attempted < MAX_PER_WORKER &&
                SystemClock.elapsedRealtime() - start < SLICE_MS
            ) {
                val candidates = StandaloneRuntime.pendingArtworkPreviews(
                    cursorOrder, cursorId, minOf(BATCH_SIZE, MAX_PER_WORKER - attempted),
                )
                if (candidates.isEmpty()) break
                for (item in candidates) {
                    if (isStopped) break
                    val outcome = StandaloneRuntime.generateIndexedArtworkPreview(
                        item.uri, item.sizeBytes,
                    )
                    when (outcome) {
                        "stored", "cached" -> succeeded++
                        "provider_busy" -> deferred++
                        else -> {
                            StandaloneRuntime.markArtworkPreviewFailure(item.uri, outcome)
                            deferred++
                        }
                    }
                    attempted++
                    cursorOrder = item.importedOrder
                    cursorId = item.imageId
                    setProgress(
                        workDataOf(
                            "preview_attempted" to attempted,
                            "preview_saved" to succeeded,
                            "preview_deferred" to deferred,
                            "last_image_id" to cursorId,
                        ),
                    )
                    if (SystemClock.elapsedRealtime() - start >= SLICE_MS) break
                }
            }

            if (isStopped) return@withContext Result.retry()

            val remaining = StandaloneRuntime.pendingArtworkPreviews(cursorOrder, cursorId, 1)
            if (remaining.isNotEmpty()) {
                val next = OneTimeWorkRequestBuilder<ArtworkThumbnailWarmupWorker>()
                    .setConstraints(Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build())
                    .setInputData(workDataOf(
                        CURSOR_ORDER_KEY to cursorOrder,
                        CURSOR_ID_KEY to cursorId,
                    ))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                    UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, next,
                ).result.get()
            }
            val hasMore = remaining.isNotEmpty()
            Log.i(TAG, "Preview batch attempted=$attempted saved=$succeeded deferred=$deferred hasMore=$hasMore")
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Preview checkpoint will retry without modifying any original artwork", error)
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "asterion_indexed_artwork_preview_warmup"
        const val CURSOR_ORDER_KEY = "cursor_imported_order"
        const val CURSOR_ID_KEY = "cursor_image_id"
        private const val TAG = "AsterionPreviewWarmup"
        private const val BATCH_SIZE = 16
        private const val MAX_PER_WORKER = 48
        private const val SLICE_MS = 3L * 60L * 1000L
    }
