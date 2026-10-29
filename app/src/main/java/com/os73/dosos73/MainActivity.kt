package com.os73.dosos73

import android.Manifest
import android.app.LocaleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    private var sharedGeneration by mutableIntStateOf(0)

    override fun attachBaseContext(newBase: Context) {
        val stored =
            newBase.getSharedPreferences("appearance", MODE_PRIVATE).getString("language", "ar")!!
        val languages =
            if (Build.VERSION.SDK_INT >= 33)
                newBase.getSystemService(LocaleManager::class.java).applicationLocales
            else LocaleList.getEmptyLocaleList()
        val locale = if (languages.isEmpty) Locale.forLanguageTag(stored) else languages[0]
        val config =
            android.content.res.Configuration(newBase.resources.configuration).apply {
                setLocale(locale)
                setLayoutDirection(locale)
            }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (savedInstanceState == null) receive(intent)
        setContent { DososTheme { AppScreen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let(model::receive)
            intent.removeExtra(Intent.EXTRA_TEXT)
            sharedGeneration++
        }
    }

    @Composable
    private fun AppScreen() {
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
        var submitting by remember { mutableStateOf(false) }
        var requestingNotifications by remember { mutableStateOf(false) }
        var details by remember { mutableStateOf(false) }
        var source by remember { mutableStateOf<String?>(null) }
        var fileAction by remember { mutableStateOf<Pair<SavedFile, String>?>(null) }
        var renamed by remember { mutableStateOf("") }
        var preview by remember { mutableStateOf<SavedFile?>(null) }
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
                runCatching { LibraryRepository.refresh(this@MainActivity) }
                    .onFailure { message(R.string.error_storage) }
            }
        }
        LaunchedEffect(Unit) {
            try {
                withContext(Dispatchers.IO) {
                    DownloadQueue.initialize(this@MainActivity)
                    FailureJournal.initialize(this@MainActivity)
                }
                val interrupted = DownloadQueue.interrupted(this@MainActivity)
                if (!Transfers.state.value.active && interrupted != null) {
                    if (interrupted.userPaused) {
                        if (Transfers.reserve(interrupted)) {
                            Transfers.take()
                            Transfers.update(
                                TransferState(Phase.PAUSED, interrupted.analysis.title)
                            )
                        }
                    } else
                        runCatching {
                                ContextCompat.startForegroundService(
                                    this@MainActivity,
                                    Intent(this@MainActivity, DownloadService::class.java),
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
        fun releaseTree() {
            // Existing queued jobs and saved SAF documents retain their original grant.
            // Changing the default destination must never invalidate those references.
        }
        val picker =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null)
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                        if (tree != uri) releaseTree()
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
                    DownloadQueue.enqueue(this@MainActivity, task)
                    retryOf?.let {
                        FailureJournal.remove(this@MainActivity, it, discardPartial = false)
                    }
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
        fun start() {
            val analysis = screen.analysis ?: return
            val choice = screen.selected ?: return
            enqueue(DownloadTask(analysis, choice, tree))
        }
        val notifications =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                requestingNotifications = false
                start()
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
                                        clipData = ClipData.newUri(contentResolver, item.name, uri)
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
                                LibraryRepository.hideHistory(this@MainActivity, item)
                                hidden = LibraryRepository.hidden(this@MainActivity)
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
                        modifier = Modifier.navigationBarsPadding().heightIn(min = 68.dp),
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
                                    releaseTree()
                                    tree = null
                                    preferences.edit().remove("tree").apply()
                                },
                                onStart = {
                                    if (!submitting && !requestingNotifications) {
                                        if (
                                            Build.VERSION.SDK_INT >= 33 &&
                                                ContextCompat.checkSelfPermission(
                                                    this@MainActivity,
                                                    Manifest.permission.POST_NOTIFICATIONS,
                                                ) !=
                                                    android.content.pm.PackageManager
                                                        .PERMISSION_GRANTED
                                        ) {
                                            requestingNotifications = true
                                            notifications.launch(
                                                Manifest.permission.POST_NOTIFICATIONS
                                            )
                                        } else start()
                                    }
                                },
                                onSource = { source = it },
                            )
                        "downloads" ->
                            DownloadsScreen(
                                transfer,
                                queue,
                                files
                                    .filterNot { it.uri in hidden }
                                    .filter { LibraryPreferences.visibleHistory(this@MainActivity, it) },
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
                                                DownloadQueue.remove(this@MainActivity, task.id)
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
                                                    this@MainActivity,
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
                                hidden = LibraryRepository.hidden(this@MainActivity)
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
                                    if (!submitting && !batchSaving && !batchStorageError) {
                                        submitting = true
                                        scope.launch {
                                            try {
                                                val ready =
                                                    batch.filter { it.selected && it.analysis != null }
                                                require(queue.size + ready.size <= 50)
                                                ready.forEach { entry ->
                                                    val analysis = entry.analysis!!
                                                    DownloadQueue.enqueue(
                                                        this@MainActivity,
                                                        DownloadTask(
                                                            analysis,
                                                            entry.choice?.takeIf { it in analysis.choices } ?: analysis.choices.first(),
                                                            if (entry.destinationOverride) entry.destination?.let(Uri::parse) else tree,
                                                        ),
                                                    )
                                                    model.removeBatch(entry)
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
                        "settings" ->
                            Page {
                                Brand(true)
                                Text(
                                    stringResource(R.string.settings),
                                    style = MaterialTheme.typography.headlineMedium,
                                )
                                Panel {
                                    Text(
                                        stringResource(R.string.language),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Row {
                                        TextButton({ setLanguage("ar") }) {
                                            Text(stringResource(R.string.language_ar))
                                        }
                                        TextButton({ setLanguage("en") }) {
                                            Text(stringResource(R.string.language_en))
                                        }
                                    }
                                }
                                Panel {
                                    Text(
                                        stringResource(R.string.destination),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        stringResource(
                                            if (tree == null) R.string.default_folder
                                            else R.string.custom_folder
                                        ),
                                        color = Neon.Secondary,
                                    )
                                    OutlinedButton(
                                        { picker.launch(tree) },
                                        Modifier.fillMaxWidth(),
                                    ) {
                                        Text(stringResource(R.string.change))
                                    }
                                    if (tree != null)
                                        TextButton({
                                            releaseTree()
                                            tree = null
                                            preferences.edit().remove("tree").apply()
                                        }) {
                                            Text(stringResource(R.string.default_folder))
                                        }
                                }
                                NetworkControls()
                                OutlinedButton({ page = "privacy" }, Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.privacy_title))
                                }
                                OutlinedButton({ page = "batch" }, Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.batch_title))
                                }
                                Panel {
                                    Text(
                                        stringResource(R.string.download_behavior),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        stringResource(R.string.pause_explanation),
                                        color = Neon.Secondary,
                                    )
                                    Text(
                                        stringResource(R.string.notice),
                                        color = Neon.Secondary,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                TextButton({ page = "about" }, Modifier.testTag("about")) {
                                    Text(stringResource(R.string.about))
                                }
                                Text(BuildConfig.VERSION_NAME, color = Neon.Muted)
                            }
                        else ->
                            Page {
                                Brand()
                                Text(
                                    stringResource(R.string.more),
                                    style = MaterialTheme.typography.headlineMedium,
                                )
                                listOf(
                                        Triple("settings", R.string.settings, Glyph.SETTINGS),
                                        Triple("batch", R.string.batch_title, Glyph.LINK),
                                        Triple("privacy", R.string.privacy_title, Glyph.INFO),
                                        Triple("about", R.string.about, Glyph.INFO),
                                        Triple("contact", R.string.contact, Glyph.SHARE),
                                    )
                                    .forEach { (key, label, icon) ->
                                        OutlinedButton(
                                            { page = key },
                                            Modifier.fillMaxWidth()
                                                .heightIn(min = 56.dp)
                                                .testTag(key),
                                        ) {
                                            Symbol(icon)
                                            Spacer(Modifier.width(12.dp))
                                            Text(stringResource(label))
                                        }
                                    }
                                Text(
                                    stringResource(R.string.notice),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Neon.Muted,
                                )
                                Text(BuildConfig.VERSION_NAME, color = Neon.Muted)
                            }
                    }
                }
            }
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
        if (screen.pendingShared != null && !screen.busy && !batchBusy)
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.pending_link)) },
                text = { Text(stringResource(R.string.pending_link_hint)) },
                confirmButton = {
                    TextButton(model::acceptPending) { Text(stringResource(R.string.batch_review)) }
                },
            )
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
                        else ->
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(item.name)
                                Text(bytesLabel(item.bytes, LocalConfiguration.current.locales[0]))
                                Text(item.mime)
                                Text(DateFormat.getDateTimeInstance().format(Date(item.date)))
                            }
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
                                                this@MainActivity,
                                                item,
                                                renamed,
                                            )
                                        else if (type == "collection") {
                                            LibraryPreferences.setCollection(
                                                this@MainActivity,
                                                item.uri,
                                                renamed,
                                            )
                                            libraryRevision++
                                        } else if (type == "forget") {
                                            LibraryRepository.forgetMissing(this@MainActivity, item)
                                        } else LibraryRepository.delete(this@MainActivity, item)
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

    private fun setLanguage(language: String) {
        getSharedPreferences("appearance", MODE_PRIVATE)
            .edit()
            .putString("language", language)
            .apply()
        if (Build.VERSION.SDK_INT >= 33)
            getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(language)
        recreate()
    }
}
