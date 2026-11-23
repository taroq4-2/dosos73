package com.os73.dosos73

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Uses the same vector geometry as the launcher, with no bitmap allocation. */
@Composable
internal fun BrandMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        scale(size.width / 108f, size.height / 80f, pivot = Offset.Zero) {
            drawPath(BrandGeometry.body, BrandGeometry.gradient)
            drawPath(BrandGeometry.details, BrandGeometry.gradient)
        }
    }
}

internal enum class Glyph {
    HOME,
    DOWNLOAD,
    FOLDER,
    MORE,
    SEARCH,
    SETTINGS,
    LINK,
    VIDEO,
    AUDIO,
    IMAGE,
    CLOCK,
    CHECK,
    CLOSE,
    PAUSE,
    PLAY,
    SHARE,
    INFO,
    SORT,
    BACK,
    RENAME,
    TRASH,
    DOTS,
    CHEVRON,
    EXPAND,
    PLUS,
    CLIPBOARD,
    FILE,
    ARCHIVE,
    APP,
    LAYERS,
    RETRY,
    BELL,
    PALETTE,
    SHIELD,
    GLOBE,
    STORAGE,
    WIFI,
    PROGRAM,
}

/** Glyphs that point along the reading direction and must mirror in RTL. */
private val directional = setOf(Glyph.BACK, Glyph.CHEVRON)

@Composable
internal fun Symbol(
    kind: Glyph,
    description: String? = null,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
) {
    val mirror = kind in directional &&
        androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Canvas(
        modifier
            .size(24.dp)
            .then(if (mirror) Modifier.graphicsLayer(scaleX = -1f) else Modifier)
            .then(if (description != null) Modifier.semanticsCompat(description) else Modifier)
    ) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            fun line(a: Float, b: Float, c: Float, d: Float) =
                drawLine(color, Offset(a, b), Offset(c, d), 1.7f, StrokeCap.Round)
            fun path(vararg points: Pair<Float, Float>, close: Boolean = false) {
                val p = Path()
                points.forEachIndexed { i, v ->
                    if (i == 0) p.moveTo(v.first, v.second) else p.lineTo(v.first, v.second)
                }
                if (close) p.close()
                drawPath(
                    p,
                    color,
                    style = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            when (kind) {
                Glyph.HOME -> {
                    path(3f to 11f, 12f to 3f, 21f to 11f)
                    path(
                        5f to 10f,
                        5f to 21f,
                        10f to 21f,
                        10f to 15f,
                        14f to 15f,
                        14f to 21f,
                        19f to 21f,
                        19f to 10f,
                    )
                }
                Glyph.DOWNLOAD -> {
                    line(12f, 3f, 12f, 16f)
                    path(6f to 11f, 12f to 17f, 18f to 11f)
                    path(4f to 19f, 4f to 21f, 20f to 21f, 20f to 19f)
                }
                Glyph.FOLDER -> {
                    path(
                        3f to 6f,
                        9f to 6f,
                        11f to 9f,
                        21f to 9f,
                        21f to 21f,
                        3f to 21f,
                        close = true,
                    )
                }
                Glyph.SEARCH -> {
                    drawCircle(color, 7f, Offset(10f, 10f), style = Stroke(1.8f))
                    line(15f, 15f, 21f, 21f)
                }
                Glyph.MORE ->
                    listOf(5f, 12f, 19f).forEach { drawCircle(color, 1.6f, Offset(it, 12f)) }
                Glyph.VIDEO -> {
                    path(3f to 6f, 16f to 6f, 16f to 19f, 3f to 19f, close = true)
                    path(16f to 10f, 22f to 7f, 22f to 18f, 16f to 15f)
                }
                Glyph.AUDIO -> {
                    path(8f to 17f, 8f to 5f, 20f to 3f, 20f to 15f)
                    drawOval(color, Offset(2f, 16f), Size(6f, 5f))
                    drawOval(color, Offset(14f, 14f), Size(6f, 5f))
                }
                Glyph.PAUSE -> {
                    line(8f, 5f, 8f, 19f)
                    line(16f, 5f, 16f, 19f)
                }
                Glyph.PLAY -> path(7f to 4f, 20f to 12f, 7f to 20f, close = true)
                Glyph.CHECK -> path(4f to 12f, 9f to 17f, 21f to 5f)
                Glyph.CLOSE -> {
                    line(5f, 5f, 19f, 19f)
                    line(19f, 5f, 5f, 19f)
                }
                Glyph.CLOCK -> {
                    drawCircle(color, 9f, Offset(12f, 12f), style = Stroke(1.7f))
                    path(12f to 6f, 12f to 12f, 17f to 15f)
                }
                Glyph.SORT -> {
                    line(3f, 5f, 21f, 5f)
                    line(3f, 12f, 16f, 12f)
                    line(3f, 19f, 11f, 19f)
                }
                Glyph.BACK -> path(15f to 4f, 7f to 12f, 15f to 20f)
                Glyph.LINK -> {
                    drawRoundRect(
                        color,
                        Offset(2f, 9f),
                        Size(13f, 7f),
                        androidx.compose.ui.geometry.CornerRadius(3f),
                        style = Stroke(1.7f),
                    )
                    drawRoundRect(
                        color,
                        Offset(9f, 4f),
                        Size(13f, 7f),
                        androidx.compose.ui.geometry.CornerRadius(3f),
                        style = Stroke(1.7f),
                    )
                }
                Glyph.SETTINGS -> {
                    drawCircle(color, 7f, Offset(12f, 12f), style = Stroke(2f))
                    drawCircle(color, 2.5f, Offset(12f, 12f), style = Stroke(1.7f))
                    (0..7).forEach { rotate(it * 45f, Offset(12f, 12f)) { line(12f, 2f, 12f, 5f) } }
                }
                Glyph.IMAGE -> {
                    path(3f to 3f, 21f to 3f, 21f to 21f, 3f to 21f, close = true)
                    path(3f to 17f, 9f to 11f, 13f to 15f, 17f to 10f, 21f to 14f)
                    drawCircle(color, 1.8f, Offset(8f, 7f))
                }
                Glyph.SHARE -> {
                    path(7f to 12f, 18f to 5f)
                    path(7f to 12f, 18f to 19f)
                    listOf(Offset(5f, 12f), Offset(19f, 4f), Offset(19f, 20f)).forEach {
                        drawCircle(color, 2f, it, style = Stroke(1.7f))
                    }
                }
                Glyph.TRASH -> {
                    path(5f to 7f, 6f to 21f, 18f to 21f, 19f to 7f)
                    line(3f, 5f, 21f, 5f)
                    path(8f to 5f, 8f to 2f, 16f to 2f, 16f to 5f)
                }
                Glyph.RENAME -> {
                    path(4f to 17f, 16f to 3f, 21f to 8f, 9f to 21f, 4f to 21f, 4f to 17f)
                }
                Glyph.DOTS -> listOf(5f, 12f, 19f).forEach { drawCircle(color, 1.7f, Offset(12f, it)) }
                Glyph.CHEVRON -> path(9f to 5f, 16f to 12f, 9f to 19f)
                Glyph.EXPAND -> path(5f to 9f, 12f to 16f, 19f to 9f)
                Glyph.PLUS -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
                Glyph.CLIPBOARD -> {
                    path(8f to 4f, 5f to 4f, 5f to 21f, 19f to 21f, 19f to 4f, 16f to 4f)
                    drawRoundRect(color, Offset(8f, 2.5f), Size(8f, 4f),
                        androidx.compose.ui.geometry.CornerRadius(1.2f), style = Stroke(1.7f))
                    line(9f, 12f, 15f, 12f); line(9f, 16f, 15f, 16f)
                }
                Glyph.FILE -> {
                    path(6f to 2.5f, 14f to 2.5f, 19f to 7.5f, 19f to 21.5f, 6f to 21.5f, close = true)
                    path(14f to 2.5f, 14f to 7.5f, 19f to 7.5f)
                    line(9f, 13f, 16f, 13f); line(9f, 17f, 14f, 17f)
                }
                Glyph.ARCHIVE -> {
                    path(4f to 4f, 20f to 4f, 20f to 20f, 4f to 20f, close = true)
                    listOf(6f, 9f, 12f).forEach { y -> line(12f, y, 12f, y + 1.5f) }
                    drawRoundRect(color, Offset(10f, 14f), Size(4f, 4f),
                        androidx.compose.ui.geometry.CornerRadius(1f), style = Stroke(1.6f))
                }
                Glyph.APP -> {
                    drawArc(color, 180f, 180f, false, Offset(5f, 6f), Size(14f, 12f), style = Stroke(1.7f))
                    path(5f to 12f, 19f to 12f, 19f to 19f, 5f to 19f, close = true)
                    line(8f, 4f, 9.5f, 6.8f); line(16f, 4f, 14.5f, 6.8f)
                    drawCircle(color, 0.9f, Offset(9.5f, 9.5f)); drawCircle(color, 0.9f, Offset(14.5f, 9.5f))
                }
                Glyph.LAYERS -> {
                    path(12f to 3f, 21f to 8f, 12f to 13f, 3f to 8f, close = true)
                    path(3f to 12.5f, 12f to 17.5f, 21f to 12.5f)
                    path(3f to 16.5f, 12f to 21.5f, 21f to 16.5f)
                }
                Glyph.RETRY -> {
                    drawArc(color, -60f, 300f, false, Offset(4f, 4f), Size(16f, 16f), style = Stroke(1.7f, cap = StrokeCap.Round))
                    path(16f to 2.5f, 19.5f to 5.5f, 15.5f to 7.5f)
                }
                Glyph.BELL -> {
                    path(6f to 17f, 6f to 11f, 7f to 7f, 12f to 4f, 17f to 7f, 18f to 11f, 18f to 17f)
                    line(4f, 17f, 20f, 17f)
                    drawArc(color, 0f, 180f, false, Offset(10f, 18f), Size(4f, 4f), style = Stroke(1.6f))
                }
                Glyph.PALETTE -> {
                    drawCircle(color, 9f, Offset(12f, 12f), style = Stroke(1.7f))
                    listOf(Offset(8f, 9f), Offset(12f, 7f), Offset(16f, 9f), Offset(9f, 14f)).forEach {
                        drawCircle(color, 1.3f, it)
                    }
                }
                Glyph.SHIELD -> {
                    path(12f to 2.5f, 20f to 5.5f, 20f to 11f, 12f to 21.5f, 4f to 11f, 4f to 5.5f, close = true)
                    path(8.5f to 12f, 11f to 14.5f, 15.5f to 9.5f)
                }
                Glyph.GLOBE -> {
                    drawCircle(color, 9f, Offset(12f, 12f), style = Stroke(1.7f))
                    drawOval(color, Offset(8f, 3f), Size(8f, 18f), style = Stroke(1.5f))
                    line(3.5f, 9f, 20.5f, 9f); line(3.5f, 15f, 20.5f, 15f)
                }
                Glyph.STORAGE -> {
                    drawOval(color, Offset(4f, 3f), Size(16f, 5f), style = Stroke(1.7f))
                    path(4f to 5.5f, 4f to 18.5f)
                    path(20f to 5.5f, 20f to 18.5f)
                    drawArc(color, 0f, 180f, false, Offset(4f, 16f), Size(16f, 5f), style = Stroke(1.7f))
                    drawArc(color, 0f, 180f, false, Offset(4f, 9.5f), Size(16f, 5f), style = Stroke(1.5f))
                }
                Glyph.WIFI -> {
                    drawArc(color, 215f, 110f, false, Offset(2f, 5f), Size(20f, 20f), style = Stroke(1.7f, cap = StrokeCap.Round))
                    drawArc(color, 215f, 110f, false, Offset(6f, 9f), Size(12f, 12f), style = Stroke(1.7f, cap = StrokeCap.Round))
                    drawCircle(color, 1.4f, Offset(12f, 18.5f))
                }
                Glyph.PROGRAM -> {
                    path(3f to 4f, 21f to 4f, 21f to 16f, 3f to 16f, close = true)
                    line(8f, 20f, 16f, 20f); line(12f, 16f, 12f, 20f)
                }
                Glyph.INFO -> {
                    drawCircle(color, 9f, Offset(12f, 12f), style = Stroke(1.7f))
                    line(12f, 11f, 12f, 17f)
                    drawCircle(color, 1f, Offset(12f, 7f))
                }
            }
        }
    }
}

private fun Modifier.semanticsCompat(description: String): Modifier =
    this.then(Modifier.semantics { contentDescription = description })
