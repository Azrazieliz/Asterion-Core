package com.ailm.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Small high-contrast line icons for the image-first Gallery overlay/dock. */
enum class GalleryGlyph { Cloud, Search, Filter, More, Pictures, Collections, Organize, Review }

@Composable
fun GalleryLineIcon(
    glyph: GalleryGlyph,
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = Color.White,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(
            width = 1.8.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(w * x1, h * y1), Offset(w * x2, h * y2), strokeWidth = stroke.width,
                cap = StrokeCap.Round)
        when (glyph) {
            GalleryGlyph.Cloud -> {
                val cloud = Path().apply {
                    moveTo(w * .27f, h * .75f)
                    cubicTo(w * .11f, h * .75f, w * .08f, h * .63f, w * .12f, h * .52f)
                    cubicTo(w * .16f, h * .43f, w * .24f, h * .40f, w * .31f, h * .42f)
                    cubicTo(w * .34f, h * .28f, w * .45f, h * .21f, w * .56f, h * .23f)
                    cubicTo(w * .68f, h * .24f, w * .74f, h * .33f, w * .76f, h * .44f)
                    cubicTo(w * .90f, h * .44f, w * .94f, h * .53f, w * .91f, h * .64f)
                    cubicTo(w * .88f, h * .72f, w * .81f, h * .75f, w * .73f, h * .75f)
                    close()
                }
                drawPath(cloud, tint, style = stroke)
            }
            GalleryGlyph.Search -> {
                drawCircle(tint, radius = w * .29f,
                    center = Offset(w * .44f, h * .43f), style = stroke)
                line(.65f, .65f, .87f, .87f)
            }
            GalleryGlyph.Filter -> {
                line(.16f, .26f, .84f, .26f)
                line(.16f, .50f, .84f, .50f)
                line(.16f, .74f, .84f, .74f)
                drawCircle(tint, radius = w * .065f,
                    center = Offset(w * .38f, h * .26f))
                drawCircle(tint, radius = w * .065f,
                    center = Offset(w * .65f, h * .50f))
                drawCircle(tint, radius = w * .065f,
                    center = Offset(w * .46f, h * .74f))
            }
            GalleryGlyph.More -> {
                for (fraction in listOf(.24f, .50f, .76f)) {
                    drawCircle(tint, radius = w * .06f,
                        center = Offset(w * .5f, h * fraction))
                }
            }
            GalleryGlyph.Pictures -> {
                drawRoundRect(tint, topLeft = Offset(w * .13f, h * .14f),
                    size = Size(w * .74f, h * .72f),
                    cornerRadius = CornerRadius(w * .09f), style = stroke)
                drawCircle(tint, radius = w * .065f,
                    center = Offset(w * .34f, h * .36f))
                line(.22f, .71f, .40f, .54f)
                line(.40f, .54f, .52f, .63f)
                line(.52f, .63f, .71f, .43f)
                line(.71f, .43f, .80f, .52f)
            }
            GalleryGlyph.Collections -> {
                drawRoundRect(tint, topLeft = Offset(w * .23f, h * .27f),
                    size = Size(w * .64f, h * .57f),
                    cornerRadius = CornerRadius(w * .07f), style = stroke)
                line(.14f, .67f, .14f, .20f)
                line(.14f, .20f, .69f, .20f)
            }
            GalleryGlyph.Organize -> {
                drawCircle(tint, radius = w * .36f,
                    center = Offset(w * .50f, h * .50f), style = stroke)
                line(.50f, .28f, .50f, .50f)
                line(.50f, .50f, .68f, .60f)
            }
            GalleryGlyph.Review -> {
                drawRoundRect(tint, topLeft = Offset(w * .16f, h * .16f),
                    size = Size(w * .68f, h * .68f),
                    cornerRadius = CornerRadius(w * .08f), style = stroke)
                line(.30f, .49f, .44f, .62f)
                line(.44f, .62f, .72f, .34f)
            }
        }
    }
}
