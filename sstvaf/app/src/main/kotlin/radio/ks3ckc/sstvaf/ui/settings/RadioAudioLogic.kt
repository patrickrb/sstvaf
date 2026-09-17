package radio.ks3ckc.sstvaf.ui.settings

import androidx.annotation.StringRes
import com.k1af.ft8af.R
import com.k1af.ft8af.connector.ConnectMode
import com.k1af.ft8af.database.ControlMode
import com.k1af.ft8af.database.RigNameList
import com.k1af.ft8af.rigs.CatConnectionState
import com.k1af.ft8af.spectrum.AudioInputLevel
import com.k1af.ft8af.wave.InputAudioLevel
import radio.ks3ckc.sstvaf.ui.components.classifyInputLevel
import radio.ks3ckc.sstvaf.ui.components.inputLevelStatusText

/**
 * Decision logic for the redesigned Radio & audio screen: what the connection
 * card says, which controls are relevant, and how the input-level meter is
 * drawn.
 *
 * Extracted because this is the screen an operator opens when transmit is not
 * working, so what it claims about the rig has to be right — and none of it
 * needs a display to check.
 */

// ---------------------------------------------------------------------------
// Connection card
// ---------------------------------------------------------------------------

/** What the connection card is reporting. */
internal enum class RigLinkState {
    /** CAT/RTS/DTR and the rig is answering. */
    CONNECTED,

    /** A connection attempt is in flight. */
    CONNECTING,

    /** A control link is configured but the rig is not answering. */
    DISCONNECTED,

    /** VOX: audio only, nothing to connect. */
    VOX,
}

/**
 * The link state from the control mode and the CAT connection.
 *
 * VOX outranks everything, including an ERROR left over from a previous
 * CAT session: an operator who switched to VOX has no control link, so
 * reporting one as broken would be reporting the absence of something they
 * turned off deliberately.
 */
internal fun rigLinkState(controlMode: Int, catState: CatConnectionState): RigLinkState = when {
    controlMode == ControlMode.VOX -> RigLinkState.VOX
    catState == CatConnectionState.CONNECTED -> RigLinkState.CONNECTED
    catState == CatConnectionState.CONNECTING -> RigLinkState.CONNECTING
    else -> RigLinkState.DISCONNECTED
}

/**
 * The card title line, e.g. "IC-705 connected".
 *
 * [hasRigModel] is false on a fresh install, where the stored model is the
 * blank first row of the shipped list. Its display name is "None", which the
 * connected/disconnected formats turn into "None not responding" — a
 * sentence that reads as a fault when the real situation is that no rig has
 * been chosen yet. That case gets its own line naming the actual next step.
 */
@StringRes
internal fun rigLinkTitleRes(state: RigLinkState, hasRigModel: Boolean = true): Int = when {
    state == RigLinkState.VOX -> R.string.radio_link_vox
    !hasRigModel -> R.string.radio_link_no_model
    state == RigLinkState.CONNECTED -> R.string.radio_link_connected
    state == RigLinkState.CONNECTING -> R.string.radio_link_connecting
    else -> R.string.radio_link_disconnected
}

/**
 * Whether a rig model has actually been chosen.
 *
 * Index 0 of the shipped list is a blank placeholder rather than a radio, so a
 * stored model number of 0 means "not set" rather than "the first rig".
 */
internal fun hasRigModelSelected(modelNo: Int): Boolean = modelNo > 0

/** The card action pill, e.g. "Reconnect" / "Connect" / "Test PTT". */
@StringRes
internal fun rigLinkActionRes(state: RigLinkState): Int = when (state) {
    RigLinkState.CONNECTED -> R.string.radio_action_reconnect
    RigLinkState.CONNECTING -> R.string.radio_action_connecting
    RigLinkState.DISCONNECTED -> R.string.radio_action_connect
    RigLinkState.VOX -> R.string.radio_action_test_ptt
}

/**
 * Whether the card's action pill is the accent (primary) treatment.
 *
 * Only when there is something to fix. A connected rig's "Reconnect" and a
 * VOX setup's "Test PTT" are useful but optional, so they stay muted — an
 * accent button on a working setup would read as an unfinished step.
 */
internal fun rigLinkActionIsPrimary(state: RigLinkState): Boolean =
    state == RigLinkState.DISCONNECTED

/** Whether the action pill does anything; a connection in flight is not re-tappable. */
internal fun rigLinkActionEnabled(state: RigLinkState): Boolean =
    state != RigLinkState.CONNECTING

/**
 * The card's detail line: how the rig is wired and what that means right now.
 *
 * Assembled from resolved strings so it stays testable. The connected case
 * names the baud rate only on a USB link, because it is meaningless on a
 * network or Bluetooth connection and printing it there would be a lie about
 * what governs the link.
 */
internal fun rigLinkDetail(
    state: RigLinkState,
    connectionLabel: String,
    baudLabel: String,
    isUsb: Boolean,
    dialLabel: String,
    voxDetail: String,
    waitingDetail: String,
    checkCableDetail: String,
): String = when (state) {
    RigLinkState.VOX -> voxDetail
    RigLinkState.CONNECTING -> "$connectionLabel · $waitingDetail"
    RigLinkState.DISCONNECTED -> "$connectionLabel · $checkCableDetail"
    RigLinkState.CONNECTED -> buildString {
        append(connectionLabel)
        if (isUsb && baudLabel.isNotBlank()) {
            append(" · ")
            append(baudLabel)
        }
        if (dialLabel.isNotBlank()) {
            append(" · ")
            append(dialLabel)
        }
    }
}

// ---------------------------------------------------------------------------
// Connection / PTT controls
// ---------------------------------------------------------------------------

/** The three connection options, in the design's order. */
internal val CONNECTION_MODES: List<Int> = listOf(
    ConnectMode.USB_CABLE,
    ConnectMode.BLUE_TOOTH,
    ConnectMode.NETWORK,
)

/** A connection option's label. */
@StringRes
internal fun connectionModeLabelRes(mode: Int): Int = when (mode) {
    ConnectMode.BLUE_TOOTH -> R.string.settings_conn_bluetooth
    ConnectMode.NETWORK -> R.string.settings_conn_network
    else -> R.string.settings_conn_usb_cable
}

/**
 * The hint under the connection selector.
 *
 * Each says the one thing that actually goes wrong with that route: the USB
 * permission dialog people dismiss, the Android pairing that has to happen
 * first, and the address format nobody guesses.
 */
@StringRes
internal fun connectionHintRes(mode: Int): Int = when (mode) {
    ConnectMode.BLUE_TOOTH -> R.string.radio_hint_bluetooth
    ConnectMode.NETWORK -> R.string.radio_hint_network
    else -> R.string.radio_hint_usb
}

/** The four PTT/control options, in the design's order. */
internal val CONTROL_MODES: List<Int> = listOf(
    ControlMode.CAT,
    ControlMode.RTS,
    ControlMode.DTR,
    ControlMode.VOX,
)

/** A control option's short label. */
internal fun controlModeShortLabel(mode: Int): String = when (mode) {
    ControlMode.CAT -> "CAT"
    ControlMode.RTS -> "RTS"
    ControlMode.DTR -> "DTR"
    else -> "VOX"
}

/** The hint beside the PTT selector: what actually keys the rig. */
@StringRes
internal fun controlHintRes(mode: Int): Int = when (mode) {
    ControlMode.CAT -> R.string.radio_ptt_cat
    ControlMode.RTS -> R.string.radio_ptt_rts
    ControlMode.DTR -> R.string.radio_ptt_dtr
    else -> R.string.radio_ptt_vox
}

/**
 * Whether the baud row is shown.
 *
 * Only for a USB serial link under rig control. Bluetooth and network links do
 * not have a baud rate, and VOX has no serial link at all — offering the
 * control there implies it does something, and an operator who changes it
 * while chasing a fault has been sent down a dead end.
 */
internal fun showsBaudRate(connectMode: Int, controlMode: Int): Boolean =
    connectMode == ConnectMode.USB_CABLE && controlMode != ControlMode.VOX

/**
 * The baud rates offered, slowest first.
 *
 * All nine the previous picker had. The redesign shows these as chips rather
 * than a dialog, but the list is not shortened to fit: 4800 is what older
 * Kenwood and Yaesu rigs default to, and dropping a rate to tidy the layout
 * would lock those rigs out of CAT control entirely.
 */
internal val BAUD_RATES: List<Int> = listOf(
    4800, 9600, 14400, 19200, 38400, 43000, 56000, 57600, 115200,
)

/** The most chips that stay legible in one row on a compact phone. */
internal const val BAUD_CHIPS_PER_ROW = 5

/**
 * The baud rates split into rows for display.
 *
 * Nine chips in a single row would each be about 35dp wide, which is below the
 * touch-target minimum as well as illegible, so they wrap. The split keeps the
 * order, so the rows read slowest-to-fastest left to right and then down.
 */
internal fun baudChipRows(
    rates: List<Int> = BAUD_RATES,
    perRow: Int = BAUD_CHIPS_PER_ROW,
): List<List<Int>> = if (perRow <= 0) listOf(rates) else rates.chunked(perRow)

/**
 * A baud rate as a short chip label, e.g. "115.2k".
 *
 * Abbreviated because the full figure does not fit a chip at a legible size,
 * and every rate here is distinct in its first three significant digits.
 */
internal fun baudChipLabel(baud: Int): String = when {
    baud < 1000 -> baud.toString()
    baud % 1000 == 0 -> "${baud / 1000}k"
    else -> "${baud / 1000}.${(baud % 1000) / 100}k"
}

/** PTT delay slider bounds and step, in milliseconds. */
internal const val PTT_DELAY_MIN = 0
internal const val PTT_DELAY_MAX = 500
internal const val PTT_DELAY_STEP = 50

/**
 * A PTT delay snapped to the slider's step and clamped to its range.
 *
 * Snapped because the value is a hardware settling time an operator tunes by
 * trial — 50 ms increments are the granularity that matters, and a slider
 * reporting 137 ms suggests a precision that does not exist.
 */
internal fun snapPttDelay(value: Int): Int {
    val clamped = value.coerceIn(PTT_DELAY_MIN, PTT_DELAY_MAX)
    return ((clamped + PTT_DELAY_STEP / 2) / PTT_DELAY_STEP) * PTT_DELAY_STEP
}

// ---------------------------------------------------------------------------
// Input level meter
// ---------------------------------------------------------------------------

/**
 * The gradient's horizontal scale for a meter filled to [level].
 *
 * The meter's fill is a green-to-red gradient, and the gradient has to map to
 * the *full* scale rather than to the width of the fill. Otherwise a quiet
 * signal shows red at the end of its short bar, telling the operator to turn
 * the gain down at exactly the moment they need to turn it up. Scaling the
 * gradient by the inverse of the fill keeps each colour pinned to its own
 * level, so the bar's tip is red only when the level really is hot.
 *
 * Returns 1.0 for a zero or negative fill, where there is nothing to scale.
 */
internal fun inputLevelGradientScale(level: Float): Float {
    val fill = level.coerceIn(0f, 1f)
    return if (fill <= 0f) 1f else 1f / fill
}

/**
 * Whether the meter should say the input is silent rather than simply quiet.
 *
 * A [AudioInputLevel.Status.SILENT] reading and no reading at all look
 * identical on the bar, and both mean the operator should check the cable
 * before touching the gain — so both get the same wording.
 */
internal fun inputLevelIsSilent(levels: InputAudioLevel.Levels?): Boolean =
    levels == null || classifyInputLevel(levels) == AudioInputLevel.Status.SILENT

/**
 * The status word beside the meter.
 *
 * Delegates to the shared classifier wording, except for silence. The shared
 * function collapses SILENT into the "too low" word on the grounds that the
 * advice is the same and the colour tells them apart, which held while the
 * only meter was a strip too small to carry a sentence. This one does carry a
 * sentence, and "Low" printed beside "no audio reaching the app" reads as two
 * different diagnoses of the same reading.
 */
@StringRes
internal fun inputLevelWordRes(status: AudioInputLevel.Status): Int =
    if (status == AudioInputLevel.Status.SILENT) {
        R.string.radio_level_silent
    } else {
        inputLevelStatusText(status)
    }

/**
 * Advice to show under the meter for a status, or null when the level is fine.
 *
 * Only the actionable cases get a line. A GOOD reading needs no instruction,
 * and printing one anyway trains the operator to ignore the text.
 */
@StringRes
internal fun inputLevelAdviceRes(status: AudioInputLevel.Status): Int? = when (status) {
    AudioInputLevel.Status.SILENT -> R.string.radio_level_advice_silent
    AudioInputLevel.Status.LOW -> R.string.radio_level_advice_low
    AudioInputLevel.Status.GOOD -> null
    AudioInputLevel.Status.HIGH -> R.string.radio_level_advice_high
    AudioInputLevel.Status.CLIPPING -> R.string.radio_level_advice_clipping
}

// ---------------------------------------------------------------------------
// Rig picker
// ---------------------------------------------------------------------------

/** One selectable rig: its index in the shipped list, and its display name. */
internal data class RigOption(val index: Int, val name: String)

/**
 * The selectable rigs from the shipped list.
 *
 * Entries whose model name starts with `#` are section comments in the asset,
 * not rigs, and are dropped — the previous dialog did the same, and picking
 * one would configure the app for a heading.
 */
internal fun rigOptions(rigNameList: RigNameList): List<RigOption> =
    rigNameList.rigList
        .mapIndexed { index, rig -> RigOption(index, rig.name) }
        .filter { option ->
            rigNameList.rigList[option.index].modelName.startsWith("#").not()
        }

/**
 * The rigs matching a search query, case-insensitively, on all whitespace-
 * separated terms.
 *
 * All terms, so "yaesu 991" narrows rather than widening — with ~100 rigs in
 * the list, a query that matched any term would return most of it. A blank
 * query returns everything unchanged.
 */
internal fun searchRigs(options: List<RigOption>, query: String): List<RigOption> {
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return options
    return options.filter { option ->
        terms.all { option.name.contains(it, ignoreCase = true) }
    }
}
