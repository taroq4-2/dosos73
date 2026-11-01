package com.os73.dosos73

import android.app.LocaleManager
import android.app.KeyguardManager
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    private var sharedGeneration by mutableIntStateOf(0)
    private var quickSettingsGeneration by mutableIntStateOf(0)
    private var quickPending = false
    private var resumed = false

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
        else {
            model.restoreInput(savedInstanceState.getString("dosos.url"), savedInstanceState.getString("dosos.shared"))
            quickPending = savedInstanceState.getBoolean("dosos.quickPending")
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.batchSaving.collect { consumeQuickRequest() }
            }
        }
        setContent { DososTheme { AppScreen(model, sharedGeneration, quickSettingsGeneration) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Clipboard-derived input is retained by the ViewModel for rotation, not persisted in a Bundle.
        if (!model.state.value.quick) outState.putString("dosos.url", model.state.value.url)
        outState.putBoolean("dosos.quickPending", quickPending)
        outState.putString("dosos.shared", model.state.value.pendingShared)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        consumeQuickRequest()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) consumeQuickRequest()
    }

    private fun consumeQuickRequest() {
        if (!quickPending || !resumed || !hasWindowFocus() ||
            getSystemService(KeyguardManager::class.java).isDeviceLocked) return
        // Initial batch storage restoration is asynchronous; do not read until it completes.
        if (QuickLaunch.enabled(this) && model.quickInitializing()) return
        quickPending = false
        if (!QuickLaunch.enabled(this)) {
            quickSettingsGeneration++
            Toast.makeText(this, R.string.quick_disabled, Toast.LENGTH_SHORT).show()
            return
        }
        if (!model.quickAvailable()) {
            Toast.makeText(this, R.string.quick_busy, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            model.openQuick(QuickLaunch.copiedUrl(this))
            sharedGeneration++
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.quick_clipboard_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun receive(intent: Intent?) {
        if (QuickLaunch.requested(this, intent)) {
            // Ignore extras/data: an exported launcher entry is not proof of a physical gesture.
            if (!model.state.value.quick) quickPending = true
            consumeQuickRequest()
            return
        }
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            quickPending = false
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(model::receive)
            intent.removeExtra(Intent.EXTRA_TEXT)
            sharedGeneration++
        }
    }

    internal fun setLanguage(language: String) {
        getSharedPreferences("appearance", MODE_PRIVATE)
            .edit()
            .putString("language", language)
            .apply()
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language)
        } else recreate()
    }
}
