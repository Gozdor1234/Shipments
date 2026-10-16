package com.nate.shipments

import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

/** Appearance settings, observable so the whole app re-themes the moment one changes. */
class Appearance private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    /** "system", "light", "dark" or "amoled". */
    var themeMode by mutableStateOf(prefs.getString(KEY_THEME, "system") ?: "system")
        private set
    var modernStyle by mutableStateOf(prefs.getBoolean(KEY_MODERN, false))
        private set
    var matchPhoneColors by mutableStateOf(prefs.getBoolean(KEY_DYNAMIC, true))
        private set
    var customColorsOn by mutableStateOf(prefs.getBoolean(KEY_CUSTOM_ON, false))
        private set
    var customColors by mutableStateOf(readCustom())
        private set

    fun updateThemeMode(mode: String) { themeMode = mode; prefs.edit().putString(KEY_THEME, mode).apply() }
    fun updateModernStyle(on: Boolean) { modernStyle = on; prefs.edit().putBoolean(KEY_MODERN, on).apply() }
    fun updateMatchPhoneColors(on: Boolean) { matchPhoneColors = on; prefs.edit().putBoolean(KEY_DYNAMIC, on).apply() }
    fun updateCustomColorsOn(on: Boolean) { customColorsOn = on; prefs.edit().putBoolean(KEY_CUSTOM_ON, on).apply() }
    fun updateCustomColor(slot: ColorSlot, argb: Int?) {
        customColors = if (argb == null) customColors - slot else customColors + (slot to argb)
        val e = prefs.edit()
        if (argb == null) e.remove(KEY_CUSTOM_PREFIX + slot.key) else e.putInt(KEY_CUSTOM_PREFIX + slot.key, argb)
        e.apply()
    }
    fun resetCustomColors() {
        customColors = emptyMap()
        val e = prefs.edit()
        ColorSlot.entries.forEach { e.remove(KEY_CUSTOM_PREFIX + it.key) }
        e.apply()
    }

    private fun readCustom(): Map<ColorSlot, Int> = ColorSlot.entries
        .filter { prefs.contains(KEY_CUSTOM_PREFIX + it.key) }
        .associateWith { prefs.getInt(KEY_CUSTOM_PREFIX + it.key, 0) }

    companion object {
        private const val KEY_THEME = "theme_mode"
        private const val KEY_MODERN = "modern_style"
        private const val KEY_DYNAMIC = "match_phone_colors"
        private const val KEY_CUSTOM_ON = "custom_colors_on"
        private const val KEY_CUSTOM_PREFIX = "custom_color_"

        @Volatile private var instance: Appearance? = null
        fun get(context: Context): Appearance =
            instance ?: synchronized(this) { instance ?: Appearance(context).also { instance = it } }
    }
}

/** Wallpaper-based "Material You" colors exist on Android 12 (API 31) and newer. */
val supportsPhoneColors: Boolean get() = Build.VERSION.SDK_INT >= 31

/** True black backgrounds so OLED pixels switch off; cards stay just visible. */
private fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF080808),
    surfaceContainer = Color(0xFF0E0E0E),
    surfaceContainerHigh = Color(0xFF151515),
    surfaceContainerHighest = Color(0xFF1C1C1C),
    surfaceVariant = Color(0xFF1A1A1A),
)

/**
 * Dark mode surfaces in slate gray-blue instead of near-black. Accent colors (from the phone
 * palette or the defaults) are kept; only backgrounds, cards and outlines change.
 */
private fun ColorScheme.toGrayBlue(): ColorScheme = copy(
    background = Color(0xFF1B2230),
    surface = Color(0xFF1B2230),
    surfaceDim = Color(0xFF161C28),
    surfaceBright = Color(0xFF3A4556),
    surfaceContainerLowest = Color(0xFF141A24),
    surfaceContainerLow = Color(0xFF1F2735),
    surfaceContainer = Color(0xFF232C3B),
    surfaceContainerHigh = Color(0xFF2A3445),
    surfaceContainerHighest = Color(0xFF313C4F),
    surfaceVariant = Color(0xFF354154),
    onSurfaceVariant = Color(0xFFBFC8D6),
    outline = Color(0xFF8A94A6),
    outlineVariant = Color(0xFF3F4A5C),
)

/** True when the app is actually showing dark colors (follows the theme setting, not just the phone). */
val LocalDarkUi = staticCompositionLocalOf { false }

@Composable
fun ShipmentsTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val prefs = Appearance.get(ctx)
    val mode = prefs.themeMode
    val dark = when (mode) {
        "light" -> false
        "dark", "amoled" -> true
        else -> isSystemInDarkTheme()
    }
    val base = when {
        prefs.matchPhoneColors && supportsPhoneColors ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFF2A93B))
        else -> lightColorScheme(primary = Color(0xFFB8741A))
    }
    val themed = when {
        mode == "amoled" -> base.toAmoled()
        dark -> base.toGrayBlue()
        else -> base
    }
    val custom = if (prefs.customColorsOn) prefs.customColors else emptyMap()
    val modern = prefs.modernStyle
    // Modern light mode sits on a soft gray-blue page so the glass edges and shadows show.
    val modernBase = if (modern && !dark) {
        val page = if (prefs.matchPhoneColors && supportsPhoneColors) {
            Color(ColorMath.mix(themed.background.toArgb(), 0xFF1E3A64.toInt(), 0.07f))
        } else ModernLightBackground
        themed.copy(background = page, surface = page, surfaceContainer = page, surfaceContainerLow = page)
    } else themed
    val scheme = modernBase.withCustom(custom)
    val bar = custom[ColorSlot.Bars]
    val extra = ExtraColors(bar = bar?.let { Color(it) }, onBar = bar?.let { Color(ColorMath.onColorFor(it)) })

    // Status/navigation bar icons follow the actual background, so they stay readable
    // when the app's mode or a custom background differs from the phone's setting.
    val barsDark = ColorMath.isDark(scheme.background.toArgb())
    val activity = ctx as? ComponentActivity
    LaunchedEffect(barsDark, activity) {
        activity?.enableEdgeToEdge(
            statusBarStyle = if (barsDark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = if (barsDark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
    }

    CompositionLocalProvider(LocalExtraColors provides extra, LocalModern provides modern, LocalDarkUi provides barsDark) {
        MaterialTheme(colorScheme = scheme) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}

/** Colors outside Material's scheme: the optional custom top bar color. */
data class ExtraColors(val bar: Color?, val onBar: Color?)

val LocalExtraColors = staticCompositionLocalOf { ExtraColors(null, null) }

/**
 * Layers the user's custom colors over the theme. Anything not customized keeps the theme's
 * color, except where that would break readability (text on a new background, text on buttons),
 * which is derived automatically.
 */
private fun ColorScheme.withCustom(c: Map<ColorSlot, Int>): ColorScheme {
    if (c.isEmpty()) return this
    var s = this
    c[ColorSlot.Background]?.let { bg ->
        val e = { amt: Float -> Color(ColorMath.elevate(bg, amt)) }
        s = s.copy(
            background = Color(bg), surface = Color(bg), surfaceDim = Color(bg), surfaceContainerLowest = Color(bg),
            surfaceContainerLow = e(0.04f), surfaceContainer = e(0.07f), surfaceContainerHigh = e(0.10f),
            surfaceContainerHighest = e(0.13f), surfaceVariant = e(0.16f), surfaceBright = e(0.20f),
        )
        // If text wasn't customized and no longer reads on this background, switch it to black or white.
        if (ColorSlot.Text !in c && ColorMath.contrast(s.onSurface.toArgb(), bg) < 4.5) {
            val on = ColorMath.onColorFor(bg)
            s = s.copy(onBackground = Color(on), onSurface = Color(on))
            if (ColorSlot.SubText !in c) s = s.copy(onSurfaceVariant = Color(ColorMath.mix(on, bg, 0.3f)))
        }
    }
    c[ColorSlot.Cards]?.let { card ->
        val bg = s.background.toArgb()
        s = s.copy(
            surfaceContainerLow = Color(ColorMath.mix(card, bg, 0.5f)),
            surfaceContainer = Color(card), surfaceContainerHigh = Color(card), surfaceContainerHighest = Color(card),
            surfaceVariant = Color(ColorMath.elevate(card, 0.06f)),
        )
    }
    c[ColorSlot.Accent]?.let { a ->
        val container = ColorMath.mix(a, s.background.toArgb(), 0.65f)
        s = s.copy(
            primary = Color(a), onPrimary = Color(ColorMath.onColorFor(a)), surfaceTint = Color(a),
            primaryContainer = Color(container), onPrimaryContainer = Color(ColorMath.onColorFor(container)),
        )
    }
    c[ColorSlot.Text]?.let { t -> s = s.copy(onBackground = Color(t), onSurface = Color(t)) }
    c[ColorSlot.SubText]?.let { t -> s = s.copy(onSurfaceVariant = Color(t)) }
    c[ColorSlot.Lines]?.let { l -> s = s.copy(outline = Color(l), outlineVariant = Color(l)) }
    return s
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun shipmentsTopBarColors(): TopAppBarColors {
    val e = LocalExtraColors.current
    val bar = e.bar
    val on = e.onBar
    return if (bar == null || on == null) TopAppBarDefaults.topAppBarColors()
    else TopAppBarDefaults.topAppBarColors(
        containerColor = bar,
        scrolledContainerColor = bar,
        titleContentColor = on,
        navigationIconContentColor = on,
        actionIconContentColor = on,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun shipmentsLargeTopBarColors(): TopAppBarColors {
    val e = LocalExtraColors.current
    val bar = e.bar
    val on = e.onBar
    return if (bar == null || on == null) TopAppBarDefaults.largeTopAppBarColors()
    else TopAppBarDefaults.largeTopAppBarColors(
        containerColor = bar,
        scrolledContainerColor = bar,
        titleContentColor = on,
        navigationIconContentColor = on,
        actionIconContentColor = on,
    )
}
