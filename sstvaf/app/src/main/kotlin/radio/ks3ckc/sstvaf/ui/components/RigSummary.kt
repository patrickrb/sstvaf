package radio.ks3ckc.sstvaf.ui.components

import com.k1af.ft8af.database.ControlMode

// Pure logic (unit-tested — see RigSummaryTest). These summarised the overflow
// sheet's radio row until that sheet was retired; the Frequency sheet's status
// line still reads from them.

/**
 * The rig status line, e.g. `"IC-705 · USB cable · CAT · connected"`.
 *
 * Reads as rig, then how it is wired, then what keys it, then whether it is
 * actually talking to us — the four facts an operator checks when transmit does
 * not key. The first three are configuration and read the same whether or not
 * the rig is plugged in, which is why [connectionStateLabel] is here: without
 * it this line answers "what is set up" while the operator is asking "is it
 * working". See [catStateDescriptionRes].
 *
 * Blank segments are dropped rather than rendered as empty gaps, so an
 * unconfigured install shows the shorter honest string instead of a row of
 * separators.
 */
internal fun radioSummaryLine(
    rigName: String,
    connectionLabel: String,
    controlLabel: String,
    connectionStateLabel: String = "",
): String = listOf(rigName, connectionLabel, controlLabel, connectionStateLabel)
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .joinToString(" · ")

/**
 * The control-mode label for [radioSummaryLine] — the same four-way vocabulary
 * the Radio & audio screen's PTT selector uses, so the summary and the control
 * it summarizes always say the same word.
 */
internal fun controlModeLabel(controlMode: Int): String = when (controlMode) {
    ControlMode.CAT -> "CAT"
    ControlMode.RTS -> "RTS"
    ControlMode.DTR -> "DTR"
    else -> "VOX"
}
