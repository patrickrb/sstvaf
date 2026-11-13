package radio.ks3ckc.sstvaf.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks the compact-vs-rail decision that drives the app shell (issue #20).
 * Uses representative real device widths so a future tweak to the threshold has
 * to consciously re-answer "does a phone in portrait still get the bottom bar,
 * and does the Redmi Redpad 2 get the rail in both orientations?".
 */
class AdaptiveShellTest {

    @Test
    fun phonePortrait_usesBottomBar() {
        // Typical compact phone widths (Pixel-class ~360-411dp portrait).
        assertThat(AdaptiveShell.useNavigationRail(360)).isFalse()
        assertThat(AdaptiveShell.useNavigationRail(411)).isFalse()
    }

    @Test
    fun phoneLandscape_usesRail() {
        // Same phones turned sideways expose ~640-820dp of width, where the
        // short canvas benefits most from moving nav off the bottom edge.
        assertThat(AdaptiveShell.useNavigationRail(640)).isTrue()
        assertThat(AdaptiveShell.useNavigationRail(820)).isTrue()
    }

    @Test
    fun tablet_usesRailInBothOrientations() {
        // Redmi Redpad 2 (11", ~800dp portrait / ~1280dp landscape) — the device
        // called out in issue #20 — is a rail in either orientation.
        assertThat(AdaptiveShell.useNavigationRail(800)).isTrue()
        assertThat(AdaptiveShell.useNavigationRail(1280)).isTrue()
    }

    @Test
    fun thresholdBoundary_isInclusiveAt600dp() {
        assertThat(AdaptiveShell.useNavigationRail(AdaptiveShell.RAIL_MIN_WIDTH_DP - 1)).isFalse()
        assertThat(AdaptiveShell.useNavigationRail(AdaptiveShell.RAIL_MIN_WIDTH_DP)).isTrue()
        assertThat(AdaptiveShell.useNavigationRail(AdaptiveShell.RAIL_MIN_WIDTH_DP + 1)).isTrue()
    }

    @Test
    fun degenerateWidths_defaultToBottomBar() {
        // A zero/negative width (rare config-race reading) must not crash or
        // flip to the rail — the compact bottom bar is the safe fallback.
        assertThat(AdaptiveShell.useNavigationRail(0)).isFalse()
        assertThat(AdaptiveShell.useNavigationRail(-1)).isFalse()
    }
}
