package radio.ks3ckc.sstvaf.ui.components

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the tab set: Receive, Send, WEFAX, Gallery, Logbook, then Settings on
 * the far right, and nothing else. WEFAX sits with the operating surfaces —
 * like Receive and Send you work a tuned radio from it, so it follows them
 * rather than the browsing tabs. Two decisions this locks in are deliberate
 * and worth being hard to undo by accident:
 *
 *  - **Waterfall is gone.** SSTV transmits its mode in the VIS header, so the
 *    decoder identifies the mode itself. There was nothing on that screen an
 *    operator could act on.
 *  - **Every destination is a tab.** Logbook and Settings both sat behind the
 *    header's overflow sheet at different points; burying them made them hard
 *    to find, and retiring the overflow entirely (see [AppHeader]) means the
 *    bar is the one and only navigation surface — matching FT8AF, where Log
 *    and Settings are tabs too.
 *
 * Robolectric because the labels are Android string resources.
 */
@RunWith(RobolectricTestRunner::class)
class TabBarTabsTest {

    @Test
    fun `tab set is exactly the six screens in order`() {
        assertThat(SstvTab.entries.map { it.name })
            .containsExactly("RX", "TX", "WEFAX", "GALLERY", "LOG", "SETTINGS")
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
        assertThat(SstvTab.WEFAX.labelRes).isEqualTo(R.string.tab_wefax)
        assertThat(SstvTab.GALLERY.labelRes).isEqualTo(R.string.tab_gallery)
        assertThat(SstvTab.LOG.labelRes).isEqualTo(R.string.tab_logbook)
        assertThat(SstvTab.SETTINGS.labelRes).isEqualTo(R.string.tab_settings)
    }

    @Test
    fun `no two tabs share a label`() {
        val labels = SstvTab.entries.map { it.labelRes }
        assertThat(labels).containsNoDuplicates()
    }
}
