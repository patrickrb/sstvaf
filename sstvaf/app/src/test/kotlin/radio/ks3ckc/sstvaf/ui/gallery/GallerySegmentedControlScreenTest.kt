package radio.ks3ckc.sstvaf.ui.gallery

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Compose UI test for the gallery's segmented filter, replacing the coverage
 * the deleted `FilterChipsScreenTest` had.
 *
 * What it can honestly assert on the JVM is the wiring: that every option is
 * rendered, that tapping one dispatches it, and that the current option reports
 * its selected state. The last of those is the part that was missing from the
 * control entirely - selection was conveyed by background colour alone, which
 * an accessibility service cannot see.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GallerySegmentedControlScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val options = GalleryFilter.entries

    private fun label(filter: GalleryFilter) = when (filter) {
        GalleryFilter.ALL -> "All"
        GalleryFilter.RX -> "Received"
        GalleryFilter.TX -> "Sent"
    }

    @Test
    fun `every option is rendered`() {
        rule.setContent {
            GallerySegmentedControl(
                options = options,
                selected = GalleryFilter.ALL,
                label = { label(it) },
                onSelected = {},
            )
        }
        options.forEach { rule.onNodeWithText(label(it)).assertExists() }
    }

    @Test
    fun `tapping an option dispatches it`() {
        var picked: GalleryFilter? = null
        rule.setContent {
            GallerySegmentedControl(
                options = options,
                selected = GalleryFilter.ALL,
                label = { label(it) },
                onSelected = { picked = it },
            )
        }
        rule.onNodeWithText(label(GalleryFilter.TX)).performClick()
        assertThat(picked).isEqualTo(GalleryFilter.TX)
    }

    @Test
    fun `only the current option reports itself selected`() {
        rule.setContent {
            GallerySegmentedControl(
                options = options,
                selected = GalleryFilter.RX,
                label = { label(it) },
                onSelected = {},
            )
        }
        rule.onNodeWithText(label(GalleryFilter.RX)).assertIsSelected()
        rule.onNodeWithText(label(GalleryFilter.ALL)).assertIsNotSelected()
        rule.onNodeWithText(label(GalleryFilter.TX)).assertIsNotSelected()
    }

    @Test
    fun `tapping the already-selected option still dispatches`() {
        // The caller decides whether a no-op re-selection matters; the control
        // must not swallow it silently.
        var count = 0
        rule.setContent {
            GallerySegmentedControl(
                options = options,
                selected = GalleryFilter.ALL,
                label = { label(it) },
                onSelected = { count++ },
            )
        }
        rule.onNodeWithText(label(GalleryFilter.ALL)).performClick()
        assertThat(count).isEqualTo(1)
    }
}
