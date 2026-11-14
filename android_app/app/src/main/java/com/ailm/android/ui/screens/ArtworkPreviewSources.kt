package com.ailm.android.ui.screens

/**
 * Gallery previews retain their original input URI as a second-chance source.
 * Never mistake a stale/corrupt stored WebP for a missing original image.
 */
internal data class ArtworkPreviewSources(val preferred: String?, val original: String?) {
    companion object {
        fun fromImage(image: Map<String, Any>): ArtworkPreviewSources {
            fun value(key: String): String? = (image[key] as? String)?.trim()?.takeIf(String::isNotBlank)
            val original = value("file_url") ?: value("uri")
            return ArtworkPreviewSources(value("thumbnail_url") ?: original, original)
        }

        fun fallbackFor(failed: String?, original: String?, originalAttempted: Boolean): String? =
            original?.takeIf { !originalAttempted && it.isNotBlank() && it != failed }
    }
}
