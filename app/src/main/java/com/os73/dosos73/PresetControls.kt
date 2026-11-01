package com.os73.dosos73

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PresetControls(analysis: Analysis, selected: Choice?, onApply: (Choice, String?) -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { DownloadPresets(context) }
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<DownloadPreset>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember(analysis.url) { mutableStateOf<Int?>(null) }
    var dialog by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }

    fun load() {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { store.read() } }
            result.onSuccess { rows = it; loaded = true; notice = null }
                .onFailure { loaded = false; notice = R.string.preset_storage_error }
            busy = false
        }
    }
    LaunchedEffect(store) { load() }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.preset_title), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { name = ""; notice = null; dialog = "save" },
                enabled = loaded && !busy && selected != null, modifier = Modifier.testTag("preset_save")) {
                Text(stringResource(R.string.preset_save))
            }
            OutlinedButton(onClick = { notice = null; dialog = "list" }, enabled = loaded && !busy,
                modifier = Modifier.testTag("preset_list")) {
                Text(stringResource(R.string.preset_manage))
            }
            if (!loaded && !busy) TextButton(onClick = { load() }) { Text(stringResource(R.string.preset_retry)) }
        }
        notice?.takeIf { dialog.isEmpty() }?.let { message ->
            Text(stringResource(message), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall)
        }
    }

    if (dialog == "save") AlertDialog(
        onDismissRequest = { if (!busy) dialog = "" },
        title = { Text(stringResource(R.string.preset_save)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(DownloadPresets.MAX_NAME) },
                    label = { Text(stringResource(R.string.preset_name)) },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("preset_name"))
                Text(stringResource(R.string.preset_save_hint), style = MaterialTheme.typography.bodySmall)
                notice?.let { Text(stringResource(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && selected != null && DownloadPresets.validName(name.trim()), onClick = {
                val choice = selected ?: return@TextButton
                if (rows.size >= DownloadPresets.MAX_PRESETS && rows.none { it.name.equals(name.trim(), true) }) {
                    notice = R.string.preset_limit
                } else {
                    val savedName = name
                    // Read at the action, so a folder changed since composition is captured.
                    val tree = context.getSharedPreferences("destination", Context.MODE_PRIVATE).getString("tree", null)
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { store.save(savedName, choice, tree) } }
                        result.onSuccess { rows = it; dialog = ""; notice = R.string.preset_saved }
                            .onFailure { notice = R.string.preset_storage_error }
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.preset_confirm_save)) }
        },
        dismissButton = { TextButton(onClick = { dialog = "" }, enabled = !busy) { Text(stringResource(R.string.preset_cancel)) } },
    )

    if (dialog == "list") AlertDialog(
        onDismissRequest = { if (!busy) dialog = "" },
        title = { Text(stringResource(R.string.preset_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (rows.isEmpty()) Text(stringResource(R.string.preset_empty))
                notice?.let { Text(stringResource(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                rows.forEach { preset ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(preset.name, style = MaterialTheme.typography.titleSmall)
                        val kind = stringResource(when (preset.kind) {
                            Kind.VIDEO -> R.string.preset_video
                            Kind.AUDIO -> R.string.preset_audio
                            Kind.FILE -> R.string.preset_file
                        })
                        val quality = preset.expectedHeight?.let { "${it}p" } ?: preset.quality?.let {
                            if (it == "Original") stringResource(R.string.preset_original) else it
                        }.orEmpty()
                        Text(stringResource(R.string.preset_summary, kind, preset.extension.uppercase(), quality),
                            style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(if (preset.tree == null) R.string.preset_default_folder else R.string.preset_custom_folder),
                            style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = !busy, onClick = {
                                val choice = preset.match(analysis.choices)
                                if (choice == null) notice = R.string.preset_unavailable
                                else { dialog = ""; notice = null; onApply(choice, preset.tree) }
                            }) { Text(stringResource(R.string.preset_apply)) }
                            TextButton(enabled = !busy, onClick = {
                                busy = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { runCatching { store.delete(preset.id) } }
                                    result.onSuccess { rows = it; notice = R.string.preset_deleted }
                                        .onFailure { notice = R.string.preset_storage_error }
                                    busy = false
                                }
                            }) { Text(stringResource(R.string.preset_delete)) }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { dialog = "" }, enabled = !busy) { Text(stringResource(R.string.preset_close)) } },
    )
}
