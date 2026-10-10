package com.ailm.android.runtime.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAiMemoryReservationTest {
    private val mib = 1024L * 1024L
    private val gib = 1024L * mib

    @Test
    fun aestheticOnnxUsesOneModelPlusExecutionHeadroomNotFourCopies() {
        assertEquals(1792L * mib, estimateAestheticOnnxReservationBytes(1L * gib))
        assertEquals(304L * mib, estimateAestheticOnnxReservationBytes(0L))
    }

    @Test
    fun oneLargeReservationFitsWhenSystemHeadroomRemains() = runBlocking {
        val manager = createManager(4L * gib)
        assertNotNull(manager.reserve("aesthetic", 2500L * mib))
        assertNull(manager.reserve("other-model", 800L * mib))
        // The second request cannot consume the 25% held back for Android.
        manager.release("aesthetic")
        assertNotNull(manager.reserve("other-model", 800L * mib))
        manager.release("other-model")
    }

    @Test
    fun explicitCapCannotExceedDeviceSafeBudget() = runBlocking {
        val capped = createManager(4L * gib, 1L * gib)
        assertNull(capped.reserve("too-large", 1500L * mib))
        assertNotNull(capped.reserve("small", 800L * mib))
        val oversizedCap = createManager(4L * gib, 7L * gib)
        assertNull(oversizedCap.reserve("too-large", 3500L * mib))
    }

    @Test
    fun insufficientAvailableRamDoesNotFabricateMinimumBudget() = runBlocking {
        assertNull(createManager(200L * mib).reserve("aesthetic", 1L * mib))
    }

    private fun createManager(availableBytes: Long, configuredLimit: Long = 0L) = LocalAiMemoryManager(
        hardwareProvider = {
            AiHardwareProfile(
                cpuCores = 8, gpuAvailable = true, npuAvailable = false,
                totalRamBytes = 12L * gib, availableRamBytes = availableBytes,
                totalStorageBytes = 128L * gib, availableStorageBytes = 64L * gib,
                threadCount = 8, simdFeatures = emptyList(), abiList = listOf("arm64-v8a"),
                batterySaverEnabled = false, charging = true, thermalStatus = 0,
                deviceManufacturer = "Test", deviceModel = "Test", capturedAtMs = 0L,
            )
        },
        settingsProvider = { AiSettings(maxReservedRamBytes = configuredLimit) },
    )
}
