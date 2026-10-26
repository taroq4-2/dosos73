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

internal object Neon {
    val Background = Color(0xFF030817)
    val Surface = Color(0xFF0B1930)
    val Raised = Color(0xFF102440)
    val Cyan = Color(0xFF19D7F5)
    val Blue = Color(0xFF168BFF)
    val Purple = Color(0xFF812BFF)
    val Text = Color(0xFFF5F7FF)
    val Secondary = Color(0xFFA7B6D0)
    val Muted = Color(0xFF7184A5)
    val Line = Color(0xFF162D49)
    val Brand = Brush.linearGradient(listOf(Cyan, Blue, Purple))
    val Radius = RoundedCornerShape(20.dp)
}

@Composable
internal fun DososTheme(content: @Composable () -> Unit) {
    val family =
        FontFamily(
            Font(R.font.tajawal_regular, FontWeight.Normal),
            Font(R.font.tajawal_medium, FontWeight.Medium),
            Font(R.font.tajawal_bold, FontWeight.Bold),
        )
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = Neon.Cyan,
                secondary = Neon.Purple,
                background = Neon.Background,
                surface = Neon.Surface,
                onSurface = Neon.Text,
                onBackground = Neon.Text,
                onSurfaceVariant = Neon.Secondary,
                surfaceVariant = Neon.Raised,
                surfaceContainerHighest = Neon.Raised,
                outline = Neon.Muted,
                outlineVariant = Neon.Line,
                error = Color(0xFFFF647C),
            ),
        typography =
            Typography(
                headlineMedium =
                    TextStyle(fontFamily = family, fontSize = 26.sp, fontWeight = FontWeight.Bold),
                headlineSmall =
                    TextStyle(fontFamily = family, fontSize = 22.sp, fontWeight = FontWeight.Bold),
                labelLarge =
                    TextStyle(
                        fontFamily = family,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                titleLarge =
                    TextStyle(fontFamily = family, fontSize = 21.sp, fontWeight = FontWeight.Bold),
                titleMedium =
                    TextStyle(
                        fontFamily = family,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                titleSmall =
                    TextStyle(
                        fontFamily = family,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                bodyLarge = TextStyle(fontFamily = family, fontSize = 16.sp),
                bodyMedium = TextStyle(fontFamily = family, fontSize = 14.sp),
                bodySmall = TextStyle(fontFamily = family, fontSize = 12.sp),
                labelSmall = TextStyle(fontFamily = family, fontSize = 11.sp),
                labelMedium =
                    TextStyle(fontFamily = family, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            ),
        content = content,
    )
}

/** Share one immutable decoded artwork between headers and the home wordmark. */
internal object BrandArtwork {
    @Volatile private var cached: ImageBitmap? = null

    fun get(resources: android.content.res.Resources): ImageBitmap =
        cached
            ?: synchronized(this) {
                cached
                    ?: ImageBitmap.imageResource(resources, R.drawable.brand_neon).also {
                        cached = it
                    }
            }
}

/** Render the actual supplied logo asset through a tight source viewport, without redrawing it. */
@Composable
internal fun BrandMark(modifier: Modifier = Modifier) {
    val art = BrandArtwork.get(androidx.compose.ui.platform.LocalContext.current.resources)
    Canvas(modifier) {
        drawImage(
            art,
            srcOffset = androidx.compose.ui.unit.IntOffset(230, 176),
            srcSize = androidx.compose.ui.unit.IntSize(708, 487),
            dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
            blendMode = BlendMode.Lighten,
        )
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
}

@Composable
internal fun Symbol(
    kind: Glyph,
    description: String? = null,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
) {
    Canvas(
        modifier
            .size(24.dp)
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
