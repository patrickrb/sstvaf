package radio.ks3ckc.sstvaf.ui.components

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the tab set after the three-tab restructure.
 *
 * The bottom bar offers exactly Receive, Send and Gallery, in that order, and
 * nothing else. The two deletions this locks in are deliberate and worth being
 * hard to undo by accident:
 *
 *  - **Waterfall is gone.** SSTV transmits its mode in the VIS header, so the
 *    decoder identifies the mode itself. There was nothing on that screen an
 *    operator could act on.
 *  - **Logbook and Settings are not tabs.** They live behind the header's
 *    overflow sheet ([MoreSheet]); a tab each meant two thirds of the bar went
 *    to screens you visit rather than operate from.
 *
 * Robolectric because the labels are Android string resources.
 */
@RunWith(RobolectricTestRunner::class)
class TabBarTabsTest {

    @Test
    fun `tab set is exactly the three screens in order`() {
        assertThat(SstvTab.entries.map { it.name })
            .containsExactly("RX", "TX", "GALLERY")
            .inOrder()
    }

    @Test
    fun `receive is the first (default landing) tab`() {
        assertThat(SstvTab.entries.first()).isEqualTo(SstvTab.RX)
    }

    @Test
    fun `each tab is wired to its own label resource`() {
        assertThat(SstvTab.RX.labelRes).isEqualTo(R.string.tab_receive)
        assertThat(SstvTab.TX.labelRes).isEqualTo(R.string.tab_send)
        assertThat(SstvTab.GALLERY.labelRes).isEqualTo(R.string.tab_gallery)
    }

    @Test
    fun `no two tabs share a label`() {
        val labels = SstvTab.entries.map { it.labelRes }
        assertThat(labels).containsNoDuplicates()
    }
}
