package com.ailm.android.runtime.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPackageStorageBudgetTest {
    private val mib = 1024L * 1024L

    @Test
    fun smallModelNeedsOnlyBoundedOverheadNotAQuarterGigabyte() {
        assertEquals(4L * mib, requiredModelPackageStagingBytes(0L))
        assertEquals(4L * mib + 1024L, requiredModelPackageStagingBytes(1024L))
        assertEquals(5L * mib, requiredModelPackageStagingBytes(1L * mib))
    }

    @Test
    fun largerModelsReserveAdditionalStagingAndProtectAgainstOverflow() {
        assertEquals(105L * mib, requiredModelPackageStagingBytes(100L * mib))
        assertEquals(10L * 1024L * mib + 256L * mib,
            requiredModelPackageStagingBytes(10L * 1024L * mib))
        assertEquals(Long.MAX_VALUE, requiredModelPackageStagingBytes(Long.MAX_VALUE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativePackageSizesAreRejected() {
        requiredModelPackageStagingBytes(-1L)
    }
}
