package com.os73.dosos73

import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable
internal fun ScreenHeader(onSearch: () -> Unit, onMore: () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Brand(true)
            Spacer(Modifier.weight(1f))
            IconAction(Glyph.SEARCH, stringResource(R.string.search), onSearch)
            IconAction(Glyph.MORE, stringResource(R.string.more), onMore)
        }
    }
}

@Composable
internal fun SavedFileRow(
    item: SavedFile,
    onAction: (SavedFile, String) -> Unit,
    history: Boolean = false,
    refreshToken: Int = 0,
) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { DateFormat.getDateInstance(DateFormat.SHORT, locale) }
    var bitmap by remember(item.uri, item.date) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var availability by remember(item.uri) { mutableStateOf<FileAvailability?>(null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(item.uri, item.date, refreshToken, lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            val access = FileAccess.inspect(context, android.net.Uri.parse(item.uri))
            val image = if (access == FileAvailability.AVAILABLE) LibraryRepository.thumbnail(context, item) else null
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                    availability = access
                    bitmap = image
                }
            }
        }
    }
    fun previewAction() = when (availability) {
        FileAvailability.MISSING -> "missing"
        FileAvailability.PERMISSION_REQUIRED -> "permission"
        FileAvailability.UNAVAILABLE -> "unavailable"
        else -> "open"
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(15.dp))
                .background(Neon.Surface)
                .clickable { onAction(item, previewAction()) }
                .padding(11.dp)
                .testTag("file_row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Box(
                Modifier.size(65.dp, 75.dp).clip(RoundedCornerShape(10.dp)).background(Neon.Raised),
                contentAlignment = Alignment.Center,
            ) {
                bitmap?.let {
                    Image(
                        it.asImageBitmap(),
                        null,
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                    ?: Symbol(
                        when (item.kind) {
                            Kind.AUDIO -> Glyph.AUDIO
                            Kind.VIDEO -> Glyph.VIDEO
                            else -> Glyph.FOLDER
                        },
                        modifier = Modifier.size(30.dp),
                        color = if (item.kind == Kind.AUDIO) Neon.Purple else Neon.Cyan,
                    )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    item.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style =
                        MaterialTheme.typography.titleSmall.copy(
                            textDirection = TextDirection.ContentOrLtr
                        ),
                )
                Text(
                    bytesLabel(item.bytes, locale) +
                        "  •  " +
                        dateFormat.format(Date(item.date)),
                    style = MaterialTheme.typography.labelSmall,
                    color = Neon.Secondary,
                )
                val accessMessage = when(availability) {
                    FileAvailability.MISSING -> R.string.file_missing
                    FileAvailability.PERMISSION_REQUIRED -> R.string.file_permission_required
                    FileAvailability.UNAVAILABLE -> R.string.file_storage_unavailable
                    else -> null
                }
                accessMessage?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (item.extension.isNotEmpty())
                    Text(
                        item.extension,
                        Modifier.clip(RoundedCornerShape(5.dp))
                            .background(
                                if (item.kind == Kind.AUDIO) Neon.Purple.copy(alpha = 0.65f)
                                else Neon.Blue.copy(alpha = 0.5f)
                            )
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
            }
            Box {
                IconAction(Glyph.MORE, stringResource(R.string.file_actions), { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    listOf(
                            "open" to R.string.preview,
                            "external" to R.string.open_external,
                            "collection" to R.string.collection,
                            "share" to R.string.share,
                            "info" to R.string.details,
                        )
                        .forEach { (key, label) ->
                            DropdownMenuItem(
                                { Text(stringResource(label)) },
                                {
                                    menu = false
                                    onAction(item, if (key == "open") previewAction() else key)
                                },
                                enabled = availability != FileAvailability.MISSING || key in setOf("info", "collection"),
                            )
                        }
                    if (history)
                        DropdownMenuItem(
                            { Text(stringResource(R.string.remove_history)) },
                            {
                                menu = false
                                onAction(item, "hide")
                            },
                        )
                    else {
                        DropdownMenuItem(
                            { Text(stringResource(R.string.rename)) },
                            {
                                menu = false
                                onAction(item, "rename")
                            },
                            enabled = availability != FileAvailability.MISSING,
                        )
                        DropdownMenuItem(
                            {
                                Text(
                                    stringResource(R.string.delete_file),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            {
                                menu = false
                                onAction(item, "delete")
                            },
                            enabled = availability != FileAvailability.MISSING,
                        )
                    }
                    if (availability == FileAvailability.MISSING) {
                        DropdownMenuItem({ Text(stringResource(R.string.forget_missing)) }, {
                            menu = false
                            onAction(item, "forget")
                        })
                    }
                }
            }
        }
    }
}

@Composable
internal fun FileLibraryScreen(
    files: List<SavedFile>,
    query: String,
    onAction: (SavedFile, String) -> Unit,
    onRefresh: () -> Unit,
) {
    var accessGeneration by remember { mutableIntStateOf(0) }
    var category by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var ascending by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var selectedCollection by rememberSaveable { mutableStateOf("") }
    var largeOnly by rememberSaveable { mutableStateOf(false) }
    val memberships = remember(files) { LibraryPreferences.collections(context) }
    val collections = remember(memberships, files) {
        files.mapNotNull { memberships[it.uri] }.distinct().sorted()
    }
    LaunchedEffect(collections) {
        if (selectedCollection.isNotEmpty() && selectedCollection !in collections) selectedCollection = ""
    }
    val filtered =
        remember(files, query, category, sort, ascending, selectedCollection, largeOnly, memberships) {
            val rows =
                files.filter {
                    (category == 0 ||
                        (category == 1 && it.kind == Kind.VIDEO) ||
                        (category == 2 && it.kind == Kind.AUDIO)) &&
                        (it.name.contains(query, true) || it.mime.contains(query, true)) &&
                        (!largeOnly || it.bytes >= 100L * 1024 * 1024) &&
                        (selectedCollection.isEmpty() ||
                            memberships[it.uri] == selectedCollection)
                }
            val comparator =
                when (sort) {
                    1 -> compareBy<SavedFile> { it.name.lowercase() }
                    2 -> compareBy { it.bytes }
                    else -> compareBy { it.date }
                }
            rows.sortedWith(if (ascending) comparator else comparator.reversed())
        }
    Column(Modifier.fillMaxSize().padding(horizontal = 17.dp)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(R.string.all_files, R.string.video, R.string.audio).forEachIndexed {
                    index,
                    label ->
                    NeonChip(
                        category == index,
                        { category = index },
                        label = { Text(stringResource(label)) },
                        leadingIcon = {
                            Symbol(
                                when (index) {
                                    1 -> Glyph.VIDEO
                                    2 -> Glyph.AUDIO
                                    else -> Glyph.FOLDER
                                },
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                ChoiceMenu(
                    stringResource(R.string.sort_by),
                    stringResource(
                        when (sort) {
                            1 -> R.string.filename
                            2 -> R.string.file_size
                            else -> R.string.recent
                        }
                    ),
                ) { close ->
                    listOf(R.string.recent, R.string.filename, R.string.file_size).forEachIndexed {
                        i,
                        label ->
                        DropdownMenuItem(
                            { Text(stringResource(label)) },
                            {
                                sort = i
                                close()
                            },
                        )
                    }
                }
            }
            IconAction(
                Glyph.SORT,
                stringResource(if (ascending) R.string.ascending else R.string.descending),
                { ascending = !ascending },
            )
            IconAction(Glyph.CLOCK, stringResource(R.string.refresh), { accessGeneration++; onRefresh() })
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(
                largeOnly,
                { largeOnly = !largeOnly },
                label = { Text(stringResource(R.string.large_files)) },
            )
            FilterChip(
                selectedCollection.isEmpty(),
                { selectedCollection = "" },
                label = { Text(stringResource(R.string.all_collections)) },
            )
            collections.forEach { group ->
                FilterChip(
                    selectedCollection == group,
                    { selectedCollection = group },
                    label = { Text(group) },
                )
            }
        }
        if (filtered.isEmpty())
            EmptyState(
                Glyph.FOLDER,
                R.string.no_files,
                if (files.isEmpty() && query.isBlank()) R.string.files_empty_body else R.string.no_search_results,
            )
        else
            LazyColumn(
                Modifier.fillMaxSize().testTag("files_list"),
                verticalArrangement = Arrangement.spacedBy(5.dp),
                contentPadding = PaddingValues(bottom = 16.dp, top = 8.dp),
            ) {
                items(filtered, key = { it.uri }) { SavedFileRow(it, onAction, refreshToken = accessGeneration) }
            }
    }
}

@Composable
internal fun DownloadsScreen(
    state: TransferState,
    queue: List<DownloadTask>,
    files: List<SavedFile>,
    query: String,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onRemove: (DownloadTask) -> Unit,
    onRunQueue: () -> Unit,
    onAction: (SavedFile, String) -> Unit,
    onRetryFailed: (FailedDownload) -> Unit,
    onForgetFailure: (FailedDownload) -> Unit,
) {
    val failures by FailureJournal.failures.collectAsState()
    var filter by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(state.phase) {
        if (state.phase == Phase.COMPLETE) filter = 1 else if (state.active) filter = 0
    }
    val locale = LocalConfiguration.current.locales[0]
    val completed = files.filter { it.name.contains(query, true) }
    val waiting = queue.filter { it.analysis.title.contains(query, true) }
    Column(Modifier.fillMaxSize().padding(horizontal = 17.dp)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                listOf(
                        R.string.active_downloads to if (state.active) 1 else 0,
                        R.string.completed_downloads to files.size,
                        R.string.waiting_downloads to queue.size,
                    )
                    .forEachIndexed { i, pair ->
                        NeonChip(
                            filter == i,
                            { filter = i },
                            label = {
                                Text(
                                    stringResource(pair.first) +
                                        " " +
                                        NumberFormat.getIntegerInstance(locale).format(pair.second),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().testTag("downloads_list"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
        ) {
            if (filter == 0) {
                if (
                    state.phase != Phase.IDLE &&
                        state.title.contains(query, true) &&
                        (state.active || failures.none { it.task.id == Transfers.currentTask?.id })
                )
                    item { TransferCard(state, onCancel, onOpen, onPause, onRetry, onDetails) }
                if (state.phase == Phase.IDLE && failures.isEmpty())
                    item {
                        EmptyState(
                            Glyph.DOWNLOAD,
                            R.string.no_active_downloads,
                            R.string.downloads_empty_body,
                        )
                    }
                items(
                    failures.filter { it.task.analysis.title.contains(query, true) },
                    key = { "failed-${it.task.id}" },
                ) { failed ->
                    Panel {
                        Text(
                            failed.task.analysis.title,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(
                                if (failed.cancelled) R.string.cancelled
                                else
                                    PipelineException(
                                            Stage.TRANSFER,
                                            failed.code ?: FailureCode.DOWNLOAD_FAILED,
                                        )
                                        .messageResource()
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row {
                            TextButton({ onRetryFailed(failed) }) {
                                Text(stringResource(R.string.retry))
                            }
                            TextButton({ onForgetFailure(failed) }) {
                                Text(stringResource(R.string.remove_history))
                            }
                        }
                        DiagnosticActions(failed.code)
                    }
                }
                if (waiting.isNotEmpty())
                    item {
                        Text(
                            stringResource(R.string.waiting_downloads),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
            }
            if (filter == 1) {
                if (completed.isEmpty())
                    item {
                        EmptyState(
                            Glyph.CHECK,
                            R.string.no_completed_downloads,
                            R.string.files_empty_body,
                        )
                    }
                items(completed, key = { it.uri }) { SavedFileRow(it, onAction, true) }
            }
            if (filter == 2 && waiting.isEmpty())
                item { EmptyState(Glyph.CLOCK, R.string.queue_empty, R.string.queue_empty_body) }
            if (filter != 1) {
                if (waiting.isNotEmpty() && !state.active)
                    item {
                        GradientButton(
                            stringResource(R.string.start_queue),
                            onRunQueue,
                            Modifier.fillMaxWidth(),
                            icon = Glyph.PLAY,
                        )
                    }
                items(waiting, key = { it.id }) { task ->
                    Panel {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Symbol(Glyph.CLOCK, color = Neon.Muted)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    task.analysis.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    stringResource(
                                        if (task.userPaused) R.string.paused
                                        else R.string.waiting_to_start
                                    ),
                                    color = Neon.Muted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconAction(
                                Glyph.CLOSE,
                                stringResource(R.string.remove_queue),
                                { onRemove(task) },
                            )
                        }
                        QueueControls(task)
                    }
                }
            }
        }
    }
}
