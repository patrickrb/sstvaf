package radio.ks3ckc.sstvaf.ui.logbook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.sstv.SstvMode
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.BgSurface
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.StatusBad
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary
import radio.ks3ckc.sstvaf.ui.components.SstvAfBottomSheet

/**
 * Manual SSTV QSO entry sheet. All decision logic lives in ManualQsoLogic.kt
 * (validated fields, MHz parsing, QSLRecord construction) — this Composable
 * only holds the field state and renders it.
 *
 * Compose it conditionally (like the TX overlay editor sheet): the caller
 * includes it only while open, so a reopened sheet starts with fresh state.
 *
 * @param initialFreqMhz the MHz text the frequency field opens with
 *   (from the current dial frequency).
 * @param initialMode the SSTV mode preselected in the picker (the last-used
 *   TX mode).
 * @param onSave called with the validated input; the caller builds the
 *   QSLRecord and inserts it.
 */
@Composable
internal fun ManualQsoSheet(
    initialFreqMhz: String,
    initialMode: SstvMode,
    onDismiss: () -> Unit,
    onSave: (ManualQsoInput) -> Unit,
) {
    var callsign by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf("") }
    var rsvSent by remember { mutableStateOf(DEFAULT_RSV) }
    var rsvReceived by remember { mutableStateOf(DEFAULT_RSV) }
    var freqMhz by remember { mutableStateOf(initialFreqMhz) }
    var mode by remember { mutableStateOf(initialMode) }
    var comment by remember { mutableStateOf("") }
    // Errors are only shown after the first save attempt, so an untouched
    // form doesn't open covered in red.
    var showErrors by remember { mutableStateOf(false) }

    val input = ManualQsoInput(
        callsign = callsign,
        grid = grid,
        rsvSent = rsvSent,
        rsvReceived = rsvReceived,
        freqMhz = freqMhz,
        mode = mode,
        comment = comment,
    )
    val errors = validateManualQso(input)

    SstvAfBottomSheet(visible = true, onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.log_manual_title),
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = callsign,
                onValueChange = { callsign = it.uppercase() },
                singleLine = true,
                isError = showErrors && ManualQsoField.CALLSIGN in errors,
                label = { Text(stringResource(R.string.log_manual_callsign)) },
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = grid,
                onValueChange = { grid = it },
                singleLine = true,
                isError = showErrors && ManualQsoField.GRID in errors,
                label = { Text(stringResource(R.string.log_manual_grid)) },
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = rsvSent,
                    onValueChange = { rsvSent = it },
                    singleLine = true,
                    isError = showErrors && ManualQsoField.RSV_SENT in errors,
                    label = { Text(stringResource(R.string.log_manual_rsv_sent)) },
                    colors = fieldColors(),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = rsvReceived,
                    onValueChange = { rsvReceived = it },
                    singleLine = true,
                    isError = showErrors && ManualQsoField.RSV_RECEIVED in errors,
                    label = { Text(stringResource(R.string.log_manual_rsv_rcvd)) },
                    colors = fieldColors(),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = freqMhz,
                onValueChange = { freqMhz = it },
                singleLine = true,
                isError = showErrors && ManualQsoField.FREQUENCY in errors,
                label = { Text(stringResource(R.string.log_manual_freq_mhz)) },
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.log_manual_mode),
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = GeistMonoFamily,
            )
            Spacer(Modifier.height(6.dp))
            // Mode picker — all SSTV modes in rows of up to three chips (the
            // last row holds the remainder when the count isn't a multiple of 3).
            SstvMode.entries.chunked(3).forEach { rowModes ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 2.dp),
                ) {
                    rowModes.forEach { m ->
                        ModeChip(
                            label = m.displayName,
                            selected = mode == m,
                            onClick = { mode = m },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it },
                singleLine = true,
                label = { Text(stringResource(R.string.log_manual_comment)) },
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.log_manual_time_note),
                color = if (showErrors && errors.isNotEmpty()) StatusBad else TextMuted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.log_manual_cancel))
                }
                Button(
                    onClick = {
                        if (errors.isEmpty()) {
                            onSave(input)
                        } else {
                            showErrors = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) {
                    Text(stringResource(R.string.log_manual_save), color = Color.Black)
                }
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent,
    focusedLabelColor = Accent,
)

/** Small selectable chip used by the mode picker rows. */
@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
