package radio.ks3ckc.sstvaf.theme

import android.app.Activity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Build the Material3 [ColorScheme] from the currently-active palette. Called
 * inside [SstvAfTheme] (not a top-level val) so it re-reads [activePalette] and
 * tracks the snapshot read — switching themes rebuilds the scheme live.
 */
private fun ft8afColorScheme(): ColorScheme {
    val base = if (activePalette.bgApp.isLight()) {
        lightColorScheme()
    } else {
        darkColorScheme()
    }
    return base.copy(
        primary = Accent,
        onPrimary = BgApp,
        primaryContainer = AccentSoft,
        onPrimaryContainer = AccentGlow,

        secondary = Signal,
        onSecondary = BgApp,
        secondaryContainer = SignalSoft,
        onSecondaryContainer = Signal,

        tertiary = StatusNew,
        onTertiary = BgApp,

        background = BgApp,
        onBackground = TextPrimary,

        surface = BgSurface,
        onSurface = TextPrimary,
        surfaceVariant = BgSurface2,
        onSurfaceVariant = TextMuted,
        surfaceTint = Accent,

        outline = Border,
        outlineVariant = BorderStrong,

        error = StatusBad,
        onError = TextPrimary,
        errorContainer = Color(0x24EF4444),
        onErrorContainer = StatusBad,

        inverseSurface = TextPrimary,
        inverseOnSurface = BgApp,
        inversePrimary = Accent,

        scrim = Color(0xCC000000),
    )
}

/**
 * True when a color is light enough that dark foreground is appropriate. Uses
 * Compose's [luminance] (WCAG relative luminance, with sRGB gamma decoding)
 * rather than a raw RGB-weighted average, so near-mid backgrounds classify
 * correctly.
 */
private fun Color.isLight(): Boolean = luminance() > 0.5f

// Additional semantic colors not in Material3 scheme. Getters (not captured
// vals) so they always reflect the active palette.
object SstvAfColors {
    val bgApp: Color get() = BgApp
    val bgSurface: Color get() = BgSurface
    val bgSurface2: Color get() = BgSurface2
    val bgSurface3: Color get() = BgSurface3
    val bgElev: Color get() = BgElev

    val border: Color get() = Border
    val borderStrong: Color get() = BorderStrong
    val borderAmber: Color get() = BorderAmber

    val textPrimary: Color get() = TextPrimary
    val textMuted: Color get() = TextMuted
    val textFaint: Color get() = TextFaint
    val textDim: Color get() = TextDim

    val accent: Color get() = Accent
    val accentSoft: Color get() = AccentSoft
    val accentGlow: Color get() = AccentGlow

    val signal: Color get() = Signal
    val signalSoft: Color get() = SignalSoft

    val statusNew: Color get() = StatusNew
    val statusNeeded: Color get() = StatusNeeded
    val statusWorked: Color get() = StatusWorked
    val statusConfirmed: Color get() = StatusConfirmed
    val statusCq: Color get() = StatusCq
    val statusWarn: Color get() = StatusWarn
    val statusBad: Color get() = StatusBad
}

@Composable
fun SstvAfTheme(content: @Composable () -> Unit) {
    val colorScheme = ft8afColorScheme()
    val view = LocalView.current
    // Dark bar icons (isAppearanceLight* = true) for light themes; light bar
    // icons for dark themes — so the bars stay legible against the app's
    // background when they're transiently revealed under edge-to-edge.
    val lightBars = activePalette.bgApp.isLight()
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // window.statusBarColor / navigationBarColor are deprecated and no-ops
            // under edge-to-edge on Android 15. The bars are transparent; we only
            // set the bar-icon appearance here so they stay legible against the
            // app's background when the bars are transiently revealed.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SstvAfTypography,
        shapes = SstvAfShapes,
    ) {
        // Provide a slashed-zero default text style so bare Text() readouts
        // (frequencies, callsigns, grids drawn with GeistMonoFamily and no
        // explicit `style =`) inherit the `zero` feature too — the Typography
        // above only covers call sites that reference MaterialTheme.typography.
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.withSlashedZeros(),
            content = content,
        )
    }
}
