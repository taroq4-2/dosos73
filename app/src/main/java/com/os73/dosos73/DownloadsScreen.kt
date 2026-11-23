package com.os73.dosos73

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.launch

private enum class DownloadFilter(val label: Int, val statuses: Set<BoardStatus>) {
    ALL(R.string.filter_all, BoardStatus.entries.toSet()),
    DOWNLOADING(R.string.filter_downloading, setOf(BoardStatus.ACTIVE, BoardStatus.QUEUED)),
    COMPLETED(R.string.filter_completed, setOf(BoardStatus.COMPLETED)),
    STOPPED(R.string.filter_stopped, setOf(BoardStatus.PAUSED, BoardStatus.FAILED)),
}

/**
 * Downloads (reference: middle phone). Rows come from [DownloadBoard], so counts, progress and
 * actions all derive from the real queue/transfer/failure/library state; nothing is simulated.
 */
@Composable
internal fun DownloadsScreen(
    state: TransferState,
    queue: List<DownloadTask>,
    files: List<SavedFile>,
    query: String,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onDetails: () -> Unit,
    onRemove: (DownloadTask) -> Unit,
    onRunQueue: () -> Unit,
    onAction: (SavedFile, String) -> Unit,
    onRetryFailed: (FailedDownload) -> Unit,
    onForgetFailure: (FailedDownload) -> Unit,
) {
    val failures by FailureJournal.failures.collectAsState()
    val leased by DownloadQueue.leased.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf(DownloadFilter.ALL) }
    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }
    var storageError by remember { mutableStateOf(false) }
    val board = remember(state, queue, failures, files, leased) {
        DownloadBoard.build(state, Transfers.currentTask, queue, failures, files, leased)
    }
    val visible = remember(board, query) { board.filter { it.title.contains(query, ignoreCase = true) } }
    val locale = LocalConfiguration.current.locales[0]
    val number = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    fun setQueuedPaused(task: DownloadTask, paused: Boolean) {
        scope.launch {
            runCatching { DownloadQueue.setPaused(context, task.id, paused) }
                .onSuccess { if (!paused) onRunQueue() }
                .onFailure { storageError = true }
        }
    }
    fun move(task: DownloadTask, delta: Int) {
        scope.launch { runCatching { DownloadQueue.move(context, task.id, delta) }.onFailure { storageError = true } }
    }
    val handlers = RowHandlers(
        onCancel = onCancel, onPause = onPause,
        onDetails = onDetails, onRemove = onRemove, onQueuedPaused = ::setQueuedPaused, onMove = ::move,
        onAction = onAction, onRetryFailed = onRetryFailed, onForgetFailure = onForgetFailure,
    )

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DownloadFilter.entries.forEach { option ->
                val count = visible.count { it.status in option.statuses }
                NeonChip(filter == option, { filter = option },
                    label = { Text("${stringResource(option.label)} (${number.format(count)})",
                        style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.heightIn(min = 40.dp).testTag("filter_${option.name.lowercase()}"))
            }
        }
        if (storageError) Text(stringResource(R.string.error_storage), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        val shown = visible.filter { it.status in filter.statuses }
        val sections = listOf(
            // Sections mirror the filter chips, so a count never disagrees with its section.
            "progress" to R.string.section_in_progress to shown.filter {
                it.status in DownloadFilter.DOWNLOADING.statuses },
            "failed" to R.string.filter_stopped to shown.filter { it.status in DownloadFilter.STOPPED.statuses },
            "completed" to R.string.section_completed to shown.filter { it.status == BoardStatus.COMPLETED },
        ).filter { it.second.isNotEmpty() }
        val waiting = queue.isNotEmpty() && !state.active
        LazyColumn(
            // Readable card width on tablets and in landscape, like the Home page.
            Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 840.dp)
                .fillMaxWidth().testTag("downloads_list"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp),
        ) {
            val running = board.any { it.status in DownloadFilter.DOWNLOADING.statuses }
            if (board.isEmpty())
                item { EmptyState(Glyph.DOWNLOAD, R.string.no_active_downloads, R.string.downloads_empty_body) }
            else if (!running && filter in setOf(DownloadFilter.ALL, DownloadFilter.DOWNLOADING))
                // Nothing is transferring: say so explicitly above the history (as in 1.3.2).
                item {
                    SurfaceCard(padding = 12.dp) {
                        ListRow(Glyph.DOWNLOAD, stringResource(R.string.no_active_downloads), null, null, tint = Neon.Muted)
                    }
                }
            else if (shown.isEmpty())
                item {
                    Text(stringResource(R.string.downloads_empty_filter), Modifier.fillMaxWidth().padding(24.dp),
                        color = Neon.Secondary, style = MaterialTheme.typography.bodyMedium)
                }
            if (waiting && filter != DownloadFilter.COMPLETED)
                item {
                    GradientButton(stringResource(R.string.start_queue), onRunQueue, Modifier.fillMaxWidth(),
                        icon = Glyph.PLAY)
                }
            sections.forEach { (head, rows) ->
                val (key, title) = head
                val open = key !in collapsed
                item(key = "section-$key") {
                    val glyph = when (key) { "progress" -> Glyph.DOWNLOAD; "failed" -> Glyph.INFO; else -> Glyph.CHECK }
                    SectionHeader(glyph, stringResource(title), rows.size, open, number) {
                        collapsed = if (open) collapsed + key else collapsed - key
                    }
                }
                if (open) items(rows, key = { it.key }) { item -> BoardRow(item, locale, handlers) }
            }
        }
    }
}

private class RowHandlers(
    val onCancel: () -> Unit,
    val onPause: () -> Unit,
    val onDetails: () -> Unit,
    val onRemove: (DownloadTask) -> Unit,
    val onQueuedPaused: (DownloadTask, Boolean) -> Unit,
    val onMove: (DownloadTask, Int) -> Unit,
    val onAction: (SavedFile, String) -> Unit,
    val onRetryFailed: (FailedDownload) -> Unit,
    val onForgetFailure: (FailedDownload) -> Unit,
)

@Composable
private fun SectionHeader(glyph: Glyph, title: String, count: Int, open: Boolean, number: NumberFormat, onToggle: () -> Unit) {
    val label = stringResource(R.string.section_toggle, title)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = label; stateDescription = if (open) "▲" else "▼" }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Symbol(glyph, modifier = Modifier.size(18.dp),
            color = if (glyph == Glyph.INFO) MaterialTheme.colorScheme.error else Neon.Cyan)
        Text("$title (${number.format(count)})", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Symbol(Glyph.EXPAND, modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f), color = Neon.Muted)
    }
}

@Composable
private fun BoardRow(item: BoardItem, locale: Locale, h: RowHandlers) {
    val palette = LocalPalette.current
    val extension = item.file?.extension?.lowercase() ?: item.task?.choice?.extension
    val current = item.task != null && item.task.id == Transfers.currentTask?.id && item.phase != null
    SurfaceCard(Modifier.then(if (current) Modifier.testTag("transfer") else Modifier), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CategoryBadge(item.category, extension)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                // More lines at large font scales so the distinguishing end ("… (4).zip") stays visible.
                Text(breakableName(item.title), maxLines = if (LocalDensity.current.fontScale > 1.3f) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.ContentOrLtr))
                Text(subtitle(item, locale), style = MaterialTheme.typography.labelMedium,
                    color = if (item.status == BoardStatus.FAILED && item.failure?.cancelled != true) MaterialTheme.colorScheme.error
                        else palette.secondary,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            PrimaryAction(item, current, h)
            RowMenu(item, current, h)
        }
        if (item.status == BoardStatus.ACTIVE || (item.status == BoardStatus.PAUSED && current)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProgressTrack(item.percent, Modifier.weight(1f),
                    color = if (item.status == BoardStatus.PAUSED) palette.muted else palette.accent)
                item.percent?.let {
                    Text(percentLabel(it, locale), style = MaterialTheme.typography.labelMedium, color = palette.text)
                }
            }
        }
    }
}

@Composable
private fun subtitle(item: BoardItem, locale: Locale): String {
    val sizes = when {
        item.bytes != null && item.total != null && item.status != BoardStatus.COMPLETED ->
            "${bytesLabel(item.bytes, locale)} / ${bytesLabel(item.total, locale)}"
        item.total != null -> bytesLabel(item.total, locale)
        item.bytes != null -> bytesLabel(item.bytes, locale)
        else -> null
    }
    val status = when (item.status) {
        BoardStatus.ACTIVE -> when (item.phase) {
            // WAITING is only published while the transfer waits for a permitted network.
            Phase.WAITING -> stringResource(R.string.waiting_for_network)
            Phase.PROCESSING -> stringResource(R.string.processing)
            Phase.SAVING -> stringResource(R.string.saving)
            else -> item.bytesPerSecond?.let { stringResource(R.string.speed_value, bytesLabel(it, locale)) }
        }
        BoardStatus.QUEUED -> stringResource(R.string.status_queued)
        BoardStatus.PAUSED -> stringResource(R.string.status_paused)
        BoardStatus.FAILED -> stringResource(DownloadBoard.failureLabel(item))
        BoardStatus.COMPLETED -> stringResource(R.string.status_completed)
    }
    val eta = item.secondsLeft?.takeIf { item.status == BoardStatus.ACTIVE }?.let {
        stringResource(R.string.time_left, durationLabel(it))
    }
    return listOfNotNull(sizes, status, eta).joinToString("  •  ")
}

@Composable
private fun PrimaryAction(item: BoardItem, current: Boolean, h: RowHandlers) {
    val palette = LocalPalette.current
    val task = item.task
    when {
        item.status == BoardStatus.ACTIVE && item.phase == Phase.DOWNLOADING ->
            RoundAction(Glyph.PAUSE, stringResource(R.string.pause), h.onPause)
        // Deferred for the network (as in 1.3.2): the same toggle continues the retained lease
        // once the user is back; while the service still waits on its own it is a no-op.
        item.status == BoardStatus.ACTIVE && item.phase == Phase.WAITING && current ->
            RoundAction(Glyph.PLAY, stringResource(R.string.resume), h.onPause)
        // The service treats "pause" as a toggle: with no running job it resumes the paused lease
        // from its durable checkpoint (cold resume). Same contract as the 1.3.2 transfer card.
        item.status == BoardStatus.PAUSED && current ->
            RoundAction(Glyph.PLAY, stringResource(R.string.resume), h.onPause)
        item.status == BoardStatus.PAUSED && task != null ->
            RoundAction(Glyph.PLAY, stringResource(R.string.resume)) { h.onQueuedPaused(task, false) }
        item.status == BoardStatus.QUEUED && task != null ->
            RoundAction(Glyph.PAUSE, stringResource(R.string.pause)) { h.onQueuedPaused(task, true) }
        item.status == BoardStatus.FAILED && item.failure != null ->
            RoundAction(Glyph.RETRY, stringResource(R.string.retry)) { h.onRetryFailed(item.failure) }
        item.status == BoardStatus.COMPLETED ->
            Box(Modifier.size(32.dp).clip(CircleShape).background(palette.success.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center) {
                Symbol(Glyph.CHECK, stringResource(R.string.status_completed), Modifier.size(18.dp), palette.success)
            }
    }
}

@Composable
private fun RoundAction(glyph: Glyph, label: String, onClick: () -> Unit) {
    // The label belongs to the button itself so TalkBack announces "Pause, button".
    FilledTonalIconButton(onClick, Modifier.size(48.dp).testTag("row_primary").semantics { contentDescription = label },
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Neon.Raised, contentColor = Neon.Text)) {
        Symbol(glyph, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun RowMenu(item: BoardItem, current: Boolean, h: RowHandlers) {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val entries = buildList<Pair<Int, () -> Unit>> {
        val task = item.task
        val file = item.file
        when (item.status) {
            BoardStatus.ACTIVE -> {
                add(R.string.details to h.onDetails)
                add(R.string.cancel to h.onCancel)
            }
            BoardStatus.PAUSED -> if (current) {
                add(R.string.details to h.onDetails)
                add(R.string.cancel to h.onCancel)
            } else if (task != null) {
                add(R.string.move_up to { h.onMove(task, -1) })
                add(R.string.move_down to { h.onMove(task, 1) })
                add(R.string.remove_queue to { h.onRemove(task) })
            }
            BoardStatus.QUEUED -> if (task != null) {
                add(R.string.move_up to { h.onMove(task, -1) })
                add(R.string.move_down to { h.onMove(task, 1) })
                add(R.string.remove_queue to { h.onRemove(task) })
            }
            BoardStatus.FAILED -> item.failure?.let { failure ->
                add(R.string.retry to { h.onRetryFailed(failure) })
                add(R.string.remove_history to { h.onForgetFailure(failure) })
                add(R.string.copy_diagnostics to { copyDiagnostics(context, failure.code) })
                add(R.string.share_diagnostics to { runCatching { shareDiagnostics(context, failure.code) } })
            }
            BoardStatus.COMPLETED -> if (file != null) {
                add(R.string.preview to { h.onAction(file, "open") })
                add(R.string.open_external to { h.onAction(file, "external") })
                add(R.string.share to { h.onAction(file, "share") })
                add(R.string.details to { h.onAction(file, "info") })
                add(R.string.remove_history to { h.onAction(file, "hide") })
            }
        }
    }
    if (entries.isEmpty()) return
    Box {
        IconAction(Glyph.DOTS, stringResource(R.string.item_actions, item.title), { open = true },
            Modifier.testTag("row_menu"))
        DropdownMenu(open, { open = false }) {
            entries.forEach { (label, action) ->
                DropdownMenuItem({ Text(stringResource(label)) }, { open = false; action() })
            }
        }
    }
}
