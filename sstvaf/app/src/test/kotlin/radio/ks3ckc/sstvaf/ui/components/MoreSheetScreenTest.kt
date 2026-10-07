package radio.ks3ckc.sstvaf.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Render tests for the overflow sheet's row set after Settings moved onto the
 * bottom bar as the far-right tab.
 *
 * The sheet now offers exactly Radio & audio, Operator and Logbook. A Settings
 * row here again would give the app two entrances to the same screen — one of
 * which (this one) used to be the only, well-hidden one. The row's removal is
 * the point of the change, so it gets a test.
 *
 * Rows are activated via their semantics OnClick action, not pointer
 * injection: the sheet clips its surface with a RoundedCornerShape, and
 * Robolectric cannot hit-test through a rounded-outline clip (the containment
 * check always reports "outside"), so an injected tap falls through to the
 * scrim and dismisses the sheet instead. The semantics action invokes the same
 * onClick the row's clickable registers, which is the wiring under test.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class MoreSheetScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var dismissed = false

    private fun setSheet(onNavigate: (MoreDestination) -> Unit = {}) {
        composeRule.setContent {
            // The same hosting shape the shell uses: sheets overlay a
            // fillMaxSize Box (see BottomSheetOverlayHostTest for why the host
            // matters — a mis-hosted sheet composes but lays out at zero size).
            Box(modifier = Modifier.fillMaxSize()) {
                MoreSheet(
                    visible = true,
                    radioSummary = "IC-705 · USB Cable · CAT",
                    operatorSummary = "K1AF · FN42",
                    onDismiss = { dismissed = true },
                    onNavigate = onNavigate,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun SemanticsNodeInteraction.click() =
        performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun sheet_offersRadioOperatorAndLogbook_butNoSettingsRow() {
        setSheet()
        composeRule.onNodeWithText("Radio & audio").assertIsDisplayed()
        composeRule.onNodeWithText("Operator").assertIsDisplayed()
        composeRule.onNodeWithText("Logbook").assertIsDisplayed()
        // Settings lives on the bottom bar now; a row here would be a second,
        // competing entrance.
        composeRule.onNodeWithText("Settings").assertDoesNotExist()
    }

    @Test
    fun sheet_reportsEachRemainingDestination() {
        var picked: MoreDestination? = null
        setSheet(onNavigate = { picked = it })

        composeRule.onNodeWithText("Radio & audio").click()
        assertThat(picked).isEqualTo(MoreDestination.RADIO_AUDIO)

        composeRule.onNodeWithText("Operator").click()
        assertThat(picked).isEqualTo(MoreDestination.OPERATOR)

        composeRule.onNodeWithText("Logbook").click()
        assertThat(picked).isEqualTo(MoreDestination.LOGBOOK)

        // Navigating must never double as dismissing — the shell owns closing
        // the sheet, and a row that also fired onDismiss would race it.
        assertThat(dismissed).isFalse()
    }
}
