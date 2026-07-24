package radio.ks3ckc.sstvaf.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R

val GeistFamily: FontFamily = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

val GeistMonoFamily: FontFamily = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
    Font(R.font.geist_mono_bold, FontWeight.Bold),
)

/**
 * The OpenType `zero` feature: renders a *slashed zero*, so `0` is
 * unambiguous versus `O`. Both Geist and Geist Mono (the app's data typeface)
 * ship this feature. Enabling it everywhere disambiguates the alphanumeric
 * data amateur operators read constantly — callsigns, Maidenhead grids, and
 * frequencies — where `0`/`O` confusion is a real source of error (issue #32).
 */
const val SLASHED_ZERO_FEATURE = "zero"

/**
 * Merge one OpenType [feature] tag into an existing CSS-style
 * `font-feature-settings` string (comma-separated tags), preserving any tags
 * already present and never duplicating [feature]. A null/blank [existing]
 * yields just [feature].
 */
internal fun mergeFontFeature(existing: String?, feature: String): String {
    if (existing.isNullOrBlank()) return feature
    val tags = existing.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    return if (tags.any { it == feature }) existing else (tags + feature).joinToString(", ")
}

/** Copy of this style with the slashed-zero feature merged into its settings. */
internal fun TextStyle.withSlashedZeros(): TextStyle =
    copy(fontFeatureSettings = mergeFontFeature(fontFeatureSettings, SLASHED_ZERO_FEATURE))

/** Copy of this type scale with every style carrying the slashed-zero feature. */
internal fun Typography.withSlashedZeros(): Typography = copy(
    displayLarge = displayLarge.withSlashedZeros(),
    displayMedium = displayMedium.withSlashedZeros(),
    displaySmall = displaySmall.withSlashedZeros(),
    headlineLarge = headlineLarge.withSlashedZeros(),
    headlineMedium = headlineMedium.withSlashedZeros(),
    headlineSmall = headlineSmall.withSlashedZeros(),
    titleLarge = titleLarge.withSlashedZeros(),
    titleMedium = titleMedium.withSlashedZeros(),
    titleSmall = titleSmall.withSlashedZeros(),
    bodyLarge = bodyLarge.withSlashedZeros(),
    bodyMedium = bodyMedium.withSlashedZeros(),
    bodySmall = bodySmall.withSlashedZeros(),
    labelLarge = labelLarge.withSlashedZeros(),
    labelMedium = labelMedium.withSlashedZeros(),
    labelSmall = labelSmall.withSlashedZeros(),
)

// The Material type scale. Every style is post-processed by [withSlashedZeros]
// so styles referenced as `MaterialTheme.typography.X` get slashed zeros; the
// theme also provides a slashed-zero [androidx.compose.material3.LocalTextStyle]
// so bare `Text(fontFamily = GeistMonoFamily, …)` readouts inherit it too.
private val SstvAfTypographyBase = Typography(
    displayLarge = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        letterSpacing = (-0.02).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        letterSpacing = (-0.02).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        letterSpacing = (-0.01).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        letterSpacing = (-0.01).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        letterSpacing = 0.02.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.02.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.04.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.04.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 0.06.sp,
    ),
)

/** The app's Material type scale, with slashed zeros enabled on every style. */
val SstvAfTypography = SstvAfTypographyBase.withSlashedZeros()
