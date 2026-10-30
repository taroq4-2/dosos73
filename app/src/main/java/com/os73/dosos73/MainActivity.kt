package com.os73.dosos73

import android.app.LocaleManager
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
        setContent { DososTheme { AppScreen(model, sharedGeneration) } }
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
