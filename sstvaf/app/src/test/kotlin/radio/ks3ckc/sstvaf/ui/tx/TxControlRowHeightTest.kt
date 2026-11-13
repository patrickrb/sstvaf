package radio.ks3ckc.sstvaf.ui.tx

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import radio.ks3ckc.sstvaf.sstv.SstvMode

/**
 * The Send screen's bottom row: the mode card and the Transmit button must be
 * the same height and share a top edge.
 *
 * They are separate composables in separate files, so nothing but a test stops
 * them drifting. They did drift: the button was a fixed 52dp and the card took
 * its height from its own content, about 47dp, which read as a misalignment
 * rather than as a deliberate difference. The row also centred them, so the
 * card slid down by half a line whenever the button grew a reason caption
 * underneath it ("Pick a picture first") - the two controls lined up or did
 * not depending on whether an image was loaded.
 *
 * Heights here come from fixed dp modifiers rather than from measured text, so
 * Robolectric reports them faithfully; this is not a case where the font stub
 * makes the numbers meaningless.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TxControlRowHeightTest {

    @get:Rule
    val rule = createComposeRule()

    private val mode = SstvMode.SCOTTIE_1

    @Test
    fun `the mode card is exactly the shared control height`() {
        rule.setContent {
            ModeCard(
                mode = mode,
                enabled = true,
                onClick = {},
                modifier = Modifier.testTag("mode").width(118.dp),
            )
        }
        val height = rule.onNodeWithTag("mode").getUnclippedBoundsInRoot().height
        assertThat(height.value).isWithin(0.5f).of(TX_CONTROL_HEIGHT.value)
    }

    @Test
    fun `the shared height is a usable touch target`() {
        // Both controls are primary actions on this screen, so the shared
        // height has to clear the 48dp minimum as well as merely matching.
        assertThat(TX_CONTROL_HEIGHT.value).isAtLeast(48f)
    }

    @Test
    fun `the card keeps its height whatever the mode name`() {
        // PD 290 is the longest display name and the tallest duration string;
        // the card must not grow for it and break the row.
        rule.setContent {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModeCard(
                    mode = SstvMode.SCOTTIE_1,
                    enabled = true,
                    onClick = {},
                    modifier = Modifier.testTag("short").width(118.dp),
                )
                ModeCard(
                    mode = SstvMode.PD_290,
                    enabled = true,
                    onClick = {},
                    modifier = Modifier.testTag("long").width(118.dp),
                )
            }
        }
        val short = rule.onNodeWithTag("short").getUnclippedBoundsInRoot()
        val long = rule.onNodeWithTag("long").getUnclippedBoundsInRoot()
        assertThat(long.height.value).isWithin(0.5f).of(short.height.value)
        // Same top edge too, which is what the eye actually reads.
        assertThat(long.top.value).isWithin(0.5f).of(short.top.value)
    }

    @Test
    fun `a disabled card is the same height as an enabled one`() {
        // The card is disabled while the rig is keyed; the row must not
        // resize underneath the operator at the moment of transmit.
        rule.setContent {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                ModeCard(
                    mode = mode,
                    enabled = true,
                    onClick = {},
                    modifier = Modifier.testTag("on").width(118.dp),
                )
                ModeCard(
                    mode = mode,
                    enabled = false,
                    onClick = {},
                    modifier = Modifier.testTag("off").width(118.dp),
                )
            }
        }
        val on = rule.onNodeWithTag("on").getUnclippedBoundsInRoot().height
        val off = rule.onNodeWithTag("off").getUnclippedBoundsInRoot().height
        assertThat(off.value).isWithin(0.5f).of(on.value)
    }
}
