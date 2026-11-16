package com.ailm.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkPreviewSourcesTest {
    @Test fun corruptPreviewFallsBackToExistingOriginal() {
        val sources = ArtworkPreviewSources.fromImage(mapOf(
            "thumbnail_url" to "/app/previews/stale.webp",
            "file_url" to "content://gallery/document/42",
        ))
        assertEquals("content://gallery/document/42", ArtworkPreviewSources.fallbackFor(
            sources.preferred, sources.original, false))
        assertNull(ArtworkPreviewSources.fallbackFor(sources.original, sources.original, false))
        assertNull(ArtworkPreviewSources.fallbackFor(sources.preferred, sources.original, true))
    }

    @Test fun missingPreviewUsesOriginalWithoutInventingASecondSource() {
        val sources = ArtworkPreviewSources.fromImage(mapOf("file_url" to "content://gallery/1"))
        assertEquals(sources.original, sources.preferred)
        assertNull(ArtworkPreviewSources.fallbackFor(sources.preferred, sources.original, false))
        assertNull(ArtworkPreviewSources.fromImage(emptyMap()).preferred)
    }

    @Test fun legacyIndexedImageUriIsRetained() {
        val sources = ArtworkPreviewSources.fromImage(mapOf("uri" to "content://gallery/9"))
        assertEquals("content://gallery/9", sources.original)
        assertEquals(sources.original, sources.preferred)
    }
}
