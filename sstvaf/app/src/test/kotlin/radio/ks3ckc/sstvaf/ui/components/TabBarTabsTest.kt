package radio.ks3ckc.sstvaf.ui.components

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the tab set. The RX tab (live SSTV decode view, SSTVAF transformation
 * PR 6) is the FIRST tab and the app's landing screen, with GALLERY (saved
 * images, PR 7) and TX (the composer, PR 8) right after it; the bottom bar
 * must offer exactly the six screens in order, and each tab's label resource
 * must stay wired to the matching string. Robolectric because the labels are
 * Android string resources.
 */
@RunWith(RobolectricTestRunner::class)
class TabBarTabsTest {

    @Test
    fun `tab set is exactly the six screens in order`() {
        assertThat(SstvTab.entries.map { it.name })
            .containsExactly("RX", "GALLERY", "TX", "WATERFALL", "LOG", "SETTINGS")
            .inOrder()
    }

    @Test
    fun `rx is the first (default landing) tab`() {
        assertThat(SstvTab.entries.first()).isEqualTo(SstvTab.RX)
    }

    @Test
    fun `each tab is wired to its own label resource`() {
        assertThat(SstvTab.RX.labelRes).isEqualTo(R.string.tab_rx)
        assertThat(SstvTab.GALLERY.labelRes).isEqualTo(R.string.tab_gallery)
        assertThat(SstvTab.TX.labelRes).isEqualTo(R.string.tab_tx)
        assertThat(SstvTab.WATERFALL.labelRes).isEqualTo(R.string.tab_waterfall)
        assertThat(SstvTab.LOG.labelRes).isEqualTo(R.string.tab_logbook)
        assertThat(SstvTab.SETTINGS.labelRes).isEqualTo(R.string.tab_settings)
    }
}
