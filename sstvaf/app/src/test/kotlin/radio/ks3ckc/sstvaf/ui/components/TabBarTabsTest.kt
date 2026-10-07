package radio.ks3ckc.sstvaf.ui.components

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the tab set: Receive, Send, Gallery, then Settings on the far right,
 * and nothing else. Two decisions this locks in are deliberate and worth being
 * hard to undo by accident:
 *
 *  - **Waterfall is gone.** SSTV transmits its mode in the VIS header, so the
 *    decoder identifies the mode itself. There was nothing on that screen an
 *    operator could act on.
 *  - **Settings is the far-right tab; Logbook is not a tab.** Settings moved
 *    back onto the bar (matching FT8AF's layout) because burying first-run
 *    setup behind the overflow sheet made it hard to find. Logbook stays
 *    behind the header's overflow sheet ([MoreSheet]).
 *
 * Robolectric because the labels are Android string resources.
 */
@RunWith(RobolectricTestRunner::class)
class TabBarTabsTest {

    @Test
    fun `tab set is exactly the four screens in order`() {
        assertThat(SstvTab.entries.map { it.name })
            .containsExactly("RX", "TX", "GALLERY", "SETTINGS")
            .inOrder()
    }

    @Test
    fun `receive is the first (default landing) tab`() {
        assertThat(SstvTab.entries.first()).isEqualTo(SstvTab.RX)
    }

    @Test
    fun `settings is the last (far-right) tab`() {
        assertThat(SstvTab.entries.last()).isEqualTo(SstvTab.SETTINGS)
    }

    @Test
    fun `each tab is wired to its own label resource`() {
        assertThat(SstvTab.RX.labelRes).isEqualTo(R.string.tab_receive)
        assertThat(SstvTab.TX.labelRes).isEqualTo(R.string.tab_send)
        assertThat(SstvTab.GALLERY.labelRes).isEqualTo(R.string.tab_gallery)
        assertThat(SstvTab.SETTINGS.labelRes).isEqualTo(R.string.tab_settings)
    }

    @Test
    fun `no two tabs share a label`() {
        val labels = SstvTab.entries.map { it.labelRes }
        assertThat(labels).containsNoDuplicates()
    }
}
