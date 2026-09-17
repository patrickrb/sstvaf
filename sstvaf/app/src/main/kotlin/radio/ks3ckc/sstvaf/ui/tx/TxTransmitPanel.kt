package radio.ks3ckc.sstvaf.ui.tx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.sstv.SstvMode
import radio.ks3ckc.sstvaf.theme.BgSurface3
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.Signal
import radio.ks3ckc.sstvaf.theme.StatusBad
import radio.ks3ckc.sstvaf.theme.TextPrimary
import radio.ks3ckc.sstvaf.theme.TextMuted
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer

/**
 * The amber card that replaces the mode row while the rig is keyed.
 *
 * Amber throughout, and it takes over the row the Transmit button occupied.
 * Both are on purpose: the app's amber means "RF is going out", and putting
 * the live state exactly where the operator just tapped means they cannot
 * miss that something changed. The Transmit button is not merely disabled
 * during a transmission — it is gone, replaced by the thing that stops it.
 *
 * Stop is outlined in red rather than filled: it is the one control here, so
 * it has to be unmistakable, but a solid red slab next to an amber card would
 * read as an error rather than an action.
 */
@Composable
internal fun TxTransmitPanel(
    mode: SstvMode,
    progress: Float,
    totalSeconds: Double,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(Signal.copy(alpha = 0.12f), Signal.copy(alpha = 0.04f)),
                ),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // A solid dot, not a pulsing one: the breathing ring around the
                // canvas already carries the "this is live" animation, and two
                // competing heartbeats read as a glitch.
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Signal),
                )
                Text(
                    text = stringResource(R.string.tx_transmitting_label, mode.displayName),
                    color = Signal,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GeistMonoFamily,
                    maxLines = 1,
                )
            }
            Text(
                text = txElapsedLabel(progress, totalSeconds),
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = GeistMonoFamily,
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.width(8.dp))
            // Time left, restored from the panel this replaced. On a PD 290 the
            // difference between "0:40 / 4:48" and "4:08 left" is the
            // difference between glancing and doing arithmetic while the rig is
            // keyed.
            Text(
                text = stringResource(
                    R.string.tx_remaining_format,
                    txRemainingLabel(progress, totalSeconds),
                ),
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = GeistMonoFamily,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(BgSurface3),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Signal),
                )
            }
            val stopLabel = stringResource(R.string.tx_stop_action)
            Box(
                modifier = Modifier
                    // 48dp minimum. This is the control that drops a keyed
                    // transmitter, so it is the last one that should need a
                    // careful aim - it was about text height plus 12dp.
                    .heightIn(min = 48.dp)
                    .widthIn(min = 48.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .border(1.dp, StatusBad.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                    .clickable(role = Role.Button, onClick = onCancel)
                    .semantics { contentDescription = stopLabel }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stopLabel,
                    color = StatusBad,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
