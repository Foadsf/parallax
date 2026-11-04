package io.github.foadsf.parallax

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class Glyph { Torch, TorchOff, TorchAuto, Stack, Back, Share, Download, Trash, Cube, Info, Copy, More, Points, Frustum, Recenter, Ruler }

/** Line icons drawn on a 24-unit grid, so the app needs no icon library. */
@Composable
fun GlyphIcon(g: Glyph, tint: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun path(vararg pts: Pair<Float, Float>, close: Boolean = false) = Path().apply {
            pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * u, y * u) else lineTo(x * u, y * u) }
            if (close) close()
        }
        when (g) {
            Glyph.Torch, Glyph.TorchOff, Glyph.TorchAuto -> {
                drawPath(path(13f to 2f, 5f to 13.5f, 11f to 13.5f, 10f to 22f, 19f to 10f, 13f to 10f, 13f to 2f, close = true), tint, style = stroke)
                if (g == Glyph.TorchOff) drawLine(tint, p(4f, 4f), p(20f, 20f), stroke.width, StrokeCap.Round)
                if (g == Glyph.TorchAuto) drawCircle(tint, 2.2f * u, p(20f, 4.5f))
            }
            Glyph.Stack -> {
                drawPath(path(12f to 3f, 21f to 8f, 12f to 13f, 3f to 8f, close = true), tint, style = stroke)
                drawPath(path(3f to 12f, 12f to 17f, 21f to 12f), tint, style = stroke)
                drawPath(path(3f to 16f, 12f to 21f, 21f to 16f), tint, style = stroke)
            }
            Glyph.Back -> drawPath(path(15f to 5f, 8f to 12f, 15f to 19f), tint, style = stroke)
            Glyph.Share -> {
                drawCircle(tint, 2.6f * u, p(18f, 5f), style = stroke)
                drawCircle(tint, 2.6f * u, p(6f, 12f), style = stroke)
                drawCircle(tint, 2.6f * u, p(18f, 19f), style = stroke)
                drawLine(tint, p(8.3f, 10.8f), p(15.7f, 6.2f), stroke.width)
                drawLine(tint, p(8.3f, 13.2f), p(15.7f, 17.8f), stroke.width)
            }
            Glyph.Download -> {
                drawLine(tint, p(12f, 3f), p(12f, 15f), stroke.width, StrokeCap.Round)
                drawPath(path(7f to 10f, 12f to 15f, 17f to 10f), tint, style = stroke)
                drawPath(path(4f to 17f, 4f to 20f, 20f to 20f, 20f to 17f), tint, style = stroke)
            }
            Glyph.Trash -> {
                drawLine(tint, p(4f, 6f), p(20f, 6f), stroke.width, StrokeCap.Round)
                drawPath(path(9f to 6f, 9f to 3.5f, 15f to 3.5f, 15f to 6f), tint, style = stroke)
                drawPath(path(6f to 6f, 7f to 20.5f, 17f to 20.5f, 18f to 6f), tint, style = stroke)
            }
            Glyph.Info -> {
                drawCircle(tint, 9.5f * u, p(12f, 12f), style = stroke)
                drawLine(tint, p(12f, 11f), p(12f, 17f), stroke.width, StrokeCap.Round)
                drawCircle(tint, 1.3f * u, p(12f, 7.5f))
            }
            Glyph.Copy -> {
                drawRoundRect(tint, topLeft = p(8f, 8f), size = androidx.compose.ui.geometry.Size(12f * u, 13f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u), style = stroke)
                drawPath(path(16f to 5f, 16f to 3.5f, 4f to 3.5f, 4f to 16f, 5.5f to 16f), tint, style = stroke)
            }
            Glyph.More -> for (y in floatArrayOf(5f, 12f, 19f)) drawCircle(tint, 1.7f * u, p(12f, y))
            Glyph.Points -> {
                drawCircle(tint, 1.2f * u, p(6f, 17f))
                drawCircle(tint, 2.0f * u, p(12f, 9f))
                drawCircle(tint, 3.0f * u, p(17.5f, 16f))
            }
            Glyph.Frustum -> {
                // A camera pyramid seen from the side: apex at the lens, image plane on the right.
                drawPath(path(15f to 4f, 21f to 7f, 21f to 17f, 15f to 20f, close = true), tint, style = stroke)
                drawPath(path(15f to 4f, 3f to 12f, 15f to 20f), tint, style = stroke)
                drawLine(tint, p(3f, 12f), p(21f, 12f), stroke.width * 0.7f, StrokeCap.Round)
            }
            Glyph.Recenter -> {
                drawCircle(tint, 6.5f * u, p(12f, 12f), style = stroke)
                drawCircle(tint, 1.8f * u, p(12f, 12f))
                for ((a, b) in listOf(p(12f, 2f) to p(12f, 5f), p(12f, 19f) to p(12f, 22f), p(2f, 12f) to p(5f, 12f), p(19f, 12f) to p(22f, 12f)))
                    drawLine(tint, a, b, stroke.width, StrokeCap.Round)
            }
            Glyph.Ruler -> {
                drawPath(path(2.5f to 15f, 15f to 2.5f, 21.5f to 9f, 9f to 21.5f, close = true), tint, style = stroke)
                for ((x, y) in listOf(6f to 11.5f, 9f to 8.5f, 12f to 5.5f)) drawLine(tint, p(x, y), p(x + 2.5f, y + 2.5f), stroke.width, StrokeCap.Round)
            }
            Glyph.Cube -> {
                drawPath(path(12f to 2.5f, 20.5f to 7f, 20.5f to 17f, 12f to 21.5f, 3.5f to 17f, 3.5f to 7f, close = true), tint, style = stroke)
                drawPath(path(3.5f to 7f, 12f to 11.5f, 20.5f to 7f), tint, style = stroke)
                drawLine(tint, p(12f, 11.5f), p(12f, 21.5f), stroke.width)
            }
        }
    }
}
