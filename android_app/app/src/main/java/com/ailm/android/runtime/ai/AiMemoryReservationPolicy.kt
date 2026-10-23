package com.ailm.android.runtime.ai

/**
 * Logical admission control for on-device AI tasks, not a heap allocation.
 *
 * The previous fallback reserved 4x the model's entire artifact size for
 * aesthetic scoring, even for ONNX models. A 1.64 GB model consequently
 * requested ~6.56 GB against a budget equal to 60% of available physical RAM,
 * and could never start on a healthy 12 GB Android phone.
 *
 * Prefer explicit peak-memory metadata when present. Otherwise reserve a
 * workload-dependent model footprint plus scratch space, not a generic 4x.
 * The concurrent reservation budget and actual Android memory limits remain
 * enforced by LocalAiMemoryManager and the native runtime.
 */
internal object AiMemoryReservationPolicy {
    private const val MIB = 1024L * 1024L
    private const val MIN_RESERVATION_BYTES = 16L * MIB

    fun estimate(
        taskType: String,
        modelSizeBytes: Long,
        declaredPeakBytes: Long = 0L,
        explicitReservationBytes: Long = 0L,
    ): Long {
        if (explicitReservationBytes > 0L) return explicitReservationBytes
        if (declaredPeakBytes > 0L) return declaredPeakBytes.coerceAtLeast(MIN_RESERVATION_BYTES)

        val footprint = modelSizeBytes.coerceAtLeast(MIN_RESERVATION_BYTES)
        val normalizedTask = AiTaskTypes.normalize(taskType)
        val (multiplier, scratch) = when (normalizedTask) {
            "aesthetic_scoring" -> 1.25 to (256L * MIB)
            "embedding_generation", "face_feature_extraction" -> 1.35 to (256L * MIB)
            "ocr", "nsfw_classification", "classification", "detection" -> 1.25 to (128L * MIB)
            // Phone-sized quantized multimodal GGUF artifacts are mapped rather
            // than duplicated into a second full-size heap allocation.
            // 1.5x + 512 MiB was blocking a 1.84 GiB Qwen file at 0/362
            // on a healthy 12 GiB device before the native backend ran.
            // Keep additional headroom but leave actual allocation/OOM to
            // llama.cpp and Android's low-memory killer.
            "captioning", "character_recognition", "series_recognition", "tag_prediction" ->
                1.15 to (192L * MIB)
            "text_generation" -> 1.25 to (256L * MIB)
            else -> 1.5 to (256L * MIB)
        }
        val scaled = (footprint.toDouble() * multiplier).toLong()
        if (scaled >= Long.MAX_VALUE - scratch) return Long.MAX_VALUE
        return (scaled + scratch).coerceAtLeast(MIN_RESERVATION_BYTES)
    }
}


/**
 * Admission budget based on Android ActivityManager's reclaimable free RAM.
 * A fixed OS safety margin is more appropriate than reserving 40% of the
 * ever-changing available-memory snapshot on 12 GB devices.
 * Actual runtime allocation and OS low-memory handling remain authoritative.
 */
internal object AiMemoryBudgetPolicy {
    private const val MIB = 1024L * 1024L
    private const val MIN_OS_HEADROOM = 512L * MIB
    fun fallbackBytes(availableRamBytes: Long): Long {
        if (availableRamBytes <= 0L) return 0L
        if (availableRamBytes <= MIN_OS_HEADROOM * 2L) {
            return (availableRamBytes.toDouble() * 0.60).toLong()
        }
        val proportionalLimit = (availableRamBytes.toDouble() * 0.85).toLong()
        val reservedLimit = (availableRamBytes - MIN_OS_HEADROOM).coerceAtLeast(0L)
        return minOf(proportionalLimit, reservedLimit)
    }
}
