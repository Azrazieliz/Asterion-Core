package com.ailm.android.workers

import org.junit.Assert.*
import org.junit.Test

class AutomationResourceGateTest {
    private fun defer(memMb: Long, low: Boolean = false, thresholdMb: Long = 350,
        thermal: Int = 2): Boolean = AutomationResourceGate.shouldDefer(
        memMb * 1024L * 1024L, low, thresholdMb * 1024L * 1024L, thermal, 4,
    )

    @Test fun normalMemoryAndModerateThermalsAllowTagging() {
        assertFalse(defer(3100, thermal = 2))
        assertFalse(defer(1000, thermal = 3))
    }

    @Test fun criticalMemoryOrSevereThermalsDefersWithoutLosingImage() {
        assertTrue(defer(300))
        assertTrue(defer(2000, low = true))
        assertTrue(defer(2600, thermal = 4))
        assertTrue(defer(2600, thermal = 5))
        assertFalse(defer(700))
    }
}
