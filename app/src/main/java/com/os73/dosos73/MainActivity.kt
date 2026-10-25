package com.os73.dosos73

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) receive(intent)
        setContent { AppScreen() }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receive(intent) }
    private fun receive(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let(model::receive)
            intent.removeExtra(Intent.EXTRA_TEXT)
        }
    }
    @Composable private fun AppScreen() {
        val screen by model.state.collectAsStateWithLifecycle()
        val transfer by Transfers.state.collectAsStateWithLifecycle()
        val preferences = remember { getSharedPreferences("destination", MODE_PRIVATE) }
        var tree by remember { mutableStateOf(preferences.getString("tree", null)?.let(Uri::parse)) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                tree?.takeIf { it != uri }?.let { old -> runCatching {
                    contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } }
                tree = uri; preferences.edit().putString("tree", uri.toString()).apply()
            } catch (_: SecurityException) { Toast.makeText(this, R.string.error_storage, Toast.LENGTH_LONG).show() }
        }
        fun start() {
            val analysis = screen.analysis ?: return
            val choice = screen.selected ?: return
            if (Transfers.reserve(DownloadTask(analysis, choice, tree))) try {
                ContextCompat.startForegroundService(this, Intent(this, DownloadService::class.java))
            } catch (_: Exception) { Transfers.failStart() }
        }
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
        val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF8DB9FF))
            else lightColorScheme(primary = Color(0xFF126BEA), background = Color(0xFFF7F9FC), surface = Color.White,
                onBackground = Color(0xFF14243A), onSurface = Color(0xFF14243A))
        MaterialTheme(colorScheme = colors) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
                    .padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Image(painterResource(R.drawable.brand), null, Modifier.size(56.dp))
                        Column {
                            Text(stringResource(R.string.title), style = MaterialTheme.typography.headlineSmall)
                            Text("dosos73", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Text(stringResource(R.string.subtitle), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(screen.url, model::edit, Modifier.fillMaxWidth(), enabled = !screen.busy && !transfer.active,
                        label = { Text(stringResource(R.string.url)) }, maxLines = 3)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val clipboard = getSystemService(ClipboardManager::class.java)
                            clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()?.let(model::receive)
                        }, enabled = !screen.busy && !transfer.active) { Text(stringResource(R.string.paste)) }
                        TextButton(onClick = { model.edit("") }, enabled = !screen.busy && !transfer.active) { Text(stringResource(R.string.clear)) }
                        Button(onClick = model::analyze, enabled = screen.url.isNotBlank() && !screen.busy && !transfer.active) {
                            Text(stringResource(R.string.analyze))
                        }
                    }
                    if (screen.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.analyzing))
                        TextButton(model::cancelAnalysis) { Text(stringResource(R.string.cancel)) }
                    }
                    screen.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    screen.analysis?.let { analysis ->
                        Text(analysis.title, style = MaterialTheme.typography.titleMedium, maxLines = 3)
                        val choices = analysis.choices
                        val selected = screen.selected ?: choices.first()
                        Selection(stringResource(R.string.type), kindName(selected.kind), !transfer.active) { close ->
                            choices.map { it.kind }.distinct().forEach { kind ->
                                DropdownMenuItem(text = { Text(kindName(kind)) }, onClick = { model.select(choices.first { it.kind == kind }); close() })
                            }
                        }
                        Selection(stringResource(R.string.quality), if (analysis.direct) stringResource(R.string.original) else selected.quality, !transfer.active) { close ->
                            choices.filter { it.kind == selected.kind }.map { it.quality }.distinct().forEach { quality ->
                                DropdownMenuItem(text = { Text(if (analysis.direct) stringResource(R.string.original) else quality) },
                                    onClick = { model.select(choices.first { it.kind == selected.kind && it.quality == quality }); close() })
                            }
                        }
                        Selection(stringResource(R.string.format), selected.extension.uppercase(), !transfer.active) { close ->
                            choices.filter { it.kind == selected.kind && it.quality == selected.quality }.forEach { choice ->
                                DropdownMenuItem(text = { Text(choice.extension.uppercase()) }, onClick = { model.select(choice); close() })
                            }
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.destination), style = MaterialTheme.typography.labelLarge)
                            Text(if (tree == null) "Downloads / dosos73" else stringResource(R.string.custom_folder))
                            Row {
                                TextButton({ picker.launch(tree) }, enabled = !transfer.active) { Text(stringResource(R.string.change)) }
                                if (tree != null) TextButton({ tree = null; preferences.edit().remove("tree").apply() }, enabled = !transfer.active) {
                                    Text(stringResource(R.string.default_folder))
                                }
                            }
                        }
                    }
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else start()
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        enabled = screen.selected != null && !screen.busy && !transfer.active) { Text(stringResource(R.string.start)) }
                    if (transfer.phase != Phase.IDLE) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(stringResource(phaseText(transfer.phase)), style = MaterialTheme.typography.titleMedium)
                                if (transfer.active) {
                                    if (transfer.percent == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    else LinearProgressIndicator(progress = { transfer.percent!! }, modifier = Modifier.fillMaxWidth())
                                    transfer.percent?.let { Text("${(it * 100).toInt()}%") }
                                    if (transfer.bytes > 0) Text("${transfer.bytes / 1024} KB" + (transfer.total?.let { " / ${it / 1024} KB" } ?: ""))
                                    transfer.bytesPerSecond?.takeIf { it > 0 }?.let { Text("${it / 1024} KB/s") }
                                    TextButton({ startService(Intent(this@MainActivity, DownloadService::class.java).setAction("cancel")) }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                }
                                transfer.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                                transfer.uri?.let { uri -> Button({
                                    try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, transfer.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                                    catch (_: android.content.ActivityNotFoundException) { Toast.makeText(this@MainActivity, R.string.error_open, Toast.LENGTH_LONG).show() }
                                }) { Text(stringResource(R.string.open)) } }
                            }
                        }
                    }
                    Text(stringResource(R.string.notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("0.1.0-dev", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable private fun kindName(kind: Kind): String = stringResource(when(kind) { Kind.VIDEO -> R.string.video; Kind.AUDIO -> R.string.audio; Kind.FILE -> R.string.file })
@Composable private fun Selection(label: String, value: String, enabled: Boolean, items: @Composable (() -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth(), enabled = enabled) { Text("$label: $value ▾") }
        DropdownMenu(expanded, { expanded = false }) { items { expanded = false } }
    }
}
