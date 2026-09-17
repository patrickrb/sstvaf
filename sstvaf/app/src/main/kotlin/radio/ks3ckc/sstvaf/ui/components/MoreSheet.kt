package radio.ks3ckc.sstvaf.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import com.k1af.ft8af.database.ControlMode
import radio.ks3ckc.sstvaf.theme.TextFaint
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary

/** A destination in the overflow sheet. */
enum class MoreDestination {
    /** Rig model, connection, PTT, audio devices and levels. */
    RADIO_AUDIO,

    /** Callsign, grid, antenna and power. */
    OPERATOR,

    /** SSTV QSOs, ADIF export and log upload. */
    LOGBOOK,

    /** Everything else. */
    SETTINGS,
}

/**
 * The sheet behind the header's overflow button: the four places that used to
 * be tabs or were buried in Settings.
 *
 * Each row carries a sub-label describing what is actually in there, and the
 * radio row carries the *live* rig summary — an operator opening this sheet is
 * usually asking "is the rig talking to me?", and answering that on the row
 * itself saves a trip into the screen.
 */
@Composable
fun MoreSheet(
    visible: Boolean,
    radioSummary: String,
    operatorSummary: String,
    onDismiss: () -> Unit,
    onNavigate: (MoreDestination) -> Unit,
) {
    SstvAfBottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.more_title),
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                MoreRow(
                    label = stringResource(R.string.more_radio_audio),
                    subLabel = radioSummary,
                    onClick = { onNavigate(MoreDestination.RADIO_AUDIO) },
                )
                MoreRow(
                    label = stringResource(R.string.more_operator),
                    subLabel = operatorSummary,
                    onClick = { onNavigate(MoreDestination.OPERATOR) },
                )
                MoreRow(
                    label = stringResource(R.string.more_logbook),
                    subLabel = stringResource(R.string.more_logbook_sub),
                    onClick = { onNavigate(MoreDestination.LOGBOOK) },
                )
                MoreRow(
                    label = stringResource(R.string.more_settings),
                    subLabel = stringResource(R.string.more_settings_sub),
                    onClick = { onNavigate(MoreDestination.SETTINGS) },
                )
            }
        }
    }
}

@Composable
private fun MoreRow(
    label: String,
    subLabel: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subLabel,
                color = TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SstvAfIcons.Chevron(size = 16.dp, color = TextFaint, strokeWidth = 1.8f)
    }
}

// ---------------------------------------------------------------------------
// Pure logic (unit-tested — see MoreSheetTest)
// ---------------------------------------------------------------------------

/**
 * The radio row's sub-label, e.g. `"IC-705 · USB cable · CAT"`.
 *
 * Reads as rig, then how it is wired, then what keys it — the three facts an
 * operator checks when transmit does not key. Blank segments are dropped rather
 * than rendered as empty gaps, so an unconfigured install shows the shorter
 * honest string instead of a row of separators.
 */
internal fun radioSummaryLine(
    rigName: String,
    connectionLabel: String,
    controlLabel: String,
): String = listOf(rigName, connectionLabel, controlLabel)
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .joinToString(" · ")

/**
 * The operator row's sub-label, e.g. `"K1AF · FN42"`.
 *
 * An unset callsign falls back to [fallbackCallsign] (the "NO CALL" resource)
 * rather than showing a bare grid: the callsign is the one field that must be
 * set before transmitting, so its absence should be visible from the sheet.
 */
internal fun operatorSummaryLine(
    callsign: String,
    grid: String,
    fallbackCallsign: String,
): String {
    val call = callsign.trim().uppercase().ifEmpty { fallbackCallsign }
    val locator = grid.trim().uppercase()
    return if (locator.isEmpty()) call else "$call · $locator"
}

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
