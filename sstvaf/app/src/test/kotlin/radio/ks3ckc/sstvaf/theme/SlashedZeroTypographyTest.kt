package radio.ks3ckc.sstvaf.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the slashed-zero typography wiring (issue #32): the `zero` OpenType
 * feature must reach every Material type-scale style so `0`/`O` never look
 * alike in callsigns, grids, and frequencies.
 *
 * No Robolectric: [TextStyle]/[androidx.compose.material3.Typography] are pure
 * Compose data holders, and [SLASHED_ZERO_FEATURE] is a plain string constant.
 */
class SlashedZeroTypographyTest {

    @Test
    fun `feature tag is the OpenType zero tag`() {
        assertThat(SLASHED_ZERO_FEATURE).isEqualTo("zero")
    }

    @Test
    fun `mergeFontFeature adds the feature when none present`() {
        assertThat(mergeFontFeature(null, SLASHED_ZERO_FEATURE)).isEqualTo("zero")
        assertThat(mergeFontFeature("", SLASHED_ZERO_FEATURE)).isEqualTo("zero")
        assertThat(mergeFontFeature("   ", SLASHED_ZERO_FEATURE)).isEqualTo("zero")
    }

    @Test
    fun `mergeFontFeature preserves existing tags`() {
        assertThat(mergeFontFeature("tnum", SLASHED_ZERO_FEATURE)).isEqualTo("tnum, zero")
        assertThat(mergeFontFeature("tnum, ss01", SLASHED_ZERO_FEATURE))
            .isEqualTo("tnum, ss01, zero")
    }

    @Test
    fun `mergeFontFeature never duplicates the feature`() {
        assertThat(mergeFontFeature("zero", SLASHED_ZERO_FEATURE)).isEqualTo("zero")
        assertThat(mergeFontFeature("tnum, zero", SLASHED_ZERO_FEATURE))
            .isEqualTo("tnum, zero")
    }

    @Test
    fun `withSlashedZeros adds the feature to a plain style`() {
        val style = TextStyle(fontSize = 12.sp)
        assertThat(style.withSlashedZeros().fontFeatureSettings).contains("zero")
    }

    @Test
    fun `withSlashedZeros keeps a pre-existing feature alongside zero`() {
        val style = TextStyle(fontSize = 12.sp, fontFeatureSettings = "tnum")
        val merged = style.withSlashedZeros().fontFeatureSettings
        assertThat(merged).contains("tnum")
        assertThat(merged).contains("zero")
    }

    @Test
    fun `withSlashedZeros leaves other style properties untouched`() {
        val style = TextStyle(fontSize = 17.sp, fontFamily = GeistMonoFamily)
        val slashed = style.withSlashedZeros()
        assertThat(slashed.fontSize).isEqualTo(17.sp)
        assertThat(slashed.fontFamily).isEqualTo(GeistMonoFamily)
    }

    @Test
    fun `every Material type-scale style carries the zero feature`() {
        val t = SstvAfTypography
        val styles = listOf(
            "displayLarge" to t.displayLarge,
            "displayMedium" to t.displayMedium,
            "displaySmall" to t.displaySmall,
            "headlineLarge" to t.headlineLarge,
            "headlineMedium" to t.headlineMedium,
            "headlineSmall" to t.headlineSmall,
            "titleLarge" to t.titleLarge,
            "titleMedium" to t.titleMedium,
            "titleSmall" to t.titleSmall,
            "bodyLarge" to t.bodyLarge,
            "bodyMedium" to t.bodyMedium,
            "bodySmall" to t.bodySmall,
            "labelLarge" to t.labelLarge,
            "labelMedium" to t.labelMedium,
            "labelSmall" to t.labelSmall,
        )
        for ((name, style) in styles) {
            assertThat(style.fontFeatureSettings).isNotNull()
            assertThat("$name=${style.fontFeatureSettings}")
                .contains(SLASHED_ZERO_FEATURE)
        }
    }

    @Test
    fun `applying the feature preserves the base scale sizing`() {
        // A spot-check that withSlashedZeros() copied rather than reset styles:
        // labelSmall keeps its 10sp size and its letter spacing from the base.
        assertThat(SstvAfTypography.labelSmall.fontSize).isEqualTo(10.sp)
        assertThat(SstvAfTypography.titleMedium.fontSize).isEqualTo(15.sp)
    }
}
