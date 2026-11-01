package com.os73.dosos73

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun OfflineAudioScreen(input: LocalAudioInput?, loading: Boolean, error: Int?, customFolder: Boolean,
    onPick: () -> Unit, onFolder: () -> Unit, onDefaultFolder: () -> Unit, onStart: () -> Unit) {
    Page {
        Brand(true)
        Text(stringResource(R.string.offline_convert), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.offline_convert_description), color = Neon.Secondary)
        OutlinedButton(onPick, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("offline_pick"), enabled = !loading) {
            Symbol(Glyph.VIDEO); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.offline_pick_video))
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        input?.let {
            Panel {
                Text(it.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("offline_input"))
                val locale = LocalConfiguration.current.locales[0]
                Text(stringResource(R.string.offline_input_details,
                    it.bytes?.let { bytesLabel(it, locale) } ?: stringResource(R.string.unknown_size),
                    if (it.durationMs > 0) java.text.NumberFormat.getNumberInstance(locale).format(it.durationMs / 1000) + " s"
                    else stringResource(R.string.tools_unknown_duration)), color = Neon.Secondary)
                Text("MP3 V0", color = Neon.Cyan)
            }
        }
        TextButton(onFolder) {
            Symbol(Glyph.FOLDER); Spacer(Modifier.width(8.dp))
            Text(stringResource(if (customFolder) R.string.custom_folder else R.string.default_folder))
        }
        if (customFolder) TextButton(onDefaultFolder) { Text(stringResource(R.string.default_folder)) }
        GradientButton(stringResource(R.string.offline_convert), onStart,
            Modifier.fillMaxWidth().testTag("offline_start"), enabled = input != null && !loading,
            icon = Glyph.AUDIO)
    }
}
