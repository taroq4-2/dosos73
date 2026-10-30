package com.os73.dosos73

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.widget.VideoView
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class BatchEntry(
    val raw: String,
    val url: String?,
    val selected: Boolean,
    val analysis: Analysis? = null,
    val error: Int? = null,
    val choice: Choice? = null,
    val destination: String? = null,
    val destinationOverride: Boolean = false,
)

internal object BatchInput {
    fun parse(text: String): List<String> =
        text
            .take(32768)
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { it.trim().take(8192) }
            .distinct()
            .take(20)
}

/**
 * Diagnostic exports deliberately never accept task URLs, titles, filenames or Throwable objects.
 */
internal object SafeReport {
    fun text(code: FailureCode?): String =
        "dosos73 ${BuildConfig.VERSION_NAME}\nAndroid API ${Build.VERSION.SDK_INT}\nCode: ${code?.name ?: "NONE"}\n" +
            "Contains no source URL, filename, destination, credentials or native logs."
}

internal object LibraryPreferences {
    private fun prefs(context: Context) =
        context.getSharedPreferences("library_controls", Context.MODE_PRIVATE)

    fun collection(context: Context, uri: String): String =
        prefs(context).getString("collection:$uri", "").orEmpty()

    fun setCollection(context: Context, uri: String, value: String) {
        prefs(context).edit().putString("collection:$uri", value.trim().take(40)).apply()
    }

    fun collections(context: Context): Map<String, String> = prefs(context).all.entries
        .mapNotNull { (key, value) ->
            if (key.startsWith("collection:") && value is String && value.isNotBlank()) key.removePrefix("collection:") to value else null
        }.toMap()

    fun migrateUri(context: Context, previous: String, next: String) {
        if (previous == next) return
        val value = collection(context, previous)
        check(prefs(context).edit().remove("collection:$previous").apply {
            if (value.isNotEmpty()) putString("collection:$next", value)
        }.commit())
    }

    fun historyDays(context: Context) = prefs(context).getInt("history_days", 0)

    fun setHistoryDays(context: Context, value: Int) {
        prefs(context).edit().putInt("history_days", value).apply()
    }

    fun visibleHistory(context: Context, file: SavedFile): Boolean {
        val days = historyDays(context)
        return days == 0 || file.date >= System.currentTimeMillis() - days * 86400000L
    }
}

@Composable
internal fun BatchScreen(
    rows: List<BatchEntry>,
    busy: Boolean,
    onInput: (String) -> Unit,
    onSelect: (Int, Boolean) -> Unit,
    onAnalyze: () -> Unit,
    onCancel: () -> Unit,
    onChoice: (Int, Choice) -> Unit,
    onDestination: (Int, Boolean) -> Unit,
    onQueue: () -> Unit,
    saving: Boolean = false,
    storageError: Boolean = false,
    onRetryStorage: () -> Unit = {},
) {
    val blocked = busy || saving || storageError
    var input by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.batch_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.batch_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            input,
            { input = it.take(32768) },
            Modifier.fillMaxWidth(),
            enabled = !blocked,
            maxLines = 4,
            label = { Text(stringResource(R.string.batch_links)) },
        )
        Row {
            TextButton({ onInput(input) }, enabled = !blocked && input.isNotBlank()) {
                Text(stringResource(R.string.batch_review))
            }
            TextButton(onAnalyze, enabled = !blocked && rows.any { it.selected && it.url != null }) {
                Text(stringResource(R.string.analyze))
            }
            if (busy) TextButton(onCancel) { Text(stringResource(R.string.cancel)) }
        }
        if (busy || saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (storageError) {
            Text(stringResource(R.string.batch_storage_error), color = MaterialTheme.colorScheme.error)
            TextButton(onRetryStorage, enabled = !saving) { Text(stringResource(R.string.retry)) }
        }
        LazyColumn(Modifier.weight(1f).testTag("batch_rows"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(rows) { index, row ->
                Panel {
                    Row {
                        Checkbox(
                            row.selected,
                            { onSelect(index, it) },
                            enabled = !blocked && row.url != null,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(row.analysis?.title ?: safeSourceLabel(row.url), maxLines = 2)
                            row.error?.let {
                                Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                            }
                            if (row.analysis != null) {
                                Row {
                                    TextButton({ onDestination(index, false) }, enabled = !blocked) {
                                        Text(stringResource(if (row.destination != null) R.string.custom_folder else R.string.destination))
                                    }
                                    TextButton({ onDestination(index, true) }, enabled = !blocked) {
                                        Text(stringResource(R.string.default_folder))
                                    }
                                }
                                Text(stringResource(R.string.batch_ready), color = Neon.Cyan)
                                val selected = row.choice ?: row.analysis.choices.firstOrNull()
                                ChoiceMenu(
                                    stringResource(R.string.format),
                                    selected?.let { "${it.extension.uppercase()} \u00b7 ${it.quality}" }.orEmpty(),
                                ) { close ->
                                    row.analysis.choices.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text("${option.extension.uppercase()} \u00b7 ${option.quality}") },
                                            onClick = { onChoice(index, option); close() },
                                            enabled = !blocked,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        GradientButton(
            stringResource(R.string.batch_queue),
            onQueue,
            Modifier.fillMaxWidth(),
            !blocked && rows.any { it.selected && it.analysis != null },
        )
    }
}

internal fun safeSourceLabel(url: String?): String =
    url?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: "—"

@Composable
internal fun OfflinePreview(item: SavedFile, onDismiss: () -> Unit, onExternal: () -> Unit) {
    val context = LocalContext.current
    val uri = remember(item.uri) { Uri.parse(item.uri) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var bitmap by remember(item.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(item.uri) { mutableStateOf(uri.scheme != "content") }
    var player by remember { mutableStateOf<VideoView?>(null) }
    LaunchedEffect(item.uri) {
        if (!failed && item.mime.startsWith("image/")) {
            bitmap =
                withContext(Dispatchers.IO) {
                    runCatching {
                            ImageDecoder.decodeBitmap(
                                ImageDecoder.createSource(context.contentResolver, uri)
                            ) { decoder, info, _ ->
                                val scale =
                                    minOf(1f, 1024f / maxOf(info.size.width, info.size.height))
                                decoder.setTargetSize(
                                    maxOf(1, (info.size.width * scale).toInt()),
                                    maxOf(1, (info.size.height * scale).toInt()),
                                )
                                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            }
                        }
                        .getOrNull()
                }
            failed = bitmap == null
        } else if (!item.mime.startsWith("video/") && !item.mime.startsWith("audio/")) failed = true
    }
    DisposableEffect(lifecycle, item.uri) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE ||
                event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                runCatching { player?.pause() }
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            runCatching { player?.stopPlayback() }
            player = null
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name, maxLines = 2) },
        text = {
            Column {
                Text(
                    stringResource(R.string.preview_local),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (failed) Text(stringResource(R.string.preview_unsupported))
                else if (item.mime.startsWith("image/")) {
                    bitmap?.let {
                        Image(
                            it.asImageBitmap(),
                            null,
                            Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        )
                    } ?: CircularProgressIndicator()
                } else
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                player = this
                                setMediaController(
                                    android.widget.MediaController(ctx).also {
                                        it.setAnchorView(this)
                                    }
                                )
                                setOnErrorListener { _, _, _ ->
                                    failed = true
                                    true
                                }
                                setOnPreparedListener {
                                    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) start()
                                }
                                try {
                                    setVideoURI(uri)
                                } catch (_: Exception) {
                                    failed = true
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                    )
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = { TextButton(onExternal) { Text(stringResource(R.string.open_external)) } },
    )
}

@Composable
internal fun PrivacyScreen(onHistoryCleared: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var retention by remember { mutableIntStateOf(LibraryPreferences.historyDays(context)) }
    var confirm by remember { mutableStateOf(false) }
    var timeline by remember { mutableStateOf(false) }
    var status by remember { mutableIntStateOf(0) }
    Page {
        Text(
            stringResource(R.string.privacy_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(stringResource(R.string.privacy_permissions))
        Panel {
            Text(stringResource(R.string.history_retention))
            listOf(
                    0 to R.string.retention_all,
                    7 to R.string.retention_week,
                    30 to R.string.retention_month,
                )
                .forEach { (days, label) ->
                    Row {
                        RadioButton(
                            retention == days,
                            {
                                retention = days
                                LibraryPreferences.setHistoryDays(context, days)
                                onHistoryCleared()
                            },
                        )
                        Text(stringResource(label))
                    }
                }
            Text(
                stringResource(R.string.retention_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton({ confirm = true }) { Text(stringResource(R.string.clear_history)) }
        }
        OutlinedButton({ timeline = true }) { Text(stringResource(R.string.timeline_title)) }
        Panel {
            Text(stringResource(R.string.safe_cleanup_hint))
            OutlinedButton({
                scope.launch {
                    withContext(Dispatchers.IO) {
                        LibraryRepository.clearThumbnailCache()
                        // Thumbnails only. Transfer/analysis/native/resume folders are never
                        // touched.
                        context.cacheDir
                            .resolve("preview-thumbnails")
                            .takeIf { it.isDirectory }
                            ?.deleteRecursively()
                    }
                    status = R.string.action_done
                }
            }) {
                Text(stringResource(R.string.safe_cleanup))
            }
        }
        if (status != 0) Text(stringResource(status))
    }
    if (timeline) TaskTimelineDialog { timeline = false }
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.clear_history)) },
            text = { Text(stringResource(R.string.clear_history_confirm)) },
            confirmButton = {
                TextButton({
                    confirm = false
                    scope.launch {
                        try {
                            LibraryRepository.clearHistory(context)
                            FailureJournal.clear(context)
                            TaskTimeline.clear(context)
                            onHistoryCleared()
                            status = R.string.action_done
                        } catch (_: Exception) {
                            status = R.string.error_storage
                        }
                    }
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton({ confirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
}

internal fun copyDiagnostics(context: Context, code: FailureCode?) {
    context
        .getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("dosos73 diagnostics", SafeReport.text(code)))
}

internal fun shareDiagnostics(context: Context, code: FailureCode?) {
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, SafeReport.text(code)),
            context.getString(R.string.share_diagnostics),
        )
    )
}

@Composable
internal fun NetworkControls() {
    val context = LocalContext.current
    var parallel by remember { mutableStateOf(NetworkSettings.parallelConnections(context)) }
    var cleartext by remember { mutableStateOf(NetworkSettings.allowCleartext(context)) }
    var wifi by remember { mutableStateOf(NetworkSettings.wifiOnly(context)) }
    var unmetered by remember { mutableStateOf(NetworkSettings.unmeteredOnly(context)) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf(false) }
    Panel {
        Text(
            stringResource(R.string.network_controls),
            style = MaterialTheme.typography.titleMedium,
        )
        NetworkToggle(wifi, R.string.wifi_only) { value ->
            scope.launch { try { withContext(Dispatchers.IO) { NetworkSettings.setWifiOnly(context, value) }; wifi = value }
                catch (_: Exception) { error = true } }
        }
        NetworkToggle(unmetered, R.string.unmetered_only) { value ->
            scope.launch { try { withContext(Dispatchers.IO) { NetworkSettings.setUnmeteredOnly(context, value) }; unmetered = value }
                catch (_: Exception) { error = true } }
        }
        Text(stringResource(R.string.network_policy_hint), style = MaterialTheme.typography.bodySmall, color = Neon.Secondary)
        NetworkToggle(parallel, R.string.parallel_connections) { value ->
            scope.launch { try { withContext(Dispatchers.IO) { NetworkSettings.setParallelConnections(context, value) }; parallel = value }
                catch (_: Exception) { error = true } }
        }
        Text(stringResource(R.string.parallel_connections_hint), style = MaterialTheme.typography.bodySmall, color = Neon.Secondary)
        NetworkToggle(cleartext, R.string.allow_cleartext) { value ->
            scope.launch { try { withContext(Dispatchers.IO) { NetworkSettings.setAllowCleartext(context, value) }; cleartext = value }
                catch (_: Exception) { error = true } }
        }
        Text(stringResource(R.string.allow_cleartext_hint), style = MaterialTheme.typography.bodySmall, color = Neon.Secondary)
        if (error) Text(stringResource(R.string.error_storage))
    }
}

@Composable
private fun NetworkToggle(checked: Boolean, label: Int, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .androidxToggleable(checked, onChange), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Text(stringResource(label), style = MaterialTheme.typography.bodyMedium) }
        Switch(checked, onCheckedChange = null)
    }
}

private fun Modifier.androidxToggleable(value: Boolean, onChange: (Boolean) -> Unit): Modifier =
    this.then(Modifier.toggleable(value, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange))

@Composable
internal fun QueueControls(task: DownloadTask) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf(false) }
    fun update(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (_: Exception) {
                error = true
            }
        }
    }
    Column {
        ChoiceMenu(
            stringResource(R.string.priority),
            stringResource(
                when (task.priority) {
                    TaskPriority.HIGH -> R.string.priority_high
                    TaskPriority.NORMAL -> R.string.priority_normal
                    TaskPriority.LOW -> R.string.priority_low
                }
            ),
        ) { close ->
            TaskPriority.values().forEach { priority ->
                DropdownMenuItem(
                    {
                        Text(
                            stringResource(
                                when (priority) {
                                    TaskPriority.HIGH -> R.string.priority_high
                                    TaskPriority.NORMAL -> R.string.priority_normal
                                    TaskPriority.LOW -> R.string.priority_low
                                }
                            )
                        )
                    },
                    {
                        close()
                        update { DownloadQueue.updatePriority(context, task.id, priority) }
                    },
                )
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton({ update { DownloadQueue.move(context, task.id, -1) } }) {
                Text(stringResource(R.string.move_up))
            }
            TextButton({ update { DownloadQueue.move(context, task.id, 1) } }) {
                Text(stringResource(R.string.move_down))
            }
            TextButton({ update { DownloadQueue.setPaused(context, task.id, !task.userPaused) } }) {
                Text(stringResource(if (task.userPaused) R.string.resume else R.string.pause))
            }
        }
        if (error) Text(stringResource(R.string.error_storage))
    }
}

@Composable
internal fun DiagnosticActions(code: FailureCode?) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(R.string.diagnostics_hint), style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton({
                copyDiagnostics(context, code)
                status = true
            }) {
                Text(stringResource(R.string.copy_diagnostics))
            }
            TextButton({ runCatching { shareDiagnostics(context, code) } }) {
                Text(stringResource(R.string.share_diagnostics))
            }
        }
        if (status) Text(stringResource(R.string.action_done))
    }
}

@Composable
internal fun TaskTimelineDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    var events by remember { mutableStateOf<List<TimelineEvent>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            val loaded = TaskTimeline.read(context)
            withContext(Dispatchers.Main.immediate) {
                androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { events = loaded }
            }
        }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) {
            withContext(Dispatchers.Main.immediate) {
                androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { failed = true }
            }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.timeline_title)) },
        text = {
            Column {
                Text(stringResource(R.string.timeline_privacy), style = MaterialTheme.typography.bodySmall)
                when {
                    failed -> Text(stringResource(R.string.error_storage))
                    events == null -> CircularProgressIndicator(Modifier.testTag("timeline_loading"))
                    events!!.isEmpty() -> Text(stringResource(R.string.timeline_empty))
                    else -> LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        events!!.groupBy { it.taskId }.values.sortedByDescending { it.last().at }.forEachIndexed { index, rows ->
                            item {
                                Text(stringResource(R.string.timeline_task, index + 1), style = MaterialTheme.typography.titleSmall)
                                rows.forEach { event ->
                                    val stage = when(event.stage) {
                                        Stage.INITIALIZE -> R.string.timeline_initialize
                                        Stage.EXTRACT -> R.string.analyze
                                        Stage.TRANSFER -> R.string.timeline_transfer
                                        Stage.MERGE -> R.string.timeline_merge
                                        Stage.VERIFY -> R.string.timeline_verify
                                        Stage.PUBLISH -> R.string.saving
                                    }
                                    val result = when(event.result) {
                                        TimelineResult.STARTED -> R.string.timeline_started
                                        TimelineResult.COMPLETED -> R.string.timeline_completed
                                        TimelineResult.RECOVERED -> R.string.timeline_recovered
                                        TimelineResult.INTERRUPTED -> R.string.timeline_interrupted
                                        TimelineResult.FAILED -> R.string.failed
                                    }
                                    Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, locale)
                                        .format(java.util.Date(event.at)) + " · " + stringResource(stage) + " · " + stringResource(result),
                                        style = MaterialTheme.typography.bodySmall)
                                    event.code?.let { Text(stringResource(PipelineException(event.stage, it).messageResource()),
                                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } })
}
