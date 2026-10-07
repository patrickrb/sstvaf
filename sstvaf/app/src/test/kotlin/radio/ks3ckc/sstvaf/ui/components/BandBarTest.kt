package radio.ks3ckc.sstvaf.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for the band bar's pure logic: the main line's format and which
 * tabs carry the bar at all. No Android runtime needed.
 */
class BandBarTest {

    // ----- label -------------------------------------------------------------

    @Test
    fun label_isBandThenFrequencyWithUnits() {
        // Band leads: this control is the band selector, so the band is the
        // headline and the exact dial is the detail. Unlike the old header
        // chip there is room for the unit.
        assertThat(bandBarLabel(14_230_000L, "20m")).isEqualTo("20m · 14.230 MHz")
    }

    @Test
    fun label_keepsKhzResolution() {
        // Three decimals: SSTV calling frequencies are specified to the kHz
        // and an operator recognises "7.171" at a glance.
        assertThat(bandBarLabel(7_171_000L, "40m")).isEqualTo("40m · 7.171 MHz")
        assertThat(bandBarLabel(3_845_000L, "80m")).isEqualTo("80m · 3.845 MHz")
        assertThat(bandBarLabel(28_680_000L, "10m")).isEqualTo("10m · 28.680 MHz")
    }

    @Test
    fun label_roundsToNearestKhz() {
        // A dial reported with Hz precision (CAT readback) must not spill
        // extra digits into the line.
        assertThat(bandBarLabel(14_230_400L, "20m")).isEqualTo("20m · 14.230 MHz")
        assertThat(bandBarLabel(14_230_600L, "20m")).isEqualTo("20m · 14.231 MHz")
    }

    @Test
    fun label_showsBareFrequencyWhenBandUnknown() {
        // Out-of-band dial: no band name, so no leading blank or dangling
        // separator — just the honest frequency.
        assertThat(bandBarLabel(14_230_000L, "")).isEqualTo("14.230 MHz")
        assertThat(bandBarLabel(14_230_000L, "   ")).isEqualTo("14.230 MHz")
    }

    // ----- which tabs carry the bar ------------------------------------------

    @Test
    fun bar_showsOnlyOnTheOperatingTabs() {
        // Receive and Send care what the dial says; Gallery, Logbook and
        // Settings do not, and the bar would just push their content down.
        assertThat(showsBandBar(SstvTab.RX)).isTrue()
        assertThat(showsBandBar(SstvTab.TX)).isTrue()
        assertThat(showsBandBar(SstvTab.GALLERY)).isFalse()
        assertThat(showsBandBar(SstvTab.LOG)).isFalse()
        assertThat(showsBandBar(SstvTab.SETTINGS)).isFalse()
    }
}
