package com.os73.dosos73

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
) {
    val hasAnalysis = screen.analysis != null
    val canAnalyze = screen.url.isNotBlank() && !screen.busy
    Page {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Brand(compact = true)
            Spacer(Modifier.weight(1f))
            IconAction(Glyph.SETTINGS, stringResource(R.string.settings), onSettings, Modifier.testTag("settings"))
        }
        Panel {
            Text(stringResource(R.string.title), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = screen.url,
                onValueChange = onEdit,
                modifier = Modifier.fillMaxWidth().testTag("url"),
                enabled = !screen.busy,
                label = { Text(stringResource(R.string.url)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (canAnalyze) onAnalyze() }),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onPaste, Modifier.testTag("paste").heightIn(min = 48.dp), enabled = !screen.busy) {
                    Symbol(Glyph.LINK, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.paste))
                }
                Spacer(Modifier.weight(1f))
                if (screen.url.isNotEmpty() && !screen.busy) {
                    TextButton({ onEdit("") }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.clear)) }
                }
            }
            if (screen.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Neon.Cyan, trackColor = Neon.Raised)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    screen.stage.label()?.let {
                        Text(stringResource(it), Modifier.weight(1f).testTag("analysis_stage")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodySmall, color = Neon.Secondary)
                    } ?: Spacer(Modifier.weight(1f))
                    TextButton(onCancelAnalysis, Modifier.heightIn(min = 48.dp).testTag("cancel_analysis")) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            } else if (!hasAnalysis) {
                if (screen.stage == AnalysisStage.CANCELED)
                    Text(stringResource(R.string.analysis_canceled), style = MaterialTheme.typography.bodySmall,
                        color = Neon.Secondary)
                GradientButton(stringResource(R.string.analyze), onAnalyze,
                    Modifier.fillMaxWidth().testTag("analyze"), canAnalyze && startEnabled, Glyph.LINK)
            }
            screen.error?.let {
                Text(stringResource(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("analysis_error"),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                screen.retryAfter?.let { seconds ->
                    val count = seconds.coerceAtMost(86_400).toInt()
                    Text(pluralStringResource(R.plurals.retry_after, count, count),
                        style = MaterialTheme.typography.bodySmall, color = Neon.Secondary)
                }
            }
        }
        if (screen.candidates.isNotEmpty() && !screen.busy) {
            Panel {
                Text(stringResource(R.string.candidates_title), style = MaterialTheme.typography.titleMedium)
                screen.candidates.take(30).forEach { candidate ->
                    TextButton({ onCandidate(candidate) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("candidate")) {
                        Text(candidate.name, Modifier.weight(1f), maxLines = 2,
                            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
                        candidate.sizeBytes?.let {
                            Spacer(Modifier.width(8.dp))
                            Text(bytesLabel(it, androidx.compose.ui.platform.LocalConfiguration.current.locales[0]), style = MaterialTheme.typography.labelSmall,
                                color = Neon.Secondary)
                        }
                    }
                }
            }
        }
        if (hasAnalysis && screen.analysis?.direct == true) {
            val host = remember(screen.analysis.url) {
                runCatching { java.net.URI(screen.analysis.url).host }.getOrNull().orEmpty()
            }
            Column(Modifier.testTag("file_facts"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.link_source, host), style = MaterialTheme.typography.labelSmall,
                    color = Neon.Secondary)
                val extension = screen.selected?.extension.orEmpty().uppercase()
                Text(if (screen.verified == true) stringResource(R.string.link_verified, extension)
                    else stringResource(R.string.link_unverified),
                    style = MaterialTheme.typography.labelSmall, color = Neon.Secondary)
                if (screen.category == FileCategory.APP)
                    Text(stringResource(R.string.apk_notice), Modifier.testTag("apk_notice"),
                        style = MaterialTheme.typography.labelSmall, color = Neon.Secondary)
            }
        }
        AnalysisOptions(screen, customFolder, onSelect, onFolder, onDefaultFolder, onPreset)
        if (hasAnalysis) {
            GradientButton(stringResource(R.string.start_short), onStart,
                Modifier.fillMaxWidth().testTag("download"), !screen.busy && startEnabled, Glyph.DOWNLOAD)
        }
        Text(stringResource(R.string.quick_tools), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolTile(R.string.download_audio, R.string.from_media, Glyph.AUDIO, Neon.Cyan, onAudio, Modifier.weight(1f))
            ToolTile(R.string.extract_audio, R.string.tools_local_hint, Glyph.VIDEO, Neon.Purple, onOffline, Modifier.weight(1f))
        }
        ToolTile(R.string.saved_downloads, R.string.manage_files, Glyph.FOLDER, Neon.Blue, onFiles, Modifier.fillMaxWidth())
        Text(stringResource(R.string.notice), style = MaterialTheme.typography.labelSmall, color = Neon.Secondary)
    }
}
