package com.os73.dosos73

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Presentation only: navigation state and side effects remain owned by AppScreen. */
@Composable
internal fun AppNavigation(page: String, onPage: (String) -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        NavigationBar(
            containerColor = Color(0xFF040E20), tonalElevation = 0.dp,
            modifier = Modifier.navigationBarsPadding().heightIn(
                min = (80 + 24 * (LocalConfiguration.current.fontScale - 1f).coerceAtLeast(0f)).dp),
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            listOf(
                Triple("home", R.string.nav_home, Glyph.HOME),
                Triple("downloads", R.string.downloads, Glyph.DOWNLOAD),
                Triple("files", R.string.nav_files, Glyph.FOLDER),
                Triple("more", R.string.more, Glyph.MORE),
            ).forEach { (key, label, icon) ->
                NavigationBarItem(
                    selected = page == key,
                    onClick = { onPage(key) },
                    icon = { Symbol(icon, color = if (page == key) Neon.Cyan else Neon.Muted) },
                    label = {
                        Text(stringResource(label), maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall)
                    },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = Color.Transparent, selectedTextColor = Neon.Cyan,
                        unselectedTextColor = Neon.Muted),
                    modifier = Modifier.testTag("nav_$key"),
                )
            }
        }
    }
}
