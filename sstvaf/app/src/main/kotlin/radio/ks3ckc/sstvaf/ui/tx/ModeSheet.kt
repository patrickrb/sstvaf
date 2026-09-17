package radio.ks3ckc.sstvaf.ui.tx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.sstv.SstvMode
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.BgApp
import radio.ks3ckc.sstvaf.theme.BgSurface2
import radio.ks3ckc.sstvaf.theme.Border
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.Signal
import radio.ks3ckc.sstvaf.theme.StatusConfirmed
import radio.ks3ckc.sstvaf.theme.TextDim
import radio.ks3ckc.sstvaf.theme.TextFaint
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary
import radio.ks3ckc.sstvaf.ui.components.SstvAfBottomSheet
import radio.ks3ckc.sstvaf.ui.components.SstvAfIcons

/**
 * The SSTV mode picker, opened from the Send screen's mode card.
 *
 * A sheet, not the chip row it replaces. The row put sixteen modes in a
 * horizontal scroller where only three were visible at once, gave every one
 * equal weight, and made the operator scroll to find out what the choice even
 * was. Almost everyone sends Scottie 1 almost always, so the Send screen shows
 * that one as a pill and the full list lives one tap away — grouped by the
 * trade-off that actually drives the decision (see [modeSpeedGroup]) with
 * dimensions and duration on every row.
 *
 * Receive has no mode picker anywhere, which the header note states outright:
 * SSTV carries its mode in the VIS header, so the decoder reads it. Only
 * transmit needs a choice.
 */
@Composable
internal fun ModeSheet(
    visible: Boolean,
    selected: SstvMode,
    onDismiss: () -> Unit,
    onSelect: (SstvMode) -> Unit,
) {
    SstvAfBottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.mode_sheet_title),
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.mode_sheet_rx_note),
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = GeistMonoFamily,
                )
            }

            for (group in modeGroups()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(modeSpeedGroupLabelRes(group.group)),
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
                        color = TextFaint,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.06.sp,
                    )
                    for (mode in group.modes) {
                        ModeRow(
                            mode = mode,
                            selected = mode == selected,
                            onClick = { onSelect(mode) },
                        )
                    }
                }
            }
        }
    }
}

/** One mode: name, dimensions, duration, and a radio circle. */
@Composable
private fun ModeRow(
    mode: SstvMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val description = stringResource(
        R.string.mode_row_description,
        mode.displayName,
        modeResolutionLabel(mode),
        modeDurationLabel(mode),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Accent.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClickLabel = description, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = mode.displayName,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = modeSubLabel(mode, modeNoteRes(mode)?.let { stringResource(it) }),
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = GeistMonoFamily,
                maxLines = 1,
            )
        }
        Text(
            text = modeDurationLabel(mode),
            color = modeDurationColor(modeDurationTone(mode)),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = GeistMonoFamily,
        )
        SelectionRing(selected = selected)
    }
}

/** The 20dp radio circle: accent-filled with a dark check when chosen. */
@Composable
private fun SelectionRing(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (selected) Accent else Color.Transparent)
            .border(1.5.dp, if (selected) Accent else TextDim, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            SstvAfIcons.Check(size = 12.dp, color = BgApp, strokeWidth = 3f)
        }
    }
}

/**
 * The Send screen's mode card: an uppercase MODE label over the current mode
 * name, with a chevron. Tapping opens [ModeSheet].
 *
 * Replaces the chip row. One mode is shown because one mode is what the
 * operator is sending; the rest are a tap away rather than permanently
 * occupying a scroller.
 */
@Composable
internal fun ModeCard(
    mode: SstvMode,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.mode_card_description, mode.displayName)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface2)
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .clickable(
                enabled = enabled,
                onClickLabel = description,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = stringResource(R.string.mode_card_label),
            color = TextFaint,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.06.sp,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = mode.displayName,
                color = if (enabled) TextPrimary else TextFaint,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistMonoFamily,
                maxLines = 1,
                softWrap = false,
            )
            SstvAfIcons.ChevronDown(size = 11.dp, color = TextMuted, strokeWidth = 2.2f)
        }
    }
}

// ---------------------------------------------------------------------------
// Resource / colour mapping (kept next to the composables that use it)
// ---------------------------------------------------------------------------

/** Section header for a speed group. */
internal fun modeSpeedGroupLabelRes(group: ModeSpeedGroup): Int = when (group) {
    ModeSpeedGroup.FAST -> R.string.mode_group_fast
    ModeSpeedGroup.STANDARD -> R.string.mode_group_standard
    ModeSpeedGroup.HIGH_RESOLUTION -> R.string.mode_group_high_resolution
}

/**
 * The note appended to a mode's dimensions line, or null for most modes.
 *
 * Only Scottie 1 carries one. It is the mode an operator will actually hear on
 * 20m, and saying so is the most useful thing this sheet can tell someone who
 * does not yet know which of sixteen names to pick. Annotating every mode would
 * bury that.
 */
internal fun modeNoteRes(mode: SstvMode): Int? = when (mode) {
    SstvMode.SCOTTIE_1 -> R.string.mode_note_most_common
    else -> null
}

/**
 * Duration colour: green when it is cheap to send, plain text for the normal
 * band, amber once the transmission is long enough to matter to whoever else
 * wants the frequency.
 */
internal fun modeDurationColor(tone: ModeDurationTone): Color = when (tone) {
    ModeDurationTone.QUICK -> StatusConfirmed
    ModeDurationTone.NORMAL -> TextPrimary
    ModeDurationTone.LONG -> Signal
}
