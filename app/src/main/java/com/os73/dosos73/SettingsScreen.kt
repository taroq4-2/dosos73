package com.os73.dosos73

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Grouped settings (Downloads, Network, Notifications, Appearance and language, Privacy and
 * security, Tools, About). Every row changes a stored preference or opens a real screen.
 */
@Composable
internal fun SettingsScreen(customFolder: Boolean, onLanguage: (String) -> Unit,
    onFolder: () -> Unit, onDefaultFolder: () -> Unit,
    onPrivacy: () -> Unit, onBatch: () -> Unit, onAbout: () -> Unit, onQuick: () -> Unit, onOffline: () -> Unit = {}) {
    val palette = LocalPalette.current
    Page {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium)

        GroupTitle(stringResource(R.string.group_downloads))
        SurfaceCard(padding = 8.dp) {
            ListRow(Glyph.FOLDER, stringResource(R.string.destination),
                stringResource(if (customFolder) R.string.custom_folder else R.string.default_folder), onFolder,
                Modifier.testTag("destination"))
            if (customFolder) TextButton(onDefaultFolder, Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.default_folder))
            }
            FreeSpaceRow()
            ListRow(Glyph.LAYERS, stringResource(R.string.batch_title), null, onBatch, Modifier.testTag("batch"),
                tint = palette.audio)
            ListRow(Glyph.INFO, stringResource(R.string.download_behavior), stringResource(R.string.pause_explanation),
                null, tint = palette.muted)
        }

        GroupTitle(stringResource(R.string.group_network))
        NetworkControls()
        SharedLinkControls()
        SurfaceCard(padding = 8.dp) {
            ListRow(Glyph.LINK, stringResource(R.string.quick_title), null, onQuick, Modifier.testTag("quick_settings"))
        }

        GroupTitle(stringResource(R.string.group_notifications))
        NotificationRow()

        GroupTitle(stringResource(R.string.group_appearance))
        AppearanceControls(onLanguage)

        GroupTitle(stringResource(R.string.group_privacy))
        SurfaceCard(padding = 8.dp) {
            ListRow(Glyph.SHIELD, stringResource(R.string.privacy_title), null, onPrivacy, Modifier.testTag("privacy"),
                tint = palette.success)
        }

        GroupTitle(stringResource(R.string.group_tools))
        SurfaceCard(padding = 8.dp) {
            ListRow(Glyph.AUDIO, stringResource(R.string.offline_convert), null, onOffline,
                Modifier.testTag("offline_settings"), tint = palette.audio)
        }

        GroupTitle(stringResource(R.string.group_about))
        SurfaceCard(padding = 8.dp) {
            ListRow(Glyph.INFO, stringResource(R.string.about), BuildConfig.VERSION_NAME, onAbout, Modifier.testTag("about"))
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.notice), style = MaterialTheme.typography.bodySmall, color = palette.secondary)
    }
}

@Composable
private fun FreeSpaceRow() {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var free by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        free = withContext(Dispatchers.IO) {
            runCatching { android.os.StatFs((context.getExternalFilesDir(null) ?: context.filesDir).path).availableBytes }
                .getOrNull()
        }
    }
    free?.let { ListRow(Glyph.STORAGE, stringResource(R.string.free_space, bytesLabel(it, locale)), null, null,
        tint = LocalPalette.current.archive) }
}

@Composable
private fun NotificationRow() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    // Re-read after returning from system settings.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    SurfaceCard(padding = 8.dp) {
        ListRow(Glyph.BELL, stringResource(R.string.notifications_open),
            stringResource(if (enabled) R.string.notifications_on else R.string.notifications_off),
            {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            Modifier.testTag("notification_settings"),
            tint = if (enabled) LocalPalette.current.success else LocalPalette.current.warning)
    }
}

@Composable
private fun AppearanceControls(onLanguage: (String) -> Unit) {
    val context = LocalContext.current
    val mode by AppearancePreferences.mode(context).collectAsState()
    val dynamic by AppearancePreferences.dynamicColor(context).collectAsState()
    val language = LocalConfiguration.current.locales[0].language
    var failed by remember { mutableStateOf(false) }
    SurfaceCard {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Symbol(Glyph.PALETTE, color = Neon.Cyan)
            Text(stringResource(R.string.theme_title), style = MaterialTheme.typography.titleSmall)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.DARK to R.string.theme_dark,
                ThemeMode.LIGHT to R.string.theme_light).forEach { (value, label) ->
                NeonChip(mode == value, {
                    failed = runCatching { AppearancePreferences.setMode(context, value) }.isFailure
                }, label = { Text(stringResource(label)) },
                    modifier = Modifier.heightIn(min = 40.dp).testTag("theme_${value.name.lowercase()}"))
            }
        }
        if (AppearancePreferences.dynamicSupported) {
            val label = stringResource(R.string.dynamic_color)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.dynamic_color_hint), style = MaterialTheme.typography.bodySmall,
                        color = Neon.Secondary)
                }
                Switch(dynamic == true, {
                    failed = runCatching { AppearancePreferences.setDynamicColor(context, it) }.isFailure
                }, Modifier.testTag("dynamic_color").semantics { contentDescription = label })
            }
        }
        HorizontalDivider(color = Neon.Line.copy(alpha = 0.5f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Symbol(Glyph.GLOBE, color = Neon.Cyan)
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeonChip(language == "ar", { onLanguage("ar") }, label = { Text(stringResource(R.string.language_ar)) },
                modifier = Modifier.heightIn(min = 40.dp))
            NeonChip(language == "en", { onLanguage("en") }, label = { Text(stringResource(R.string.language_en)) },
                modifier = Modifier.heightIn(min = 40.dp))
        }
        if (failed) Text(stringResource(R.string.error_storage), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SharedLinkControls() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(LinkPreferences.autoAnalyzeShared(context)) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val label = stringResource(R.string.shared_auto)
    SurfaceCard {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.shared_auto), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.shared_auto_hint), color = Neon.Secondary, style = MaterialTheme.typography.bodySmall)
            }
            Switch(enabled, onCheckedChange = { value ->
                saving = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { LinkPreferences.setAutoAnalyzeShared(context, value) }
                        enabled = value
                        failed = false
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                    finally { saving = false }
                }
            }, enabled = !saving, modifier = Modifier.testTag("shared_auto").semantics { contentDescription = label })
        }
        if (failed) Text(stringResource(R.string.error_storage), color = MaterialTheme.colorScheme.error)
    }
}

/** What dosos73 can download, stated honestly (home row "Supported links"). */
@Composable
internal fun SupportedLinksScreen() {
    val palette = LocalPalette.current
    Page {
        Text(stringResource(R.string.row_supported), style = MaterialTheme.typography.headlineMedium)
        listOf(
            Triple(Glyph.FILE, R.string.supported_direct_title, R.string.supported_direct_body) to palette.video,
            Triple(Glyph.LAYERS, R.string.supported_github_title, R.string.supported_github_body) to palette.text,
            Triple(Glyph.APP, R.string.supported_fdroid_title, R.string.supported_fdroid_body) to palette.app,
            Triple(Glyph.VIDEO, R.string.supported_media_title, R.string.supported_media_body) to palette.document,
            Triple(Glyph.SHIELD, R.string.supported_limits_title, R.string.supported_limits_body) to palette.warning,
        ).forEach { (content, tint) ->
            val (glyph, title, body) = content
            SurfaceCard(padding = 8.dp) {
                ListRow(glyph, stringResource(title), stringResource(body), null, tint = tint)
            }
        }
        Text(stringResource(R.string.notice), style = MaterialTheme.typography.bodySmall, color = palette.secondary)
    }
}

/** Extra tools reachable from Home: offline audio extraction, multi-link downloads, quick download. */
@Composable
internal fun ToolsScreen(onOffline: () -> Unit, onBatch: () -> Unit, onQuick: () -> Unit) {
    val palette = LocalPalette.current
    Page {
        Text(stringResource(R.string.row_tools), style = MaterialTheme.typography.headlineMedium)
        SurfaceCard(padding = 8.dp) {
            ListRow(Glyph.AUDIO, stringResource(R.string.offline_convert), null, onOffline, tint = palette.audio)
            ListRow(Glyph.LAYERS, stringResource(R.string.batch_title), null, onBatch, tint = palette.video)
            ListRow(Glyph.LINK, stringResource(R.string.quick_title), null, onQuick)
        }
    }
}
