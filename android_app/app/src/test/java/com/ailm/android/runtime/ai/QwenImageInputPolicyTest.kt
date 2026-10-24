package com.ailm.android.runtime.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenImageInputPolicyTest {
    @Test
    fun `bounds-only decoder returning null still reports valid image`() {
        val bounds = QwenImageInputPolicy.inspectBounds { report ->
            report(1920, 1080)
            null // BitmapFactory.decodeStream in inJustDecodeBounds mode
        }
        assertTrue(bounds.valid)
        assertEquals(1920, bounds.width)
        assertEquals(1080, bounds.height)
    }

    @Test
    fun `unreadable bounds do not pass as images`() {
        val bounds = QwenImageInputPolicy.inspectBounds { _ -> null }
        assertFalse(bounds.valid)
    }

    @Test
    fun `falls back to valid local path behind old image uri`() {
        val candidates = QwenImageInputPolicy.candidates(
            mapOf(
                "image_uri" to "content://stale/item",
                "uri" to "content://stale/item",
                "source_path" to "/data/user/0/com.ailm.android/cache/remote_ai/image.png",
            ),
        )
        assertEquals(2, candidates.size)
        assertEquals("/data/user/0/com.ailm.android/cache/remote_ai/image.png", candidates[1])
    }
}
