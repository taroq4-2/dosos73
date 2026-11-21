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
internal fun ScreenHeader(title: String, glyph: Glyph, onSearch: () -> Unit,
    menu: (@Composable (close: () -> Unit) -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(glyph, Neon.Cyan, 44.dp)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
        IconAction(Glyph.SEARCH, stringResource(R.string.search), onSearch)
        if (menu != null) Box {
            IconAction(Glyph.DOTS, stringResource(R.string.more), { open = true })
            DropdownMenu(open, { open = false }) { menu { open = false } }
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
          try {
            val access = FileAccess.inspect(context, android.net.Uri.parse(item.uri))
            val image = if (access == FileAvailability.AVAILABLE) LibraryRepository.thumbnail(context, item) else null
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                    availability = access
                    bitmap = image
                }
            }
            kotlinx.coroutines.awaitCancellation()
          } finally {
            bitmap = null
          }
        }
    }
    fun previewAction() = when (availability) {
        FileAvailability.MISSING -> "missing"
        FileAvailability.PERMISSION_REQUIRED -> "permission"
        FileAvailability.UNAVAILABLE -> "unavailable"
        else -> "open"
    }
    run {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Neon.Surface)
                .clickable { onAction(item, previewAction()) }
                .padding(11.dp)
                .testTag("file_row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Neon.Raised),
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
                    ?: CategoryBadge(DownloadBoard.categoryOf(item), item.extension.lowercase(), 56.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    breakableName(item.name),
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
                if (item.extension.isNotEmpty()) {
                    val tint = LocalPalette.current.category(DownloadBoard.categoryOf(item))
                    Text(
                        item.extension,
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(tint.copy(alpha = 0.18f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                    )
                }
            }
            Box {
                IconAction(Glyph.DOTS, stringResource(R.string.file_actions), { menu = true })
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
                    if (item.kind == Kind.VIDEO)
                        DropdownMenuItem({ Text(stringResource(R.string.offline_convert)) }, {
                            menu = false
                            onAction(item, "extract_mp3")
                        }, enabled = availability == FileAvailability.AVAILABLE)
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
                    (category == 0 || DownloadBoard.categoryOf(it) == libraryCategories[category].first) &&
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
    val counts = remember(files) { files.groupingBy { DownloadBoard.categoryOf(it) }.eachCount() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            libraryCategories.forEachIndexed { index, (value, label) ->
                // Empty categories are hidden instead of offering filters with nothing behind them.
                if (index != 0 && (counts[value] ?: 0) == 0 && category != index) return@forEachIndexed
                NeonChip(
                    category == index,
                    { category = index },
                    label = { Text(stringResource(label)) },
                    leadingIcon = {
                        Symbol(if (index == 0) Glyph.FOLDER else value.glyph(), modifier = Modifier.size(18.dp))
                    },
                    modifier = Modifier.testTag("library_category_$index"),
                )
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

/** Library filters (index 0 = all files). Order follows the most common downloads. */
internal val libraryCategories: List<Pair<FileCategory, Int>> = listOf(
    FileCategory.OTHER to R.string.all_files,
    FileCategory.VIDEO to R.string.video,
    FileCategory.AUDIO to R.string.audio,
    FileCategory.APP to R.string.category_apps,
    FileCategory.DOCUMENT to R.string.category_documents,
    FileCategory.IMAGE to R.string.category_images,
    FileCategory.ARCHIVE to R.string.category_archives,
    FileCategory.PROGRAM to R.string.category_programs,
    FileCategory.OTHER to R.string.category_other,
)
