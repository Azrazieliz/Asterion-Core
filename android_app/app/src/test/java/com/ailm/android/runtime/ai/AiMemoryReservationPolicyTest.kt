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
    fun `extreme sizes saturate without overflow`() {
        val value = AiMemoryReservationPolicy.estimate("aesthetic_scoring", Long.MAX_VALUE)
        assertTrue(value > 0L)
    }
}
