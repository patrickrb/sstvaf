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

/**
 * Add/edit one [TextOverlay]: text, color swatch, S/M/L size, position
 * preset grid, BAR/OUTLINE style.
 */
@Composable
internal fun OverlayEditorSheet(
    initial: TextOverlay?,
    onDismiss: () -> Unit,
    onSave: (TextOverlay) -> Unit,
) {
    var text by remember { mutableStateOf(initial?.text ?: "") }
    var colorArgb by remember { mutableStateOf(initial?.colorArgb ?: OVERLAY_COLOR_WHITE) }
    var sizeFraction by remember { mutableStateOf(initial?.sizeFraction ?: OVERLAY_SIZE_MEDIUM) }
    var position by remember { mutableStateOf(initial?.position ?: OverlayPosition.TOP_BAR) }
    var style by remember { mutableStateOf(initial?.style ?: OverlayStyle.BAR) }

    SstvAfBottomSheet(visible = true, onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(
                    if (initial == null) R.string.tx_overlay_add else R.string.tx_overlay_edit,
                ),
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.tx_overlay_text_label)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    focusedLabelColor = Accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            // Color swatches
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OVERLAY_COLOR_SWATCHES.forEach { swatch ->
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(swatch))
                            .border(
                                width = if (swatch == colorArgb) 3.dp else 1.dp,
                                color = if (swatch == colorArgb) Accent else BgSurface3,
                                shape = CircleShape,
                            )
                            .clickable { colorArgb = swatch },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // Size presets
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "S" to OVERLAY_SIZE_SMALL,
                    "M" to OVERLAY_SIZE_MEDIUM,
                    "L" to OVERLAY_SIZE_LARGE,
                ).forEach { (label, fraction) ->
                    SelectChip(
                        label = label,
                        selected = sizeFraction == fraction,
                        onClick = { sizeFraction = fraction },
                    )
                }
                Spacer(Modifier.weight(1f))
                // Style toggle
                OverlayStyle.entries.forEach { s ->
                    SelectChip(
                        label = s.name,
                        selected = style == s,
                        onClick = { style = s },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // Position preset grid (two rows)
            listOf(
                listOf(OverlayPosition.TOP_LEFT, OverlayPosition.TOP_BAR, OverlayPosition.TOP_RIGHT),
                listOf(OverlayPosition.BOTTOM_LEFT, OverlayPosition.BOTTOM_BAR, OverlayPosition.BOTTOM_RIGHT),
                listOf(OverlayPosition.CENTER),
            ).forEach { rowPositions ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 2.dp),
                ) {
                    rowPositions.forEach { p ->
                        SelectChip(
                            label = p.name.replace('_', ' '),
                            selected = position == p,
                            onClick = { position = p },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tx_confirm_cancel))
                }
                Button(
                    onClick = {
                        onSave(TextOverlay(text.trim(), colorArgb, sizeFraction, position, style))
                    },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) {
                    Text(stringResource(R.string.tx_overlay_save), color = Color.Black)
                }
            }
        }
    }
}

/** Small selectable chip used by the editor's preset rows. */
@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Color.Black else TextPrimary,
        fontSize = 11.sp,
        fontFamily = GeistMonoFamily,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Accent else BgSurface)
            .clickable(enabled = !selected) { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
