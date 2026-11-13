package radio.ks3ckc.sstvaf.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Normalisation of the operator identity fields.
 *
 * These four values go into every log entry and out over the air on every
 * transmission, so the tests here are about what gets *stored*, not about what
 * the sheet looks like.
 */
class OperatorEditTest {

    // ----- callsign -----------------------------------------------------------

    @Test
    fun `a callsign is stored trimmed and upper-cased`() {
        // The log compares callsigns as strings, so a lower-case entry would
        // read as a different station from the same one typed in capitals.
        assertThat(normalizeCallsign("  k1af ")).isEqualTo("K1AF")
        assertThat(normalizeCallsign("k1af/p")).isEqualTo("K1AF/P")
    }

    @Test
    fun `an empty callsign stays empty rather than becoming whitespace`() {
        assertThat(normalizeCallsign("   ")).isEmpty()
    }

    // ----- grid ---------------------------------------------------------------

    @Test
    fun `a grid takes the conventional mixed case`() {
        assertThat(normalizeGrid("fn42ab")).isEqualTo("FN42ab")
        assertThat(normalizeGrid("FN42AB")).isEqualTo("FN42ab")
    }

    @Test
    fun `a four-character grid is left at four characters`() {
        // Padding a four-character locator to six would claim a precision the
        // operator never entered.
        assertThat(normalizeGrid("fn42")).isEqualTo("FN42")
        assertThat(normalizeGrid("fn42")).hasLength(4)
    }

    @Test
    fun `surrounding whitespace is dropped before the case rule applies`() {
        // Without the trim the leading space would count as the first
        // character and the field letters would come out lower-case.
        assertThat(normalizeGrid("  fn42  ")).isEqualTo("FN42")
    }

    @Test
    fun `an empty grid stays empty`() {
        assertThat(normalizeGrid("")).isEmpty()
        assertThat(normalizeGrid("  ")).isEmpty()
    }

    // ----- antenna ------------------------------------------------------------

    @Test
    fun `an antenna is trimmed but otherwise left alone`() {
        assertThat(normalizeAntenna("  EFHW 40-10m ")).isEqualTo("EFHW 40-10m")
    }

    // ----- power --------------------------------------------------------------

    @Test
    fun `a plain number parses to watts`() {
        assertThat(parsePowerWatts("100")).isEqualTo(100)
        assertThat(parsePowerWatts(" 5 ")).isEqualTo(5)
    }

    @Test
    fun `a blank or unusable entry clears the field instead of failing the save`() {
        // Zero is the not-set value the card already renders as a dash. Failing
        // the save would take the other three fields down with it.
        assertThat(parsePowerWatts("")).isEqualTo(0)
        assertThat(parsePowerWatts("  ")).isEqualTo(0)
        assertThat(parsePowerWatts("abc")).isEqualTo(0)
    }

    @Test
    fun `stray non-digits are ignored rather than rejected`() {
        assertThat(parsePowerWatts("100W")).isEqualTo(100)
    }

    @Test
    fun `an absurd power is clamped rather than stored`() {
        assertThat(parsePowerWatts("999999999")).isEqualTo(MAX_POWER_WATTS)
    }

    @Test
    fun `a value too large for an int does not crash the save`() {
        // toIntOrNull returns null well before this overflows, and the fallback
        // is the same not-set value as any other unusable entry.
        assertThat(parsePowerWatts("9".repeat(40))).isEqualTo(0)
    }

    // ----- the card detail line -----------------------------------------------

    @Test
    fun `the detail line joins what is set`() {
        assertThat(operatorDetailLine("FN42", "EFHW", 100)).isEqualTo("FN42 · EFHW · 100W")
    }

    @Test
    fun `unset values are dropped rather than shown as dashes`() {
        // Three dashes in a row is noise, and an operator who has not filled
        // these in does not need telling three times.
        assertThat(operatorDetailLine("FN42", "", 0)).isEqualTo("FN42")
        assertThat(operatorDetailLine("", "EFHW", 0)).isEqualTo("EFHW")
        assertThat(operatorDetailLine("", "", 100)).isEqualTo("100W")
    }

    @Test
    fun `an entirely unset operator yields an empty line`() {
        // The card substitutes its own placeholder for this, so returning a
        // lone separator here would print a stray dot under the callsign.
        assertThat(operatorDetailLine("", "  ", 0)).isEmpty()
    }

    @Test
    fun `the grid is upper-cased for display`() {
        assertThat(operatorDetailLine("fn42ab", "", 0)).isEqualTo("FN42AB")
    }

    @Test
    fun `zero and negative power count as unset`() {
        assertThat(operatorDetailLine("FN42", "", -5)).isEqualTo("FN42")
    }
}
