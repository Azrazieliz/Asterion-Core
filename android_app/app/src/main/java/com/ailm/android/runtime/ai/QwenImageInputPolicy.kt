package com.ailm.android.runtime.ai

/**
 * Qwen-VL's image-input contract is shared by the native decoder and JVM
 * regressions. Android BitmapFactory.decodeStream returns null in
 * inJustDecodeBounds mode; the recorded dimensions, not the return value,
 * establish whether an image was detected.
 */
internal object QwenImageInputPolicy {
    private val INPUT_KEYS = listOf(
        "image_uri", "uri", "source_path", "image_path", "file_path", "path",
    )

    data class Bounds(val width: Int, val height: Int) {
        val valid: Boolean get() = width > 0 && height > 0
    }

    fun candidates(payload: Map<String, Any>): List<String> =
        INPUT_KEYS.mapNotNull { key ->
            payload[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct()

    /**
     * Ignore the decoder's optional bitmap return value. With bounds-only
     * decoding the returned bitmap is deliberately null on Android.
     */
    fun inspectBounds(decode: (report: (width: Int, height: Int) -> Unit) -> Any?): Bounds {
        var width = 0
        var height = 0
        decode { decodedWidth, decodedHeight ->
            width = decodedWidth
            height = decodedHeight
        }
        return Bounds(width, height)
    }
}
