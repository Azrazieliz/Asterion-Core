package com.ailm.android.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Bounded on-device fallback using Asterion Art's technical-image scoring
 * formula. No 1.7GB aesthetic model session is needed during bulk organizing.
 *
 * Distinct from personal taste: users' 1..5-star decisions override and
 * calibrate the suggestion. RT safety ratings remain independent.
 */
internal object ArtworkTechnicalScorer {
    fun scoreFile(file: File): Double? {
        if (!file.isFile || file.length() <= 0) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth < 16 || bounds.outHeight < 16) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            try { scoreBitmap(decoded) } finally { decoded.recycle() }
        }.getOrNull()
    }

    private fun scoreBitmap(bitmap: Bitmap): Double {
        val w = min(bitmap.width, 192)
        val h = min(bitmap.height, 192)
        val preview = Bitmap.createScaledBitmap(bitmap, w, h, true)
        try {
            val pixels = IntArray(w*h)
            preview.getPixels(pixels,0,w,0,0,w,h)
            var sum=0.0
            var sumSq=0.0
            var clipped=0
            var edge=0.0
            var adjacent=0
            for (y in 0 until h) for (x in 0 until w) {
                val rgb=pixels[y*w+x]
                val l=((rgb shr 16 and 255)*299 +
                       (rgb shr 8 and 255)*587 + (rgb and 255)*114)/1000
                sum+=l
                sumSq+=l.toDouble()*l
                if(l<4 || l>251)clipped++
                if(x>0) {
                    val other=pixels[y*w+x-1]
                    val prev=((other shr 16 and 255)*299 +
                              (other shr 8 and 255)*587 + (other and 255)*114)/1000
                    edge+=abs(l-prev)
                    adjacent++
                }
            }
            val n=max(1,w*h)
            val deviation=sqrt(max(0.0,sumSq/n-(sum/n)*(sum/n)))
            val contrast=(deviation/65.0).coerceIn(0.0,1.0)
            val detail=(edge/max(1,adjacent)/22.0).coerceIn(0.0,1.0)
            val clippedRatio=clipped.toDouble()/n
            return ((0.55*contrast + 0.45*detail - 0.20*clippedRatio)
                .coerceIn(0.0,1.0)*100.0)
        } finally {
            if(preview!==bitmap) preview.recycle()
        }
    }
}
