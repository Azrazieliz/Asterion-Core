package com.ailm.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurablePreviewSizingTest {
    @Test fun squareIs720NotUnnecessary1280() {
        assertEquals(720 to 720, DurablePreviewSizing.outputDimensions(4096, 4096))
    }
    @Test fun landscapeAndPortraitKeep720ShortEdge() {
        assertEquals(1280 to 720, DurablePreviewSizing.outputDimensions(3840, 2160))
        assertEquals(720 to 1280, DurablePreviewSizing.outputDimensions(2160, 3840))
    }
    @Test fun neverFabricatesMissingPixels() {
        assertNull(DurablePreviewSizing.outputDimensions(640, 1024))
    }
    @Test fun extremePanoramaIsNotSilentlyStoredBelow720() {
        assertNull(DurablePreviewSizing.outputDimensions(8000, 800))
    }
}
