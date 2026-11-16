package com.os73.dosos73

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
                TextButton(onCancelAnalysis) { Text(stringResource(R.string.cancel)) }
            } else if (!hasAnalysis) {
                GradientButton(stringResource(R.string.analyze), onAnalyze,
                    Modifier.fillMaxWidth().testTag("analyze"), canAnalyze && startEnabled, Glyph.LINK)
            }
            screen.error?.let {
                Text(stringResource(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
