package radio.ks3ckc.sstvaf.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import radio.ks3ckc.sstvaf.theme.StatusConfirmed

/**
 * Render tests for the shell's chrome — the title-only header, the band bar,
 * and the two tab surfaces.
 *
 * **What these can and cannot check.** Robolectric measures text with stub font
 * metrics that return roughly one pixel per character, so every string in the
 * tree is a few pixels wide regardless of its real type size. That makes any
 * assertion about pixel *fit* worthless here; it would be testing the stub, not
 * the layout. Whether the chrome physically fits is a real-device check, and
 * the tab × device × orientation sweep in `docs/ui-testing.md` is still
 * required.
 *
 * What this suite does lock down is that the chrome is wired: every control is
 * composed, is clickable, reports the right thing when tapped, and carries a
 * TalkBack label. That is not a formality — it caught the old overflow button
 * shipping with no content description at all, because `clickable`'s
 * `onClickLabel` names the action without ever labelling the control.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class ShellChromeLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val bandLabel = bandBarLabel(14_230_000L, "20m")
    private val catState = "connected"
    private val barDescription = "Frequency $bandLabel, $catState, tap to change"

    /** The header, band bar and tab bar as the compact shell stacks them. */
    private fun setShell(
        catStateDescription: String = catState,
        onTab: (SstvTab) -> Unit = {},
        onBand: () -> Unit = {},
    ) {
        composeRule.setContent {
            Column(modifier = Modifier.fillMaxSize()) {
                AppHeader(title = "Receive")
                BandBar(
                    bandName = "20m",
                    freqHz = 14_230_000L,
                    dotColor = StatusConfirmed,
                    catStateDescription = catStateDescription,
                    onClick = onBand,
                )
                TabBar(activeTab = SstvTab.RX, onTabSelected = onTab)
            }
        }
    }

    // ----- the band bar is composed, labelled, and opens the picker ----------

    @Test
    fun bandBar_composesItsCaptionAndDialLine() {
        setShell()
        // The caption is what makes the control self-describing — the whole
        // point of promoting the old header chip to a bar.
        composeRule.onNodeWithText("BAND & FREQUENCY").assertExists()
        composeRule.onNodeWithText(bandLabel).assertExists()
    }

    @Test
    fun bandBar_isClickableAndDescribed() {
        // The bar's visible text is a dial reading; without a description
        // TalkBack gives no hint that it opens anything.
        setShell()
        composeRule.onNodeWithContentDescription(barDescription)
            .assertExists()
            .assertHasClickAction()
    }

    @Test
    fun bandBar_descriptionCarriesTheCatState() {
        // The dot is the only visual CAT indicator on the bar, and colour
        // alone is not available to TalkBack — or to an operator who cannot
        // tell the green from the amber. Two states must produce two
        // descriptions.
        setShell(catStateDescription = "connection error")
        composeRule.onNodeWithContentDescription(
            "Frequency $bandLabel, connection error, tap to change",
        ).assertExists()
    }

    @Test
    fun bandBar_opensTheFrequencySheet() {
        var opened = false
        setShell(onBand = { opened = true })
        composeRule.onNodeWithContentDescription(barDescription).performClick()
        assertThat(opened).isTrue()
    }

    // ----- the tab bar offers all five, and reports the right one ------------

    @Test
    fun tabBar_composesAllFiveTabs() {
        setShell()
        // "Receive" is also the header title, so it is covered by the header
        // tests; these four labels are unambiguous in the tree.
        composeRule.onNodeWithText("Send").assertExists()
        composeRule.onNodeWithText("Gallery").assertExists()
        composeRule.onNodeWithText("Logbook").assertExists()
        composeRule.onNodeWithText("Settings").assertExists()
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

    @Test
    fun tabBar_reportsLogbookWhenLogbookIsTapped() {
        // Logbook graduated from the retired overflow sheet to the bar — the
        // bar itself must be able to report it.
        var picked: SstvTab? = null
        setShell(onTab = { picked = it })
        composeRule.onNodeWithText("Logbook").performClick()
        assertThat(picked).isEqualTo(SstvTab.LOG)
    }

    @Test
    fun tabBar_reportsSettingsWhenSettingsIsTapped() {
        var picked: SstvTab? = null
        setShell(onTab = { picked = it })
        composeRule.onNodeWithText("Settings").performClick()
        assertThat(picked).isEqualTo(SstvTab.SETTINGS)
    }

    // ----- the rail is the same control re-flowed for width ------------------

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun rail_offersTheSameFiveTabs() {
        composeRule.setContent {
            TabRail(activeTab = SstvTab.RX, onTabSelected = {})
        }
        composeRule.onNodeWithText("Receive").assertExists()
        composeRule.onNodeWithText("Send").assertExists()
        composeRule.onNodeWithText("Gallery").assertExists()
        composeRule.onNodeWithText("Logbook").assertExists()
        composeRule.onNodeWithText("Settings").assertExists()
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun rail_reportsTheTappedTab() {
        var picked: SstvTab? = null
        composeRule.setContent {
            TabRail(activeTab = SstvTab.RX, onTabSelected = { picked = it })
        }
        composeRule.onNodeWithText("Logbook").performClick()
        assertThat(picked).isEqualTo(SstvTab.LOG)
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
