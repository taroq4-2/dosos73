package com.os73.dosos73

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp

@Composable
internal fun HomeScreen(
    screen: ScreenState,
    customFolder: Boolean,
    onEdit: (String) -> Unit,
    onSelect: (Choice) -> Unit,
    onAnalyze: () -> Unit,
    onCancelAnalysis: () -> Unit,
    onPaste: () -> Unit,
    onSettings: () -> Unit,
    onFiles: () -> Unit,
    onAudio: () -> Unit,
    onFolder: () -> Unit,
    onDefaultFolder: () -> Unit,
    onStart: () -> Unit,
    onOffline: () -> Unit,
    onPreset: (Choice, String?) -> Unit,
    startEnabled: Boolean,
    onCandidate: (LinkCandidate) -> Unit = {},
    preferred: Kind = Kind.VIDEO,
    onVideo: () -> Unit = {},
    onDownloads: () -> Unit = {},
    onSupported: () -> Unit = {},
    onTools: () -> Unit = onOffline,
) {
    val palette = LocalPalette.current
    val hasAnalysis = screen.analysis != null
    val canAnalyze = screen.url.isNotBlank() && !screen.busy
    val urlLabel = stringResource(R.string.url)
    Box(Modifier.fillMaxSize()) {
        HeaderWave(Modifier.fillMaxWidth().height(190.dp))
        Page {
            HomeHeader(onSettings)
            // ---- New download -------------------------------------------------------------
            SurfaceCard(padding = 18.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(Glyph.DOWNLOAD, palette.accent, 40.dp)
                    Text(stringResource(R.string.new_download), style = MaterialTheme.typography.headlineSmall)
                }
                Text(stringResource(R.string.new_download_hint), style = MaterialTheme.typography.bodyMedium,
                    color = palette.secondary)
                OutlinedTextField(
                    value = screen.url,
                    onValueChange = onEdit,
                    modifier = Modifier.fillMaxWidth().testTag("url").semantics { contentDescription = urlLabel },
                    enabled = !screen.busy,
                    placeholder = { Text(stringResource(R.string.url_placeholder), color = palette.muted) },
                    leadingIcon = { Symbol(Glyph.LINK, modifier = Modifier.size(22.dp), color = palette.muted) },
                    trailingIcon = {
                        if (screen.url.isNotEmpty() && !screen.busy)
                            IconAction(Glyph.CLOSE, stringResource(R.string.clear), { onEdit("") })
                        else
                            FilledTonalButton(onPaste, Modifier.padding(end = 6.dp).heightIn(min = 48.dp).testTag("paste"),
                                enabled = !screen.busy, shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = palette.raised,
                                    contentColor = palette.text)) {
                                Symbol(Glyph.CLIPBOARD, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.paste))
                            }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (canAnalyze) onAnalyze() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accent, unfocusedBorderColor = palette.line,
                        focusedContainerColor = palette.background, unfocusedContainerColor = palette.background,
                    ),
                )
                if (screen.busy) {
                    ProgressTrack(null)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        screen.stage.label()?.let {
                            Text(stringResource(it), Modifier.weight(1f).testTag("analysis_stage")
                                .semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.bodySmall, color = palette.secondary)
                        } ?: Spacer(Modifier.weight(1f))
                        TextButton(onCancelAnalysis, Modifier.heightIn(min = 48.dp).testTag("cancel_analysis")) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                } else if (!hasAnalysis) {
                    if (screen.stage == AnalysisStage.CANCELED)
                        Text(stringResource(R.string.analysis_canceled), style = MaterialTheme.typography.bodySmall,
                            color = palette.secondary)
                    GradientButton(stringResource(R.string.analyze_link), onAnalyze,
                        Modifier.fillMaxWidth().testTag("analyze"), canAnalyze && startEnabled, Glyph.DOWNLOAD)
                }
                screen.error?.let {
                    Text(stringResource(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("analysis_error"),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    screen.retryAfter?.let { seconds ->
                        val count = seconds.coerceAtMost(86_400).toInt()
                        Text(pluralStringResource(R.plurals.retry_after, count, count),
                            style = MaterialTheme.typography.bodySmall, color = palette.secondary)
                    }
                }
            }
            // ---- Result ------------------------------------------------------------------
            if (screen.candidates.isNotEmpty() && !screen.busy) CandidateList(screen.candidates, onCandidate)
            if (hasAnalysis && screen.analysis?.direct == true) FileFacts(screen)
            AnalysisOptions(screen, customFolder, onSelect, onFolder, onDefaultFolder, onPreset)
            if (hasAnalysis) {
                GradientButton(stringResource(R.string.start_short), onStart,
                    Modifier.fillMaxWidth().testTag("download"), !screen.busy && startEnabled, Glyph.DOWNLOAD)
            }
            // ---- Shortcuts ---------------------------------------------------------------
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShortcutTile(Glyph.VIDEO, stringResource(R.string.video), palette.document, onVideo,
                    Modifier.weight(1f).testTag("shortcut_video"), selected = preferred == Kind.VIDEO,
                    description = stringResource(R.string.shortcut_video_hint))
                ShortcutTile(Glyph.AUDIO, stringResource(R.string.audio), palette.audio, onAudio,
                    Modifier.weight(1f).testTag("shortcut_audio"), selected = preferred == Kind.AUDIO,
                    description = stringResource(R.string.shortcut_audio_hint))
                ShortcutTile(Glyph.FILE, stringResource(R.string.shortcut_files), palette.video, onFiles,
                    Modifier.weight(1f).testTag("shortcut_files"))
                ShortcutTile(Glyph.DOWNLOAD, stringResource(R.string.downloads), palette.accent, onDownloads,
                    Modifier.weight(1f).testTag("shortcut_downloads"))
            }
            // ---- Navigation rows -----------------------------------------------------------
            SurfaceCard(padding = 8.dp) {
                ListRow(Glyph.SETTINGS, stringResource(R.string.row_quick_settings),
                    stringResource(R.string.row_quick_settings_hint), onSettings, Modifier.testTag("settings"))
                HorizontalDivider(color = palette.line.copy(alpha = 0.5f))
                ListRow(Glyph.LINK, stringResource(R.string.row_supported), stringResource(R.string.row_supported_hint),
                    onSupported, Modifier.testTag("supported"))
                HorizontalDivider(color = palette.line.copy(alpha = 0.5f))
                ListRow(Glyph.LAYERS, stringResource(R.string.row_tools), stringResource(R.string.row_tools_hint),
                    onTools, Modifier.testTag("tools"))
            }
        }
    }
}

@Composable
private fun HomeHeader(onSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Brand(compact = true)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.bodyMedium,
            color = Neon.Secondary, textAlign = TextAlign.Center)
    }
}

/** Soft decorative wave behind the header (pure drawing, no bitmap). */
@Composable
private fun HeaderWave(modifier: Modifier) {
    val palette = LocalPalette.current
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val fill = Path().apply {
            moveTo(0f, h * 0.55f)
            cubicTo(w * 0.3f, h * 0.35f, w * 0.6f, h * 0.95f, w, h * 0.6f)
            lineTo(w, 0f); lineTo(0f, 0f); close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(palette.accent.copy(alpha = if (palette.dark) 0.10f else 0.08f),
            palette.background.copy(alpha = 0f))))
        val line = Path().apply {
            moveTo(0f, h * 0.62f)
            cubicTo(w * 0.3f, h * 0.42f, w * 0.62f, h * 1.0f, w, h * 0.66f)
        }
        drawPath(line, Brush.horizontalGradient(listOf(palette.accent.copy(alpha = 0f), palette.accent.copy(alpha = 0.45f),
            palette.accent.copy(alpha = 0f))), style = Stroke(2.5f))
    }
}

@Composable
private fun CandidateList(candidates: List<LinkCandidate>, onCandidate: (LinkCandidate) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    SurfaceCard {
        Text(stringResource(R.string.candidates_title), style = MaterialTheme.typography.titleMedium)
        candidates.take(30).forEach { candidate ->
            val extension = candidate.name.substringAfterLast('.', "").lowercase()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { onCandidate(candidate) }.padding(6.dp).testTag("candidate"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CategoryBadge(FileTypes.category(extension), extension, 38.dp)
                Text(candidate.name, Modifier.weight(1f), maxLines = 2,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
                candidate.sizeBytes?.let {
                    Text(bytesLabel(it, locale), style = MaterialTheme.typography.labelSmall, color = Neon.Secondary)
                }
            }
        }
    }
}

@Composable
private fun FileFacts(screen: ScreenState) {
    val analysis = screen.analysis ?: return
    val host = remember(analysis.url) { runCatching { java.net.URI(analysis.url).host }.getOrNull().orEmpty() }
    val extension = screen.selected?.extension.orEmpty()
    SurfaceCard(Modifier.testTag("file_facts")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CategoryBadge(screen.category ?: FileTypes.category(extension), extension)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(analysis.title, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                    color = Neon.Text)
                Text(stringResource(R.string.link_source, host), style = MaterialTheme.typography.labelSmall,
                    color = Neon.Secondary)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Symbol(if (screen.verified == true) Glyph.SHIELD else Glyph.INFO, modifier = Modifier.size(18.dp),
                color = if (screen.verified == true) LocalPalette.current.success else Neon.Muted)
            Text(if (screen.verified == true) stringResource(R.string.link_verified, extension.uppercase())
                else stringResource(R.string.link_unverified),
                style = MaterialTheme.typography.labelSmall, color = Neon.Secondary)
        }
        if (screen.category == FileCategory.APP)
            Text(stringResource(R.string.apk_notice), Modifier.testTag("apk_notice"),
                style = MaterialTheme.typography.labelSmall, color = Neon.Secondary)
    }
}
