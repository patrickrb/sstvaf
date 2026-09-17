package radio.ks3ckc.sstvaf.ui.tx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import radio.ks3ckc.sstvaf.sstv.SstvMode

/**
 * Compose UI test for the mode picker — the card on the Send screen and the
 * sheet it opens. Runs on the JVM via Robolectric, following the
 * `FilterChipsScreenTest` pattern.
 *
 * `ModeSheetLogicTest` covers the grouping and label helpers, which are pure
 * functions. What it cannot see is the part that broke in review: whether the
 * sheet composes when `visible`, whether a row reports its *selected* state to
 * an accessibility service (the check mark is a drawn canvas, invisible to
 * one), and whether tapping a row or the card reaches the callbacks.
 *
 * **Why the clicks go through `SemanticsActions.OnClick` rather than
 * `performClick`.** Robolectric measures text with stub font metrics and the
 * sheet is sixteen rows tall, so which rows land inside the viewport here is an
 * artefact of the stub, not of the real layout — and `performScrollTo` drives an
 * animated scroll that never idles under this runner. Invoking the semantics
 * action tests the wiring, which is what a JVM test can honestly assert;
 * whether the sheet physically fits is the device sweep in `docs/ui-testing.md`.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class ModeSheetScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val scottie1 = SstvMode.SCOTTIE_1
    private val other = modeGroups().flatMap { it.modes }.first { it != scottie1 }
    private val allModes = modeGroups().flatMap { it.modes }

    private fun setSheet(
        visible: Boolean = true,
        selected: SstvMode = scottie1,
        onDismiss: () -> Unit = {},
        onSelect: (SstvMode) -> Unit = {},
    ) {
        composeRule.setContent {
            ModeSheet(
                visible = visible,
                selected = selected,
                onDismiss = onDismiss,
                onSelect = onSelect,
            )
        }
    }

    private fun row(mode: SstvMode) =
        composeRule.onNodeWithContentDescription(rowDescription(mode))

    private fun card(mode: SstvMode) =
        composeRule.onNodeWithContentDescription("SSTV mode ${mode.displayName}")

    // ----- visibility --------------------------------------------------------

    @Test
    fun sheet_rendersEveryModeWhenVisible() {
        setSheet()
        for (mode in allModes) {
            composeRule.onNodeWithText(mode.displayName).assertExists()
        }
    }

    @Test
    fun sheet_isNotComposedWhenHidden() {
        setSheet(visible = false)
        composeRule.onNodeWithText(scottie1.displayName).assertDoesNotExist()
    }

    @Test
    fun sheet_everyModeIsAClickableLabelledRow() {
        setSheet()
        for (mode in allModes) {
            row(mode).assertExists().assertHasClickAction()
        }
    }

    @Test
    fun sheet_scrollsSoEveryModeCanBeReached() {
        // Sixteen rows, three group headings and a title do not fit a 640dp
        // phone, and the sheet is anchored to the bottom — without a scroller
        // the fast modes at the top are clipped away with no way to reach them.
        setSheet()
        composeRule.onNode(hasScrollAction()).assertExists()
    }

    // ----- selection semantics ----------------------------------------------

    @Test
    fun sheet_reportsTheSelectedRowToAccessibility() {
        // The regression this guards: with `clickable(role = RadioButton)` every
        // row announced identically, because the only thing marking the active
        // one was SelectionRing — a canvas an accessibility service cannot read.
        setSheet(selected = scottie1)
        row(scottie1).assertIsSelected()
        row(other).assertIsNotSelected()
    }

    @Test
    fun sheet_movesTheSelectedStateWhenTheModeChanges() {
        setSheet(selected = other)
        row(other).assertIsSelected()
        row(scottie1).assertIsNotSelected()
    }

    @Test
    fun sheet_marksExactlyOneRowSelected() {
        setSheet(selected = scottie1)
        for (mode in allModes.filter { it != scottie1 }) {
            row(mode).assertIsNotSelected()
        }
    }

    // ----- callbacks ---------------------------------------------------------

    @Test
    fun sheet_tappingARowSelectsThatMode() {
        var picked: SstvMode? = null
        setSheet(onSelect = { picked = it })
        row(other).performSemanticsAction(SemanticsActions.OnClick)
        assertThat(picked).isEqualTo(other)
    }

    @Test
    fun sheet_tappingTheAlreadySelectedRowStillFires() {
        var picked: SstvMode? = null
        setSheet(selected = scottie1, onSelect = { picked = it })
        row(scottie1).performSemanticsAction(SemanticsActions.OnClick)
        assertThat(picked).isEqualTo(scottie1)
    }

    // ----- the card, and the card-to-sheet path ------------------------------

    @Test
    fun card_isDescribedAndShowsTheCurrentMode() {
        composeRule.setContent {
            ModeCard(mode = scottie1, enabled = true, onClick = {})
        }
        composeRule.onNodeWithText(scottie1.displayName).assertExists()
        card(scottie1).assertExists().assertHasClickAction()
    }

    @Test
    fun card_isDisabledAndDoesNotFireWhileTransmitting() {
        // Still described, so TalkBack can read out which mode is going out,
        // but reported as disabled and inert to a real tap. (Compose keeps the
        // OnClick semantics action on a disabled clickable and marks the node
        // disabled instead, so the enabled flag — not the action — is what says
        // this cannot be used.)
        var clicks = 0
        composeRule.setContent {
            ModeCard(mode = scottie1, enabled = false, onClick = { clicks++ })
        }
        card(scottie1).assertExists().assertIsNotEnabled().performClick()
        assertThat(clicks).isEqualTo(0)
    }

    @Test
    fun card_opensTheSheetAndPickingAModeClosesIt() {
        // The whole path the review asked for: card -> sheet -> row -> dismissed
        // with the new mode applied.
        composeRule.setContent {
            var sheetVisible by remember { mutableStateOf(false) }
            var mode by remember { mutableStateOf(scottie1) }
            ModeCard(mode = mode, enabled = true, onClick = { sheetVisible = true })
            ModeSheet(
                visible = sheetVisible,
                selected = mode,
                onDismiss = { sheetVisible = false },
                onSelect = { mode = it; sheetVisible = false },
            )
        }

        row(other).assertDoesNotExist()

        card(scottie1).performSemanticsAction(SemanticsActions.OnClick)
        row(other).assertExists()

        row(other).performSemanticsAction(SemanticsActions.OnClick)
        card(other).assertExists()
        row(scottie1).assertDoesNotExist()
    }

    @Test
    fun sheet_hidingItDismissesTheContent() {
        // The dismiss path: SstvAfBottomSheet keeps the sheet composed through
        // its exit animation, so "onDismiss ran" and "the sheet is gone" are
        // two different facts and this asserts the second one.
        val visible = mutableStateOf(true)
        composeRule.setContent {
            ModeSheet(
                visible = visible.value,
                selected = scottie1,
                onDismiss = { visible.value = false },
                onSelect = {},
            )
        }

        row(other).assertExists()
        composeRule.runOnIdle { visible.value = false }
        row(other).assertDoesNotExist()
    }

    /** Mirrors `mode_row_description`: name, dimensions, duration. */
    private fun rowDescription(mode: SstvMode): String =
        "${mode.displayName}, ${modeResolutionLabel(mode)}, ${modeDurationLabel(mode)}"
}
