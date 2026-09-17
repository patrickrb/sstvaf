package radio.ks3ckc.sstvaf.ui.tx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.sstv.SstvMode
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.BgSurface
import radio.ks3ckc.sstvaf.theme.BgSurface3
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.StatusWarn
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary
import radio.ks3ckc.sstvaf.ui.components.SstvAfBottomSheet
import radio.ks3ckc.sstvaf.ui.rx.formatDialFrequency

/**
 * Pre-transmit confirmation: mode, duration, dial frequency, and a reminder
 * that confirming keys the transmitter. [cwTailSeconds] is the airtime the
 * optional CW station-ID tail adds (0 when off) and [voxPreToneSeconds] the
 * VOX pre-tone prepended in VOX control mode (0 otherwise), both folded into
 * the duration line.
 */
@Composable
internal fun TxConfirmSheet(
    visible: Boolean,
    mode: SstvMode,
    cwTailSeconds: Double,
    voxPreToneSeconds: Double = 0.0,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    SstvAfBottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.tx_confirm_title),
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = confirmDurationLine(mode, cwTailSeconds, voxPreToneSeconds),
                color = TextPrimary,
                fontSize = 14.sp,
                fontFamily = GeistMonoFamily,
            )
            Spacer(Modifier.height(2.dp))
            // Plain-language airtime class under the exact seconds, so the
            // operator gauges the on-air commitment at the moment they commit.
            Text(
                text = stringResource(txAirtimeClass(mode, cwTailSeconds).labelRes),
                color = TextMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatDialFrequency(GeneralVariables.band),
                color = TextMuted,
                fontSize = 13.sp,
                fontFamily = GeistMonoFamily,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.tx_confirm_warning),
                color = StatusWarn,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tx_confirm_cancel))
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) {
                    Text(stringResource(R.string.tx_confirm_go), color = Color.Black)
                }
            }
        }
    }
}
