package com.ailm.android.runtime.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiMemoryReservationPolicyTest {
    private val mib = 1024L * 1024L

    @Test
    fun `aesthetic scoring fits realistic 12GB handset admission budget`() {
        // Exact model size displayed in the submitted screen recording.
        val modelBytes = 1639L * mib
        val reservation = AiMemoryReservationPolicy.estimate(
            taskType = "aesthetic_scoring",
            modelSizeBytes = modelBytes,
        )
        assertTrue("Old 4x estimate was 6556 MiB and blocked this phone", reservation < 4L * 1024L * mib)
        assertTrue("Must still cover model file and scratch memory", reservation > modelBytes)
    }

    @Test
    fun `actual Qwen recording admission no longer blocks before native inference`() {
        // v2.0.7 reported 3272 MiB required against 2609 MiB available
        // for the same 1840 MiB model artifact on the 12 GiB handset.
        val estimate = AiMemoryReservationPolicy.estimate("captioning", 1840L * mib)
        assertTrue("Qwen must fit the recorded admission budget", estimate < 2609L * mib)
        assertTrue("Still reserve runtime working memory beyond the model file", estimate > 1840L * mib)
    }

    @Test
    fun `explicit memory reservations remain authoritative`() {
        val explicit = 4096L * mib
        assertEquals(explicit, AiMemoryReservationPolicy.estimate("aesthetic_scoring", mib, explicitReservationBytes = explicit))
    }

    @Test
    fun `declared native memory needs are never arbitrarily reduced`() {
        val declared = 5120L * mib
        assertEquals(declared, AiMemoryReservationPolicy.estimate("aesthetic_scoring", 1600L * mib, declaredPeakBytes = declared))
    }

    @Test
    fun `qwen vision and embedding retain separate scratch headroom`() {
        val qwen = AiMemoryReservationPolicy.estimate("captioning", 1840L * mib)
        val embedding = AiMemoryReservationPolicy.estimate("embedding_generation", 520L * mib)
        assertTrue(qwen > 1840L * mib)
        assertTrue(embedding > 520L * mib)
        assertTrue(qwen < 4L * 1024L * mib)
    }

    @Test
    fun `budget admits recorded aesthetic model without pretending RAM is unlimited`() {
        val available = 2835L * mib
        val budget = AiMemoryBudgetPolicy.fallbackBytes(available)
        assertTrue("2.3 GiB aesthetic reservation should fit the admission budget", budget >= 2304L * mib)
        assertTrue("Keep 512 MiB for the OS", budget <= available - 512L * mib)
    }

    @Test
    fun `low-memory devices keep proportional limits`() {
        assertTrue(AiMemoryBudgetPolicy.fallbackBytes(512L * mib) < 512L * mib)
        assertEquals(0L, AiMemoryBudgetPolicy.fallbackBytes(0L))
    }

    @Test
    fun `extreme sizes saturate without overflow`() {
        val value = AiMemoryReservationPolicy.estimate("aesthetic_scoring", Long.MAX_VALUE)
        assertTrue(value > 0L)
    }
}
