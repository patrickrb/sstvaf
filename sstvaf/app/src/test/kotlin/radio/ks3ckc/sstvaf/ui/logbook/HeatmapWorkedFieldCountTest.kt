package radio.ks3ckc.sstvaf.ui.logbook

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [heatmapWorkedFieldCount] feeds the grid heatmap's spoken TalkBack summary
 * ("N of 180 fields worked"). It must count only fields the 18x10 card
 * actually draws (longitude A..R, latitude A..J), or the summary claims more
 * lit cells than exist on screen.
 */
class HeatmapWorkedFieldCountTest {
    @Test
    fun `counts fields inside the displayed window`() {
        // Longitude A..R, latitude A..J — all four corners of the drawn grid.
        assertThat(heatmapWorkedFieldCount(setOf("AA", "AJ", "RA", "RJ"))).isEqualTo(4)
    }

    @Test
    fun `empty set counts zero`() {
        assertThat(heatmapWorkedFieldCount(emptySet())).isEqualTo(0)
    }

    @Test
    fun `latitude letters past J are outside the drawn window`() {
        // "FN" is a real Maidenhead field but the card only draws latitude
        // rows A..J; counting it would overstate the lit cells.
        assertThat(heatmapWorkedFieldCount(setOf("FN", "FJ"))).isEqualTo(1)
    }

    @Test
    fun `longitude letters past R are not valid fields`() {
        assertThat(heatmapWorkedFieldCount(setOf("SA", "ZB"))).isEqualTo(0)
    }

    @Test
    fun `malformed designators are ignored`() {
        assertThat(heatmapWorkedFieldCount(setOf("", "F", "FN4", "12"))).isEqualTo(0)
    }

    @Test
    fun `window bounds follow the cols and rows arguments`() {
        // A hypothetical 2x2 window keeps only AA/AB/BA/BB.
        val fields = setOf("AA", "AB", "BA", "BB", "CA", "AC")
        assertThat(heatmapWorkedFieldCount(fields, cols = 2, rows = 2)).isEqualTo(4)
    }
}
