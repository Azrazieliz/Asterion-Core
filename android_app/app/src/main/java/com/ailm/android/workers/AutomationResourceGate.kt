package com.ailm.android.workers

/**
 * Prevent native inference from starting when Android reports critical memory
 * pressure or severe throttling. Unlike a failed image, a resource deferral
 * leaves its image cursor untouched and WorkManager backs off safely.
 */
internal object AutomationResourceGate {
    private const val MIN_AVAILABLE_BYTES = 600L * 1024L * 1024L
    fun shouldDefer(
        availableBytes: Long,
        lowMemory: Boolean,
        osThresholdBytes: Long,
        thermalStatus: Int,
        severeThermalStatus: Int,
    ): Boolean =
        lowMemory ||
        (availableBytes >= 0L &&
            availableBytes < maxOf(MIN_AVAILABLE_BYTES, osThresholdBytes.coerceAtLeast(0))) ||
        thermalStatus >= severeThermalStatus
}
