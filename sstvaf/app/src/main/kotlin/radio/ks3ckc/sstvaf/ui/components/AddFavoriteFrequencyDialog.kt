package radio.ks3ckc.sstvaf.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.k1af.ft8af.R
import com.k1af.ft8af.rigs.BaseRigOperation
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.BgApp
import radio.ks3ckc.sstvaf.theme.BgSurface2
import radio.ks3ckc.sstvaf.theme.BgSurface3
import radio.ks3ckc.sstvaf.theme.Border
import radio.ks3ckc.sstvaf.theme.BorderStrong
import radio.ks3ckc.sstvaf.theme.StatusBad
import radio.ks3ckc.sstvaf.theme.TextFaint
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary

/**
 * Centered modal for saving a favourite frequency: a dial and an optional
 * short name.
 *
 * The dial field is pre-filled with [initialFreqHz] (the current dial) so the
 * common case, "remember where I am", is a tap on Save. Typing another
 * frequency works too, in MHz, kHz or Hz, with a decimal point or comma (see
 * [parseFrequencyInputHz]); the band the typed dial falls in is echoed under
 * the field so a slipped decimal point shows up before it is saved.
 */
@Composable
fun AddFavoriteFrequencyDialog(
    visible: Boolean,
    initialFreqHz: Long,
    alreadySavedHz: Set<Long>,
    onDismiss: () -> Unit,
    onSave: (freqHz: Long, label: String) -> Unit,
) {
    if (!visible) return

    var freqText by rememberSaveable(initialFreqHz) { mutableStateOf(favoriteInputText(initialFreqHz)) }
    var labelText by rememberSaveable { mutableStateOf("") }

    val parsedHz = parseFrequencyInputHz(freqText)
    val showError = freqText.isNotBlank() && parsedHz == null
    val replacing = parsedHz != null && alreadySavedHz.contains(parsedHz)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(BgSurface2)
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Text(
                text = stringResource(R.string.freq_favorite_add_title),
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(modifier = Modifier.height(14.dp))

            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Accent,
                focusedBorderColor = Accent,
                unfocusedBorderColor = BorderStrong,
                focusedLabelColor = Accent,
                unfocusedLabelColor = TextMuted,
                errorBorderColor = StatusBad,
                errorLabelColor = StatusBad,
            )

            OutlinedTextField(
                value = freqText,
                onValueChange = { freqText = it },
                label = { Text(stringResource(R.string.freq_favorite_freq_label)) },
                singleLine = true,
                isError = showError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when {
                    showError -> stringResource(R.string.freq_favorite_invalid)
                    parsedHz != null -> stringResource(
                        R.string.freq_favorite_resolves_to,
                        BaseRigOperation.getMeterFromFreq(parsedHz),
                        formatMhz(parsedHz),
                    )
                    else -> stringResource(R.string.freq_favorite_invalid)
                },
                color = if (showError) StatusBad else TextFaint,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = labelText,
                onValueChange = { labelText = it.take(FAVORITE_LABEL_MAX_LENGTH) },
                label = { Text(stringResource(R.string.freq_favorite_name_label)) },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FavoriteDialogButton(
                    label = stringResource(R.string.action_cancel),
                    enabled = true,
                    primary = false,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
                FavoriteDialogButton(
                    label = stringResource(
                        if (replacing) R.string.freq_favorite_update else R.string.action_save,
                    ),
                    enabled = parsedHz != null,
                    primary = true,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val hz = parsedHz ?: return@FavoriteDialogButton
                        onSave(hz, sanitizeFavoriteLabel(labelText))
                    },
                )
            }
        }
    }
}

@Composable
private fun FavoriteDialogButton(
    label: String,
    enabled: Boolean,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> BgSurface3
        primary -> Accent
        else -> BgSurface3
    }
    val textColor = when {
        !enabled -> TextFaint
        primary -> BgApp
        else -> TextPrimary
    }
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .then(if (primary) Modifier else Modifier.border(1.dp, Border, RoundedCornerShape(12.dp)))
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
    }
}
