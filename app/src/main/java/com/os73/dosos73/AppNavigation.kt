package com.os73.dosos73

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Bottom navigation: Home / Downloads / Library / Settings (reference design). Follows the layout
 * direction, so Home sits on the right in Arabic. Page keys stay "files" and "more" for saved-state
 * and test compatibility; "more" now hosts the grouped settings.
 */
@Composable
internal fun AppNavigation(page: String, onPage: (String) -> Unit) {
    val palette = LocalPalette.current
    NavigationBar(
        containerColor = palette.surface, tonalElevation = 0.dp,
        modifier = Modifier.navigationBarsPadding().heightIn(
            min = (80 + 24 * (LocalConfiguration.current.fontScale - 1f).coerceAtLeast(0f)).dp),
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        val current = when (page) {
            "home", "downloads", "files" -> page
            "more", "settings", "privacy", "batch", "about", "contact", "quick", "offline", "supported" -> "more"
            else -> page
        }
        listOf(
            Triple("home", R.string.nav_home, Glyph.HOME),
            Triple("downloads", R.string.downloads, Glyph.DOWNLOAD),
            Triple("files", R.string.nav_library, Glyph.FOLDER),
            Triple("more", R.string.nav_settings, Glyph.SETTINGS),
        ).forEach { (key, label, icon) ->
            val selected = current == key
            NavigationBarItem(
                selected = selected,
                onClick = { onPage(key) },
                icon = { Symbol(icon, color = if (selected) palette.accent else palette.muted) },
                label = {
                    FitText(stringResource(label), MaterialTheme.typography.labelMedium)
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = palette.accentSoft, selectedTextColor = palette.accent,
                    unselectedTextColor = palette.muted),
                modifier = Modifier.testTag("nav_$key"),
            )
        }
    }
}
