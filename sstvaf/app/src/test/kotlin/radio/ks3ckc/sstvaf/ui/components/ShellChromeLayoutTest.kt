package radio.ks3ckc.sstvaf.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import radio.ks3ckc.sstvaf.theme.StatusConfirmed

/**
 * Render tests for the shell's chrome — the header and the two tab surfaces.
 *
 * **What these can and cannot check.** Robolectric measures text with stub font
 * metrics that return roughly one pixel per character, so every string in the
 * tree is a few pixels wide regardless of its real type size. That makes any
 * assertion about pixel *fit* — does the frequency chip still have room next to
 * the title at 320dp — worthless here; it would be testing the stub, not the
 * layout. Whether the header physically fits is a real-device check, and the
 * tab × device × orientation sweep in `docs/ui-testing.md` is still required.
 *
 * What this suite does lock down is that the chrome is wired: every control is
 * composed, is clickable, reports the right thing when tapped, and carries a
 * TalkBack label. That is not a formality — it caught the overflow button
 * shipping with no content description at all, because `clickable`'s
 * `onClickLabel` names the action without ever labelling the control.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class ShellChromeLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val chipLabel = frequencyChipLabel(14_230_000L, "20m")
    private val moreDescription = "More: radio, operator, logbook, settings"
    private val catState = "connected"
    private val chipDescription = "Frequency $chipLabel, $catState, tap to change"

    /** The header and tab bar as the compact shell stacks them. */
    private fun setShell(
        label: String = chipLabel,
        catStateDescription: String = catState,
        onTab: (SstvTab) -> Unit = {},
        onFrequency: () -> Unit = {},
        onMore: () -> Unit = {},
    ) {
        composeRule.setContent {
            Column(modifier = Modifier.fillMaxSize()) {
                AppHeader(
                    title = "Receive",
                    frequencyLabel = label,
                    catDotColor = StatusConfirmed,
                    catStateDescription = catStateDescription,
                    onOpenFrequency = onFrequency,
                    onOpenMore = onMore,
                )
                TabBar(activeTab = SstvTab.RX, onTabSelected = onTab)
            }
        }
    }

    // ----- the header is composed and labelled -------------------------------

    @Test
    fun header_composesTheDialChipWithItsLabel() {
        setShell()
        composeRule.onNodeWithText(chipLabel).assertExists()
    }

    @Test
    fun header_dialChipIsClickableAndDescribed() {
        // The chip's visible text is bare digits; without a description TalkBack
        // gives no hint that it opens anything.
        setShell()
        composeRule.onNodeWithContentDescription(chipDescription)
            .assertExists()
            .assertHasClickAction()
    }

    @Test
    fun header_dialChipDescriptionCarriesTheCatState() {
        // The dot is the only visual CAT indicator left, and colour alone is
        // not available to TalkBack — or to an operator who cannot tell the
        // green from the amber. Two states must produce two descriptions.
        setShell(catStateDescription = "connection error")
        composeRule.onNodeWithContentDescription(
            "Frequency $chipLabel, connection error, tap to change",
        ).assertExists()
    }

    @Test
    fun header_overflowButtonSurvivesAnOversizedDialChip() {
        // Regression: the chip used to be an unweighted child measured against
        // the whole half-header before the fixed-size overflow button, so a
        // label that outgrew the row on its own — a long dial at a large
        // accessibility font scale — left the button measured at zero width and
        // therefore invisible and untappable. Robolectric's stub font metrics
        // are about a pixel per character, which is exactly what makes this
        // reproducible here: a long enough label overflows the row.
        setShell(label = "1".repeat(400))
        val width = composeRule.onNodeWithContentDescription(moreDescription)
            .getUnclippedBoundsInRoot().width
        assertThat(width.value).isGreaterThan(0f)
        // And it keeps its full 34dp: the fix reserves the button, it does not
        // merely leave it a sliver.
        assertThat(width.value).isWithin(0.5f).of(34.dp.value)
    }

    @Test
    fun header_overflowButtonIsClickableAndDescribed() {
        // A glyph-only button: the description is the only thing TalkBack has.
        setShell()
        composeRule.onNodeWithContentDescription(moreDescription)
            .assertExists()
            .assertHasClickAction()
    }

    @Test
    fun header_dialChipOpensTheFrequencySheet() {
        var opened = false
        setShell(onFrequency = { opened = true })
        composeRule.onNodeWithContentDescription(chipDescription).performClick()
        assertThat(opened).isTrue()
    }

    @Test
    fun header_overflowOpensTheMoreSheet() {
        var opened = false
        setShell(onMore = { opened = true })
        composeRule.onNodeWithContentDescription(moreDescription).performClick()
        assertThat(opened).isTrue()
    }

    // ----- the tab bar offers all three, and reports the right one -----------

    @Test
    fun tabBar_composesAllThreeTabs() {
        setShell()
        // "Receive" is also the header title, so it is covered by the header
        // tests; these two labels are unambiguous in the tree.
        composeRule.onNodeWithText("Send").assertExists()
        composeRule.onNodeWithText("Gallery").assertExists()
    }

    @Test
    fun tabBar_reportsTheTappedTab() {
        var picked: SstvTab? = null
        setShell(onTab = { picked = it })
        composeRule.onNodeWithText("Gallery").performClick()
        assertThat(picked).isEqualTo(SstvTab.GALLERY)
    }

    @Test
    fun tabBar_reportsSendWhenSendIsTapped() {
        var picked: SstvTab? = null
        setShell(onTab = { picked = it })
        composeRule.onNodeWithText("Send").performClick()
        assertThat(picked).isEqualTo(SstvTab.TX)
    }

    // ----- the rail is the same control re-flowed for width ------------------

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun rail_offersTheSameThreeTabs() {
        composeRule.setContent {
            TabRail(activeTab = SstvTab.RX, onTabSelected = {})
        }
        composeRule.onNodeWithText("Receive").assertExists()
        composeRule.onNodeWithText("Send").assertExists()
        composeRule.onNodeWithText("Gallery").assertExists()
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun rail_reportsTheTappedTab() {
        var picked: SstvTab? = null
        composeRule.setContent {
            TabRail(activeTab = SstvTab.RX, onTabSelected = { picked = it })
        }
        composeRule.onNodeWithText("Gallery").performClick()
        assertThat(picked).isEqualTo(SstvTab.GALLERY)
    }

    // ----- the back affordance ------------------------------------------------

    @Test
    fun screenHeader_backButtonIsClickableAndDescribed() {
        var backed = false
        composeRule.setContent {
            TopBar(title = "Settings", onBack = { backed = true })
        }
        composeRule.onNodeWithContentDescription("Back")
            .assertExists()
            .assertHasClickAction()
            .performClick()
        assertThat(backed).isTrue()
    }

    @Test
    fun screenHeader_hasNoBackButtonWhenNotGivenOne() {
        // A tab's own title bar must not sprout a back chevron.
        composeRule.setContent {
            TopBar(title = "Settings")
        }
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }
}
