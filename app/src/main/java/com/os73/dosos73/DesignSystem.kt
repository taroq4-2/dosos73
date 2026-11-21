package com.os73.dosos73

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Design tokens. Every colour on screen comes from here; screens never hold literal colours.
 * Dark is the reference look (deep navy, elegant cards, interactive teal); Light keeps the same
 * hierarchy with AA contrast (checked in `DesignContrastTest`).
 */
@Immutable
data class DososPalette(
    val background: Color,
    val surface: Color,
    val raised: Color,
    val line: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val text: Color,
    val secondary: Color,
    val muted: Color,
    val error: Color,
    val success: Color,
    val warning: Color,
    val video: Color,
    val audio: Color,
    val document: Color,
    val app: Color,
    val archive: Color,
    val image: Color,
    val program: Color,
    val dark: Boolean,
) {
    fun category(category: FileCategory): Color = when (category) {
        FileCategory.VIDEO -> video
        FileCategory.AUDIO -> audio
        FileCategory.DOCUMENT -> document
        FileCategory.APP -> app
        FileCategory.ARCHIVE -> archive
        FileCategory.IMAGE -> image
        FileCategory.PROGRAM -> program
        FileCategory.OTHER -> muted
    }
}

internal object DososPalettes {
    val Dark = DososPalette(
        background = Color(0xFF0B1929), surface = Color(0xFF14273A), raised = Color(0xFF1C3349),
        line = Color(0xFF26425B), accent = Color(0xFF42D9CC), accentSoft = Color(0xFF1E5C61),
        onAccent = Color(0xFF04201F), text = Color(0xFFE4F5F4), secondary = Color(0xFFA3BCC6),
        muted = Color(0xFF8AA3AF), error = Color(0xFFFF7A8A), success = Color(0xFF4ADE9B),
        warning = Color(0xFFF5B547), video = Color(0xFF3B82F6), audio = Color(0xFF8B5CF6),
        document = Color(0xFFE5484D), app = Color(0xFF3DDC84), archive = Color(0xFFF59E0B),
        image = Color(0xFFEC4899), program = Color(0xFF64748B), dark = true,
    )
    val Light = DososPalette(
        background = Color(0xFFF2F7F9), surface = Color(0xFFFFFFFF), raised = Color(0xFFE6EFF3),
        line = Color(0xFFCFDDE5), accent = Color(0xFF0A7068), accentSoft = Color(0xFFCDEDEA),
        onAccent = Color(0xFFFFFFFF), text = Color(0xFF0B1929), secondary = Color(0xFF3D5466),
        muted = Color(0xFF566C7D), error = Color(0xFFB3261E), success = Color(0xFF15803D),
        warning = Color(0xFFB45309), video = Color(0xFF2563EB), audio = Color(0xFF7C3AED),
        document = Color(0xFFD02F36), app = Color(0xFF16A34A), archive = Color(0xFFD97706),
        image = Color(0xFFDB2777), program = Color(0xFF475569), dark = false,
    )
}

internal val LocalPalette = staticCompositionLocalOf { DososPalettes.Dark }

/** Back-compatible token names used across existing screens; values follow the active palette. */
internal object Neon {
    val Background: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.background
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface
    val Raised: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.raised
    val Cyan: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
    val Blue: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.video
    val ActionBlue: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
    val Purple: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.audio
    val Text: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.text
    val Secondary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.secondary
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
    val Line: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.line
    val Brand: Brush @Composable @ReadOnlyComposable get() = LocalPalette.current.let {
        Brush.linearGradient(listOf(it.accent, it.accent.copy(alpha = 0.75f).compositeOver(it.video)))
    }
    val Radius = RoundedCornerShape(18.dp)
}

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(red * a + background.red * (1 - a), green * a + background.green * (1 - a),
        blue * a + background.blue * (1 - a), 1f)
}

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Appearance choices; observable so a change re-themes the running UI immediately. */
internal object AppearancePreferences {
    private const val FILE = "appearance"
    private val modeState = MutableStateFlow<ThemeMode?>(null)
    private val dynamicState = MutableStateFlow<Boolean?>(null)

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun mode(context: Context): StateFlow<ThemeMode?> {
        if (modeState.value == null) modeState.value =
            runCatching { ThemeMode.valueOf(prefs(context).getString("mode", null) ?: "") }.getOrDefault(ThemeMode.DARK)
        return modeState.asStateFlow()
    }

    fun dynamicColor(context: Context): StateFlow<Boolean?> {
        if (dynamicState.value == null) dynamicState.value = prefs(context).getBoolean("dynamic", false)
        return dynamicState.asStateFlow()
    }

    fun setMode(context: Context, mode: ThemeMode) {
        check(prefs(context).edit().putString("mode", mode.name).commit())
        modeState.value = mode
    }

    fun setDynamicColor(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean("dynamic", enabled).commit())
        dynamicState.value = enabled
    }

    val dynamicSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}

/** Resolves the palette for the current preferences and system state. */
@Composable
internal fun rememberPalette(): Pair<DososPalette, ColorScheme> {
    val context = LocalContext.current
    val mode by AppearancePreferences.mode(context).collectAsState()
    val dynamic by AppearancePreferences.dynamicColor(context).collectAsState()
    val dark = when (mode ?: ThemeMode.DARK) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val base = if (dark) DososPalettes.Dark else DososPalettes.Light
    if (dynamic == true && AppearancePreferences.dynamicSupported) {
        val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        val palette = base.copy(
            background = scheme.background, surface = scheme.surfaceContainer, raised = scheme.surfaceContainerHigh,
            line = scheme.outlineVariant, accent = scheme.primary, accentSoft = scheme.primaryContainer,
            onAccent = scheme.onPrimary, text = scheme.onSurface, secondary = scheme.onSurfaceVariant,
            muted = scheme.onSurfaceVariant, error = scheme.error,
        )
        return palette to scheme
    }
    return base to schemeOf(base)
}

internal fun schemeOf(p: DososPalette): ColorScheme {
    val onError = if (p.dark) Color(0xFF3B0A10) else Color.White
    return if (p.dark) darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.accentSoft, onPrimaryContainer = p.text,
        secondary = p.audio, onSecondary = Color.White, secondaryContainer = p.raised, onSecondaryContainer = p.text,
        tertiary = p.app, onTertiary = Color.Black, tertiaryContainer = p.raised, onTertiaryContainer = p.text,
        background = p.background, onBackground = p.text, surface = p.surface, onSurface = p.text,
        surfaceVariant = p.raised, onSurfaceVariant = p.secondary, surfaceTint = p.accent,
        error = p.error, onError = onError, outline = p.muted, outlineVariant = p.line,
        surfaceContainerLowest = p.background, surfaceContainerLow = p.background, surfaceContainer = p.surface,
        surfaceContainerHigh = p.raised, surfaceContainerHighest = p.raised,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.accentSoft, onPrimaryContainer = p.text,
        secondary = p.audio, onSecondary = Color.White, secondaryContainer = p.raised, onSecondaryContainer = p.text,
        tertiary = p.app, onTertiary = Color.White, tertiaryContainer = p.raised, onTertiaryContainer = p.text,
        background = p.background, onBackground = p.text, surface = p.surface, onSurface = p.text,
        surfaceVariant = p.raised, onSurfaceVariant = p.secondary, surfaceTint = p.accent,
        error = p.error, onError = onError, outline = p.muted, outlineVariant = p.line,
        surfaceContainerLowest = p.surface, surfaceContainerLow = p.background, surfaceContainer = p.surface,
        surfaceContainerHigh = p.raised, surfaceContainerHighest = p.raised,
    )
}

@Composable
internal fun DososTheme(content: @Composable () -> Unit) {
    val family = FontFamily(
        Font(R.font.tajawal_regular, FontWeight.Normal),
        Font(R.font.tajawal_medium, FontWeight.Medium),
        Font(R.font.tajawal_bold, FontWeight.Bold),
    )
    val (palette, scheme) = rememberPalette()
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ),
            typography = Typography(
                headlineMedium = TextStyle(fontFamily = family, fontSize = 26.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
                headlineSmall = TextStyle(fontFamily = family, fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
                labelLarge = TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
                titleLarge = TextStyle(fontFamily = family, fontSize = 21.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
                titleMedium = TextStyle(fontFamily = family, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
                titleSmall = TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
                bodyLarge = TextStyle(fontFamily = family, fontSize = 16.sp, lineHeight = 24.sp),
                bodyMedium = TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = 22.sp),
                bodySmall = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 18.sp),
                labelSmall = TextStyle(fontFamily = family, fontSize = 11.sp, lineHeight = 17.sp),
                labelMedium = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
            ),
            content = content,
        )
    }
}
