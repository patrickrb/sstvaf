package radio.ks3ckc.sstvaf.ui.settings

import androidx.annotation.StringRes
import com.k1af.ft8af.R

/**
 * What each category row on the Settings landing says about itself.
 *
 * The design gives every row a label, a one-line description and the current
 * value, so the landing answers most "what is this set to?" questions without
 * being opened. The values come from the caller, because they are live state;
 * the labels and descriptions live here so the row set is one list rather than
 * six hand-written blocks that drift apart.
 */

/** The categories on the landing, in the order the design lists them. */
internal enum class SettingsCategory {
    RADIO_AUDIO,
    TRANSMISSION,
    LOGGING,
    ADVANCED,
    USB_DIAGNOSTICS,
    ABOUT,
}

/** A category label. */
@StringRes
internal fun categoryLabelRes(category: SettingsCategory): Int = when (category) {
    SettingsCategory.RADIO_AUDIO -> R.string.settings_cat_radio_audio
    SettingsCategory.TRANSMISSION -> R.string.settings_cat_transmission
    SettingsCategory.LOGGING -> R.string.settings_cat_logging
    SettingsCategory.ADVANCED -> R.string.settings_cat_advanced
    SettingsCategory.USB_DIAGNOSTICS -> R.string.settings_cat_usb_diagnostics
    SettingsCategory.ABOUT -> R.string.settings_cat_about
}

/** A category one-line description: what is actually inside it. */
@StringRes
internal fun categoryDescriptionRes(category: SettingsCategory): Int = when (category) {
    SettingsCategory.RADIO_AUDIO -> R.string.settings_cat_radio_audio_desc
    SettingsCategory.TRANSMISSION -> R.string.settings_cat_transmission_desc
    SettingsCategory.LOGGING -> R.string.settings_cat_logging_desc
    SettingsCategory.ADVANCED -> R.string.settings_cat_advanced_desc
    SettingsCategory.USB_DIAGNOSTICS -> R.string.settings_cat_usb_diagnostics_desc
    SettingsCategory.ABOUT -> R.string.settings_cat_about_desc
}

/**
 * The inset rig-status line on the operator card.
 *
 * Reads as a sentence about the link rather than a label and a value, because
 * this line exists to be glanced at: the operator card is the first thing on
 * the screen and the rig link is the thing most likely to be wrong.
 */
internal fun rigStatusLine(
    connected: Boolean,
    rigName: String,
    controlLabel: String,
    connectedFormat: String,
    idleFormat: String,
): String = if (connected) {
    String.format(connectedFormat, rigName, controlLabel)
} else {
    String.format(idleFormat, controlLabel)
}
