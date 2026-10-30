package com.os73.dosos73

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreen(customFolder: Boolean, onLanguage: (String) -> Unit,
    onFolder: () -> Unit, onDefaultFolder: () -> Unit,
    onPrivacy: () -> Unit, onBatch: () -> Unit, onAbout: () -> Unit) {
    Page {
        Brand(true)
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium)
        Panel {
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ onLanguage("ar") }) { Text(stringResource(R.string.language_ar)) }
                TextButton({ onLanguage("en") }) { Text(stringResource(R.string.language_en)) }
            }
        }
        Panel {
            Text(stringResource(R.string.destination), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (customFolder) R.string.custom_folder else R.string.default_folder), color = Neon.Secondary)
            OutlinedButton(onFolder, Modifier.fillMaxWidth()) { Text(stringResource(R.string.change)) }
            if (customFolder) TextButton(onDefaultFolder) { Text(stringResource(R.string.default_folder)) }
        }
        NetworkControls()
        OutlinedButton(onPrivacy, Modifier.fillMaxWidth()) { Text(stringResource(R.string.privacy_title)) }
        OutlinedButton(onBatch, Modifier.fillMaxWidth()) { Text(stringResource(R.string.batch_title)) }
        Panel {
            Text(stringResource(R.string.download_behavior), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.pause_explanation), color = Neon.Secondary)
        }
        TextButton(onAbout, Modifier.testTag("about")) { Text(stringResource(R.string.about)) }
        Text(BuildConfig.VERSION_NAME, color = Neon.Muted)
    }
}

@Composable
internal fun MoreScreen(onNavigate: (String) -> Unit) {
    Page {
        Brand()
        Text(stringResource(R.string.more), style = MaterialTheme.typography.headlineMedium)
        listOf(Triple("settings", R.string.settings, Glyph.SETTINGS),
            Triple("batch", R.string.batch_title, Glyph.LINK),
            Triple("privacy", R.string.privacy_title, Glyph.INFO),
            Triple("about", R.string.about, Glyph.INFO),
            Triple("contact", R.string.contact, Glyph.SHARE)).forEach { (key, label, icon) ->
            OutlinedButton({ onNavigate(key) }, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(key)) {
                Symbol(icon); Spacer(Modifier.width(12.dp)); Text(stringResource(label))
            }
        }
        Text(stringResource(R.string.notice), style = MaterialTheme.typography.bodySmall, color = Neon.Secondary)
        Text(BuildConfig.VERSION_NAME, color = Neon.Muted)
    }
}
