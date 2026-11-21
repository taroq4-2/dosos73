package com.os73.dosos73

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Reusable building blocks of the vNext design (reference: deep navy cards, teal accents). */

internal val CardShape = RoundedCornerShape(18.dp)

/**
 * File names like "app-1.3.2-sources.zip" have no spaces, so text layout breaks them mid-word.
 * Zero-width spaces after separators give natural break points; display only, never stored.
 */
internal fun breakableName(name: String): String =
    buildString(name.length + 8) { for (c in name) { append(c); if (c == '.' || c == '-' || c == '_') append(ZERO_WIDTH_SPACE) } }

private val ZERO_WIDTH_SPACE: Char = 0x200B.toChar()

/** A rounded tile with a glyph, tinted by meaning (file category, setting group...). */
@Composable
internal fun IconTile(glyph: Glyph, tint: Color, size: Dp = 44.dp, filled: Boolean = false) {
    val palette = LocalPalette.current
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f))
            .background(if (filled) tint else tint.copy(alpha = if (palette.dark) 0.18f else 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(glyph, modifier = Modifier.size(size * 0.52f), color = if (filled) Color.White else tint)
    }
}

internal fun FileCategory.glyph(): Glyph = when (this) {
    FileCategory.VIDEO -> Glyph.VIDEO
    FileCategory.AUDIO -> Glyph.AUDIO
    FileCategory.IMAGE -> Glyph.IMAGE
    FileCategory.DOCUMENT -> Glyph.FILE
    FileCategory.ARCHIVE -> Glyph.ARCHIVE
    FileCategory.APP -> Glyph.APP
    FileCategory.PROGRAM -> Glyph.PROGRAM
    FileCategory.OTHER -> Glyph.FILE
}

/** File-type badge: coloured tile; documents/archives show their short extension like the reference (PDF, ZIP). */
@Composable
internal fun CategoryBadge(category: FileCategory, extension: String?, size: Dp = 46.dp) {
    val palette = LocalPalette.current
    val tint = palette.category(category)
    val label = extension?.uppercase()?.takeIf {
        category in setOf(FileCategory.DOCUMENT, FileCategory.ARCHIVE, FileCategory.PROGRAM) && it.length in 2..4
    }
    if (label == null) {
        IconTile(category.glyph(), tint, size, filled = true)
        return
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.26f).sp,
            maxLines = 1, style = MaterialTheme.typography.labelSmall)
    }
}

/** Elevated card container used across screens. */
@Composable
internal fun SurfaceCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(CardShape).background(Neon.Surface)
            .border(1.dp, Neon.Line.copy(alpha = 0.6f), CardShape).padding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** A navigable row: tile, title, subtitle, chevron (mirrors in RTL). Whole row is one 56dp+ target. */
@Composable
internal fun ListRow(
    glyph: Glyph,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    tint: Color = Neon.Cyan,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(glyph, tint, 42.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Neon.Text)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Neon.Secondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Symbol(Glyph.CHEVRON, modifier = Modifier.size(20.dp), color = Neon.Muted)
        }
    }
}

/** Section title inside a settings/list card. */
@Composable
internal fun GroupTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Neon.Text,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
}

/** Square shortcut (reference: Video / Audio / File / Downloads). */
@Composable
internal fun ShortcutTile(
    glyph: Glyph,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    description: String? = null,
) {
    val palette = LocalPalette.current
    Column(
        modifier.heightIn(min = 88.dp).clip(RoundedCornerShape(18.dp))
            .background(if (selected) palette.accentSoft else palette.surface)
            .border(1.dp, if (selected) palette.accent else palette.line.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = "$label. $description" } else Modifier)
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Symbol(glyph, modifier = Modifier.size(28.dp), color = tint)
        FitText(label, MaterialTheme.typography.titleSmall, palette.text)
    }
}

/**
 * One-line label that steps its size down (to 60% at most) only when it would not fit, so a
 * single long word stays readable at large font scales instead of ending in an ellipsis.
 */
@Composable
internal fun FitText(text: String, style: TextStyle, color: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    var scale by remember(text, style) { mutableFloatStateOf(1f) }
    Text(text, modifier, color = color, style = style.copy(fontSize = style.fontSize * scale), maxLines = 1,
        softWrap = false, overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (it.hasVisualOverflow && scale > 0.6f) scale = (scale - 0.1f).coerceAtLeast(0.6f) })
}

/** Progress bar with rounded track; null fraction renders indeterminate. */
@Composable
internal fun ProgressTrack(fraction: Float?, modifier: Modifier = Modifier, color: Color = Neon.Cyan) {
    val track = Neon.Raised
    if (fraction == null)
        LinearProgressIndicator(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = color, trackColor = track)
    else
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = color, trackColor = track, gapSize = 0.dp, drawStopIndicator = {},
        )
}
