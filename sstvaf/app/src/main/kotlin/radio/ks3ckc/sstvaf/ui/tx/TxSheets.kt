package radio.ks3ckc.sstvaf.ui.tx

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.sstv.SstvMode
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.BgApp
import radio.ks3ckc.sstvaf.theme.BgSurface
import radio.ks3ckc.sstvaf.theme.BgSurface3
import radio.ks3ckc.sstvaf.theme.BorderStrong
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
    preview: Bitmap?,
    txLevelPercent: Int,
    bandLabel: String,
    cwTailSeconds: Double,
    voxPreToneSeconds: Double = 0.0,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
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
                text = stringResource(R.string.tx_confirm_title),
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The actual flattened composite, at the mode's aspect ratio.
                // This is the last chance to notice a missing callsign or a
                // crop that cut someone's head off, so it has to be the real
                // thing rather than a thumbnail of the source photo.
                if (preview != null) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = stringResource(R.string.tx_preview_description),
                        modifier = Modifier
                            .width(120.dp)
                            .aspectRatio(mode.width.toFloat() / mode.height)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BgSurface),
                        contentScale = ContentScale.FillBounds,
                        filterQuality = FilterQuality.None,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = confirmDurationLine(mode, cwTailSeconds, voxPreToneSeconds),
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontFamily = GeistMonoFamily,
                    )
                    Text(
                        text = stringResource(txAirtimeClass(mode, cwTailSeconds).labelRes),
                        color = TextMuted,
                        fontSize = 12.sp,
                    )
                    Text(
                        text = confirmFrequencyLine(GeneralVariables.band, bandLabel),
                        color = TextMuted,
                        fontSize = 13.sp,
                        fontFamily = GeistMonoFamily,
                    )
                    Text(
                        // TX level belongs on this sheet: it is the one setting
                        // that decides whether the transmission is clean or
                        // over-driven, it lives two taps away in the Frequency
                        // sheet, and this is the moment it stops being
                        // adjustable.
                        text = stringResource(R.string.tx_confirm_level, txLevelPercent),
                        color = TextMuted,
                        fontSize = 13.sp,
                        fontFamily = GeistMonoFamily,
                    )
                }
            }

            Text(
                text = stringResource(R.string.tx_confirm_warning),
                color = StatusWarn,
                fontSize = 12.sp,
            )

            // Equal-weight buttons, 48dp: confirming keys a transmitter, so
            // Cancel is the same size and just as easy to hit. The accent fill
            // marks which one proceeds without making the other a hard target.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ConfirmButton(
                    label = stringResource(R.string.tx_confirm_cancel),
                    background = Color.Transparent,
                    textColor = TextPrimary,
                    borderColor = BorderStrong,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
                ConfirmButton(
                    label = stringResource(R.string.tx_confirm_go),
                    background = Accent,
                    textColor = BgApp,
                    borderColor = Color.Transparent,
                    modifier = Modifier.weight(1f),
                    onClick = onConfirm,
                )
            }
        }
    }
}

/** One of the confirm sheet's two equal 48dp buttons. */
@Composable
private fun ConfirmButton(
    label: String,
    background: Color,
    textColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
