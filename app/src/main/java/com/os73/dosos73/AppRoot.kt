package com.os73.dosos73

import android.Manifest
import android.app.LocaleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MainActivity.AppScreen(model: MainViewModel, sharedGeneration: Int, quickSettingsGeneration: Int = 0) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val screen by model.state.collectAsStateWithLifecycle()
    val transfer by Transfers.state.collectAsStateWithLifecycle()
    val files by LibraryRepository.files.collectAsStateWithLifecycle()
    val queue by DownloadQueue.tasks.collectAsStateWithLifecycle()
    val batch by model.batch.collectAsStateWithLifecycle()
    val batchBusy by model.batchBusy.collectAsStateWithLifecycle()
    val batchSaving by model.batchSaving.collectAsStateWithLifecycle()
    val batchStorageError by model.batchStorageError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var page by rememberSaveable { mutableStateOf("home") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(sharedGeneration) {
        if (sharedGeneration > 0) {
            page = "home"
            searching = false
            query = ""
        }
    }
    LaunchedEffect(quickSettingsGeneration) {
        if (quickSettingsGeneration > 0) page = "quick"
    }
    var quickEnabled by remember { mutableStateOf(QuickLaunch.enabled(this)) }
    var launcherPaste by remember { mutableStateOf(QuickLaunch.launcherPaste(this)) }
    var submitting by remember { mutableStateOf(false) }
    var presetApplying by remember { mutableStateOf(false) }
    var requestingNotifications by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<String?>(null) }
    var fileAction by rememberSaveable(stateSaver = listSaver<Pair<SavedFile, String>?, Any>(
        save = { value -> value?.let { (file, action) ->
            listOf(file.uri, file.name, file.mime, file.bytes, file.date, action)
        } ?: emptyList() },
        restore = { if (it.isEmpty()) null else SavedFile(it[0] as String, it[1] as String,
            it[2] as String, it[3] as Long, it[4] as Long) to (it[5] as String) },
    )) { mutableStateOf(null) }
    var renamed by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<SavedFile?>(null) }
    var duplicate by remember { mutableStateOf<Pair<SavedFile, kotlinx.coroutines.CompletableDeferred<Int>>?>(null) }
    var localUri by rememberSaveable { mutableStateOf<String?>(null) }
    var localInput by remember { mutableStateOf<LocalAudioInput?>(null) }
    var localLoading by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<Int?>(null) }
    var pendingStart by rememberSaveable { mutableStateOf<String?>(null) }
    var libraryRevision by remember { mutableIntStateOf(0) }
    val batchRequested by model.batchRequested.collectAsStateWithLifecycle()
    LaunchedEffect(batchRequested) {
        if (batchRequested) {
            page = "batch"
            model.batchOpened()
        }
    }
    var hidden by remember { mutableStateOf(LibraryRepository.hidden(this)) }
    val savedPages = rememberSaveableStateHolder()
    val preferences = remember { getSharedPreferences("destination", MODE_PRIVATE) }
    var tree by remember {
        mutableStateOf(preferences.getString("tree", null)?.let(Uri::parse))
    }
    fun message(id: Int) {
        scope.launch { snackbar.showSnackbar(getString(id)) }
    }
    fun refresh() {
        scope.launch {
            runCatching { LibraryRepository.refresh(this@AppScreen) }
                .onFailure { message(R.string.error_storage) }
        }
    }
    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) {
                DownloadQueue.initialize(this@AppScreen)
                FailureJournal.initialize(this@AppScreen)
            }
            val interrupted = DownloadQueue.interrupted(this@AppScreen)
            if (!Transfers.state.value.active && (interrupted != null || DownloadQueue.hasRunnable())) {
                if (interrupted?.userPaused == true) {
                    if (Transfers.reserve(interrupted)) {
                        Transfers.take()
                        Transfers.update(
                            TransferState(Phase.PAUSED, interrupted.analysis.title)
                        )
                    }
                } else
                    runCatching {
                            ContextCompat.startForegroundService(
                                this@AppScreen,
                                Intent(this@AppScreen, DownloadService::class.java),
                            )
                        }
                        .onFailure { message(R.string.error_start) }
            }
            refresh()
        } catch (_: Exception) {
            message(R.string.error_storage)
        }
    }
    LaunchedEffect(transfer.phase) {
        if (transfer.phase == Phase.COMPLETE) refresh()
        if (transfer.phase == Phase.FAILED) model.invalidateFormats()
    }
    BackHandler(page != "home") {
        page = if (page == "contact") "about" else "home"
        searching = false
        query = ""
    }
    // Persisted grants remain valid for queued jobs and saved documents when defaults change.
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null)
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                            tree = uri
                    preferences.edit().putString("tree", uri.toString()).apply()
                } catch (_: SecurityException) {
                    message(R.string.error_storage)
                }
        }
    var pendingBatchDestination by rememberSaveable { mutableStateOf<String?>(null) }
    val batchPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val url = pendingBatchDestination
        pendingBatchDestination = null
        if (uri != null && url != null) {
            try {
                contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                model.batchDestination(url, uri.toString())
            } catch (_: SecurityException) { message(R.string.error_storage) }
        }
    }
    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                localUri = uri.toString()
            } catch (_: SecurityException) { message(R.string.offline_error_read) }
        }
    }
    LaunchedEffect(localUri) {
        localInput = null
        localError = null
        val uri = localUri ?: return@LaunchedEffect
        localLoading = true
        try { localInput = OfflineAudio(this@AppScreen).inspect(Uri.parse(uri)) }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) { localError = (error as? OfflineAudioException)?.messageResource ?: R.string.offline_error_read }
        finally { localLoading = false }
    }
    fun applyPreset(choice: Choice, destination: String?) {
        if (presetApplying || submitting || requestingNotifications) return
        val target = model.state.value.analysis?.url ?: return
        val previousChoice = model.state.value.selected
        presetApplying = true
        scope.launch {
            try {
                val uri = destination?.let(Uri::parse)
                withContext(Dispatchers.IO) {
                    if (uri != null) {
                        val owns = packageManager.resolveContentProvider(uri.authority.orEmpty(), 0)
                            ?.applicationInfo?.uid == android.os.Process.myUid()
                        require(owns || contentResolver.persistedUriPermissions.any {
                            it.uri == uri && it.isWritePermission
                        })
                    }
                    OutputStore(this@AppScreen).preflight(uri)
                }
                val current = model.state.value
                val currentAnalysis = current.analysis ?: return@launch
                if (currentAnalysis.url != target || choice !in currentAnalysis.choices ||
                    current.selected != previousChoice || current.busy) return@launch
                model.select(choice)
                tree = uri
                preferences.edit().apply {
                    if (uri == null) remove("tree") else putString("tree", uri.toString())
                }.apply()
                message(R.string.tools_preset_applied)
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { message(R.string.tools_preset_folder_error) }
            finally { presetApplying = false }
        }
    }
    suspend fun reviewExisting(task: DownloadTask): Boolean {
        val existing = try { DownloadHistory.find(this@AppScreen, task) }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { message(R.string.tools_history_error); null }
        if (existing == null) return true
        val answer = kotlinx.coroutines.CompletableDeferred<Int>()
        duplicate = existing to answer
        return try {
            when (answer.await()) {
                1 -> true
                2 -> { model.closeQuick(); preview = existing; false }
                else -> false
            }
        } finally { if (duplicate?.second === answer) duplicate = null }
    }
    fun runQueue() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, DownloadService::class.java),
            )
        } catch (_: Exception) {
            message(R.string.error_start)
        }
    }
    fun enqueue(task: DownloadTask, retryOf: String? = null) {
        if (submitting) return
        submitting = true
        scope.launch {
            try {
                if (retryOf == null && !reviewExisting(task)) return@launch
                DownloadQueue.enqueue(this@AppScreen, task)
                retryOf?.let {
                    FailureJournal.remove(this@AppScreen, it, discardPartial = false)
                }
                model.closeQuick()
                page = "downloads"
                query = ""
                runQueue()
            } catch (_: Exception) {
                message(R.string.error_start)
            } finally {
                submitting = false
            }
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestingNotifications = false
        val task = pendingStart
        pendingStart = null
        if (task != null) {
            try { enqueue(DownloadQueue.decodeTask(org.json.JSONObject(task))) }
            catch (_: Exception) { message(R.string.error_start) }
        }
    }
    fun requestTask(task: DownloadTask) {
        if (submitting || requestingNotifications || presetApplying) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this@AppScreen, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingStart = DownloadQueue.encodeTask(task).toString()
            requestingNotifications = true
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else enqueue(task)
    }
    fun requestStart() {
        val analysis = screen.analysis ?: return
        val choice = screen.selected ?: return
        requestTask(DownloadTask(analysis, choice, tree))
    }
    fun command(action: String) {
        try {
            startService(Intent(this, DownloadService::class.java).setAction(action))
        } catch (_: Exception) {
            message(R.string.error_start)
        }
    }
    fun open(uri: Uri, mime: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: Exception) {
            message(R.string.error_open)
        }
    }
    fun action(item: SavedFile, type: String) {
        when (type) {
            "missing" -> message(R.string.file_missing)
            "permission" -> message(R.string.file_permission_required)
            "unavailable" -> message(R.string.file_storage_unavailable)
            "extract_mp3" -> { localUri = item.uri; page = "offline" }
            "open" -> preview = item
            "external" -> open(Uri.parse(item.uri), item.mime)
            "share" ->
                try {
                    val uri = Uri.parse(item.uri)
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND)
                                .setType(item.mime)
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .apply {
                                    clipData = ClipData(item.name, arrayOf(item.mime), ClipData.Item(uri))
                                }
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            getString(R.string.share),
                        )
                    )
                } catch (_: Exception) {
                    message(R.string.error_open)
                }
            "hide" ->
                scope.launch {
                    runCatching {
                            LibraryRepository.hideHistory(this@AppScreen, item)
                            hidden = LibraryRepository.hidden(this@AppScreen)
                        }
                        .onFailure { message(R.string.error_storage) }
                }
            else -> {
                renamed =
                    if (type == "collection") LibraryPreferences.collection(this, item.uri)
                    else item.name
                fileAction = item to type
            }
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Neon.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                NavigationBar(
                    containerColor = Color(0xFF040E20),
                    tonalElevation = 0.dp,
                    modifier = Modifier.navigationBarsPadding().heightIn(
                        min = (80 + 24 * (LocalConfiguration.current.fontScale - 1f).coerceAtLeast(0f)).dp
                    ),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    listOf(
                            Triple("home", R.string.nav_home, Glyph.HOME),
                            Triple("downloads", R.string.downloads, Glyph.DOWNLOAD),
                            Triple("files", R.string.nav_files, Glyph.FOLDER),
                            Triple("more", R.string.more, Glyph.MORE),
                        )
                        .forEach { (key, label, icon) ->
                            NavigationBarItem(
                                selected = page == key,
                                onClick = {
                                    focus.clearFocus()
                                    keyboard?.hide()
                                    page = key
                                    query = ""
                                    searching = false
                                    if (key == "files") refresh()
                                },
                                icon = {
                                    Symbol(
                                        icon,
                                        color = if (page == key) Neon.Cyan else Neon.Muted,
                                    )
                                },
                                label = {
                                    Text(
                                        stringResource(label),
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                colors =
                                    NavigationBarItemDefaults.colors(
                                        indicatorColor = Color.Transparent,
                                        selectedTextColor = Neon.Cyan,
                                        unselectedTextColor = Neon.Muted,
                                    ),
                                modifier = Modifier.testTag("nav_$key"),
                            )
                        }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding().imePadding()) {
            if (presetApplying) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.tools_preset_checking), Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (page in setOf("downloads", "files")) {
                ScreenHeader(
                    {
                        searching = !searching
                        if (!searching) query = ""
                    },
                    { page = "more" },
                )
                if (searching)
                    OutlinedTextField(
                        query,
                        { query = it },
                        Modifier.fillMaxWidth().padding(horizontal = 17.dp).testTag("search"),
                        singleLine = true,
                        label = { Text(stringResource(R.string.search)) },
                    )
            }
            savedPages.SaveableStateProvider(page) {
                when (page) {
                    "home" ->
                        HomeScreen(
                            screen,
                            transfer,
                            tree != null,
                            model::edit,
                            model::select,
                            model::analyze,
                            model::cancelAnalysis,
                            onPaste = {
                                model.paste(
                                    getSystemService(ClipboardManager::class.java)
                                        .primaryClip
                                        ?.takeIf { it.itemCount > 0 }
                                        ?.getItemAt(0)
                                        ?.text
                                        ?.toString()
                                )
                            },
                            onSettings = { page = "settings" },
                            onFiles = {
                                page = "files"
                                refresh()
                            },
                            onAudio = {
                                model.prefer(Kind.AUDIO)
                                message(R.string.audio_tool_hint)
                            },
                            onVideo = { model.prefer(Kind.VIDEO) },
                            onFolder = { picker.launch(tree) },
                            onDefaultFolder = {
                                    tree = null
                                preferences.edit().remove("tree").apply()
                            },
                            onStart = ::requestStart,
                            onSource = { source = it },
                            onOffline = { page = "offline" },
                            onPreset = ::applyPreset,
                            startEnabled = !submitting && !requestingNotifications && !presetApplying,
                        )
                    "downloads" ->
                        DownloadsScreen(
                            transfer,
                            queue,
                            files
                                .filterNot { it.uri in hidden }
                                .filter { LibraryPreferences.visibleHistory(this@AppScreen, it) },
                            query,
                            { command("cancel") },
                            { command("pause") },
                            {
                                Transfers.currentTask?.let {
                                    enqueue(it.copy(userPaused = false))
                                }
                            },
                            { transfer.uri?.let { open(it, transfer.mime) } },
                            { details = true },
                            { task ->
                                scope.launch {
                                    runCatching {
                                            DownloadQueue.remove(this@AppScreen, task.id)
                                        }
                                        .onFailure { message(R.string.error_storage) }
                                }
                            },
                            { runQueue() },
                            ::action,
                            { failed ->
                                enqueue(failed.task.copy(userPaused = false), failed.task.id)
                            },
                            { failed ->
                                scope.launch {
                                    runCatching {
                                            FailureJournal.remove(
                                                this@AppScreen,
                                                failed.task.id,
                                            )
                                        }
                                        .onFailure { message(R.string.error_storage) }
                                }
                            },
                        )
                    "files" ->
                        key(libraryRevision) {
                            FileLibraryScreen(files, query, ::action, ::refresh)
                        }
                    "privacy" ->
                        PrivacyScreen {
                            hidden = LibraryRepository.hidden(this@AppScreen)
                            libraryRevision++
                        }
                    "batch" ->
                        BatchScreen(
                            batch,
                            batchBusy,
                            model::prepareBatch,
                            model::selectBatch,
                            model::analyzeBatch,
                            model::cancelAnalysis,
                            model::chooseBatch,
                            { index, useDownloads ->
                                batch.getOrNull(index)?.url?.let { url ->
                                    if (useDownloads) model.batchDestination(url, null)
                                    else {
                                        pendingBatchDestination = url
                                        batchPicker.launch(batch[index].destination?.let(Uri::parse) ?: tree)
                                    }
                                }
                            },
                            saving = batchSaving,
                            storageError = batchStorageError,
                            onRetryStorage = model::retryBatchDraft,
                            onQueue = {
                                if (!submitting && !batchSaving && !batchStorageError && !presetApplying) {
                                    submitting = true
                                    scope.launch {
                                        try {
                                            val ready =
                                                batch.filter { it.selected && it.analysis != null }
                                            require(queue.size + ready.size <= 50)
                                            val tasks = ready.map { entry ->
                                                val analysis = entry.analysis!!
                                                entry to DownloadTask(analysis,
                                                    entry.choice?.takeIf { it in analysis.choices } ?: analysis.choices.first(),
                                                    if (entry.destinationOverride) entry.destination?.let(Uri::parse) else tree)
                                            }
                                            // Finish duplicate review before committing any batch row.
                                            for ((_, task) in tasks) if (!reviewExisting(task)) return@launch
                                            for ((entry, task) in tasks) {
                                                model.queueBatch(entry, task)
                                                runQueue()
                                            }
                                            // Failed and unselected entries remain available for retry.
                                            page = "downloads"
                                            runQueue()
                                        } catch (_: Exception) {
                                            message(R.string.error_start)
                                        } finally {
                                            submitting = false
                                        }
                                    }
                                }
                            },
                        )
                    "about" -> AboutScreen({ page = "more" }, { page = "contact" })
                    "contact" -> ContactScreen { page = "about" }
                    "settings" -> SettingsScreen(tree != null,
                        ::setLanguage, { picker.launch(tree) }, {
                            tree = null
                            preferences.edit().remove("tree").apply()
                        }, { page = "privacy" }, { page = "batch" }, { page = "about" }, { page = "quick" }, { page = "offline" })
                    "offline" -> OfflineAudioScreen(localInput, localLoading, localError, tree != null,
                        onPick = { localPicker.launch(arrayOf("video/*")) },
                        onFolder = { picker.launch(tree) },
                        onDefaultFolder = { tree = null; preferences.edit().remove("tree").apply() },
                        onStart = {
                            localInput?.let { input ->
                                val title = input.name.substringBeforeLast('.', input.name).take(200)
                                val analysis = Analysis(input.uri.toString(), title, listOf(OfflineAudio.CHOICE), false)
                                requestTask(DownloadTask(analysis, OfflineAudio.CHOICE, tree, localSource = input.uri))
                            }
                        })
                    "quick" -> QuickLaunchScreen(quickEnabled, launcherPaste,
                        onEnabled = { value ->
                            try {
                                QuickLaunch.setEnabled(this@AppScreen, value)
                                quickEnabled = value
                                launcherPaste = QuickLaunch.launcherPaste(this@AppScreen)
                            } catch (_: RuntimeException) { message(R.string.error_storage) }
                        },
                        onLauncherPaste = { value ->
                            try {
                                QuickLaunch.setLauncherPaste(this@AppScreen, value)
                                launcherPaste = value
                            } catch (_: RuntimeException) { message(R.string.error_storage) }
                        },
                        onSetup = {
                            try { QuickLaunch.setup(this@AppScreen) }
                            catch (_: RuntimeException) { message(R.string.quick_setup_unavailable) }
                        },
                        onPin = {
                            if (!runCatching { QuickLaunch.pin(this@AppScreen) }.getOrDefault(false))
                                message(R.string.quick_pin_unavailable)
                        },
                        onTry = { startActivity(QuickLaunch.intent(this@AppScreen)) })
                    else -> MoreScreen { page = it }

                }
            }
        }
    }
    if (screen.quick) {
        val compactQuick = LocalConfiguration.current.screenHeightDp < 420
        AlertDialog(
            modifier = (if (compactQuick) Modifier.widthIn(max = 640.dp).fillMaxWidth()
                .padding(vertical = 24.dp) else Modifier)
                .testTag("quick_dialog"),
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = !compactQuick),
            onDismissRequest = model::closeQuick,
            title = {
                Text(stringResource(R.string.quick_entry_label), maxLines = if (compactQuick) 1 else Int.MAX_VALUE,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(screen.url, maxLines = if (compactQuick) 1 else 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = Neon.Secondary)
                    if (screen.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    screen.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    AnalysisOptions(screen, tree != null, model::select, { picker.launch(tree) }, {
                        tree = null
                        preferences.edit().remove("tree").apply()
                    }, ::applyPreset)
                }
            },
            confirmButton = {
                TextButton(::requestStart, enabled = !screen.busy && screen.selected != null &&
                    !submitting && !requestingNotifications && !presetApplying, modifier = Modifier.testTag("quick_start")) {
                    Symbol(Glyph.DOWNLOAD)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.start_short))
                }
            },
            dismissButton = { TextButton(model::closeQuick, Modifier.testTag("quick_close")) { Text(stringResource(R.string.cancel)) } },
        )
    }
    duplicate?.let { (item, answer) ->
        AlertDialog(
            modifier = Modifier.testTag("history_dialog"),
            onDismissRequest = { answer.complete(0) },
            title = { Text(stringResource(R.string.history_downloaded_before)) },
            text = { Column {
                Text(stringResource(R.string.history_downloaded_before_message))
                Text(item.name, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                TextButton({ answer.complete(2) }, Modifier.testTag("history_open")) {
                    Text(stringResource(R.string.history_open_existing))
                }
            } },
            confirmButton = { TextButton({ answer.complete(1) }, Modifier.testTag("history_again")) {
                Text(stringResource(R.string.history_download_again))
            } },
            dismissButton = { TextButton({ answer.complete(0) }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    preview?.let { item ->
        OfflinePreview(
            item,
            { preview = null },
            {
                preview = null
                open(Uri.parse(item.uri), item.mime)
            },
        )
    }
    if (screen.pendingShared != null && !screen.quick && !screen.busy && !batchBusy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.pending_link)) },
            text = { Text(stringResource(R.string.pending_link_hint)) },
            confirmButton = {
                TextButton(model::acceptPending) { Text(stringResource(R.string.batch_review)) }
            },
        )
    }
    if (source != null)
        AlertDialog(
            onDismissRequest = { source = null },
            title = { Text(source!!) },
            text = { Text(stringResource(R.string.source_hint)) },
            confirmButton = {
                TextButton({ source = null }) { Text(stringResource(R.string.got_it)) }
            },
        )
    if (details)
        AlertDialog(
            onDismissRequest = { details = false },
            title = { Text(transfer.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(phaseText(transfer.phase)))
                    Text(transfer.detail)
                    Text(bytesLabel(transfer.bytes, LocalConfiguration.current.locales[0]))
                    Text(stringResource(R.string.pause_explanation), color = Neon.Secondary)
                }
            },
            confirmButton = {
                TextButton({ details = false }) { Text(stringResource(R.string.close)) }
            },
        )
    fileAction?.let { (item, type) ->
        AlertDialog(
            onDismissRequest = { fileAction = null },
            title = {
                Text(
                    stringResource(
                        when (type) {
                            "rename" -> R.string.rename
                            "collection" -> R.string.collection
                            "delete" -> R.string.delete_file
                            "forget" -> R.string.forget_missing
                            else -> R.string.details
                        }
                    )
                )
            },
            text = {
                when (type) {
                    "rename",
                    "collection" ->
                        OutlinedTextField(
                            renamed,
                            { renamed = it.take(180) },
                            label = {
                                Text(
                                    stringResource(
                                        if (type == "collection") R.string.collection
                                        else R.string.filename
                                    )
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.testTag("rename_input"),
                        )
                    "delete" -> Text(stringResource(R.string.delete_confirmation, item.name))
                    "forget" -> Text(stringResource(R.string.forget_missing_confirm))
                    else -> FileDetailsContent(item)
                }
            },
            confirmButton = {
                TextButton(
                    {
                        if (type == "info") fileAction = null
                        else
                            scope.launch {
                                try {
                                    if (type == "rename")
                                        LibraryRepository.rename(
                                            this@AppScreen,
                                            item,
                                            renamed,
                                        )
                                    else if (type == "collection") {
                                        LibraryPreferences.setCollection(
                                            this@AppScreen,
                                            item.uri,
                                            renamed,
                                        )
                                        libraryRevision++
                                    } else if (type == "forget") {
                                        LibraryRepository.forgetMissing(this@AppScreen, item)
                                    } else LibraryRepository.delete(this@AppScreen, item)
                                    hidden = LibraryRepository.hidden(this@AppScreen)
                                    libraryRevision++
                                    fileAction = null
                                    message(R.string.action_done)
                                } catch (_: Exception) {
                                    message(R.string.error_storage)
                                }
                            }
                    },
                    enabled = type != "rename" || renamed.isNotBlank(),
                ) {
                    Text(
                        stringResource(if (type == "info") R.string.close else R.string.confirm)
                    )
                }
            },
            dismissButton = {
                if (type != "info")
                    TextButton({ fileAction = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

