package com.os73.dosos73

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
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
        var page by rememberSaveable { mutableStateOf("home") }
        val savedPages = rememberSaveableStateHolder()
        BackHandler(page != "home") { page = if (page == "contact") "about" else "home" }
        LaunchedEffect(transfer.phase) { if (transfer.phase == Phase.FAILED) model.invalidateFormats() }
        val preferences = remember { getSharedPreferences("destination", MODE_PRIVATE) }
        var tree by remember { mutableStateOf(preferences.getString("tree", null)?.let(Uri::parse)) }
        fun releaseTree() {
            tree?.let { old -> runCatching { contentResolver.releasePersistableUriPermission(old,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (tree != uri) releaseTree()
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
        MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0874EE), onPrimary = Color.White,
            background = Color(0xFFF6F8FC), surface = Color.White, onBackground = Color(0xFF0E2035),
            onSurface = Color(0xFF0E2035), onSurfaceVariant = Color(0xFF53647D),
            surfaceVariant = Color(0xFFEDF1F7), surfaceContainerHighest = Color(0xFFEDF1F7), outlineVariant = Color(0xFFDCE4EF))) {
            savedPages.SaveableStateProvider(page) { when (page) {
                "about" -> AboutScreen({ page = "home" }, { page = "contact" })
                "contact" -> ContactScreen { page = "about" }
                else -> HomeScreen(screen, transfer, tree != null, model::edit, model::select, model::analyze,
                    model::cancelAnalysis, onPaste = {
                        getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)
                            ?.coerceToText(this@MainActivity)?.toString()?.let(model::receive)
                    }, onAbout = { page = "about" }, onFolder = { picker.launch(tree) },
                    onDefaultFolder = { releaseTree(); tree = null; preferences.edit().remove("tree").apply() },
                    onStart = { if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else start() },
                    onCancel = { startService(Intent(this@MainActivity, DownloadService::class.java).setAction("cancel")) },
                    onNewDownload = { Transfers.update(TransferState()); model.edit("") },
                    onOpen = { transfer.uri?.let { uri ->
                        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, transfer.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                        catch (_: android.content.ActivityNotFoundException) { Toast.makeText(this, R.string.error_open, Toast.LENGTH_LONG).show() }
                    } })
            } }
        }
    }
}

