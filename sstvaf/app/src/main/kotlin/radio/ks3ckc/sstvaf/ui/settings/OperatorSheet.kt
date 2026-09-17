package radio.ks3ckc.sstvaf.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.AccentSoft
import radio.ks3ckc.sstvaf.theme.BgSurface2
import radio.ks3ckc.sstvaf.theme.BgSurface3
import radio.ks3ckc.sstvaf.theme.Border
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.TextFaint
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary
import radio.ks3ckc.sstvaf.ui.components.SstvAfBottomSheet

/**
 * The operator identity editor: callsign, grid, antenna and power.
 *
 * A sheet rather than the dialog this replaces, because the dialog put four
 * fields plus a keyboard into a centred box that was cut off in landscape on a
 * phone. A sheet is anchored to the bottom, so the keyboard pushes it up
 * instead of over it.
 *
 * Saving normalises through the functions in OperatorEdit.kt; the caller
 * receives values already in the form they should be stored in.
 */
@Composable
internal fun OperatorSheet(
    visible: Boolean,
    initialCallsign: String,
    initialGrid: String,
    initialAntenna: String,
    initialPowerWatts: Int,
    onDismiss: () -> Unit,
    onSave: (callsign: String, grid: String, antenna: String, powerWatts: Int) -> Unit,
) {
    // Keyed on visible so reopening the sheet shows the stored values again
    // rather than whatever was abandoned last time.
    var callsign by remember(visible) { mutableStateOf(initialCallsign) }
    var grid by remember(visible) { mutableStateOf(initialGrid) }
    var antenna by remember(visible) { mutableStateOf(initialAntenna) }
    var power by remember(visible) {
        mutableStateOf(if (initialPowerWatts > 0) initialPowerWatts.toString() else "")
    }

    SstvAfBottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.operator_sheet_title),
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )

            OperatorField(
                label = stringResource(R.string.settings_callsign),
                value = callsign,
                placeholder = stringResource(R.string.settings_callsign_hint),
                onValueChange = { callsign = it },
                mono = true,
            )
            OperatorField(
                label = stringResource(R.string.settings_grid_locator),
                value = grid,
                placeholder = stringResource(R.string.settings_grid_locator_hint),
                onValueChange = { grid = it },
                mono = true,
            )
            OperatorField(
                label = stringResource(R.string.operator_antenna),
                value = antenna,
                placeholder = stringResource(R.string.operator_antenna_hint),
                onValueChange = { antenna = it },
            )
            OperatorField(
                label = stringResource(R.string.operator_power),
                value = power,
                placeholder = stringResource(R.string.operator_power_hint),
                onValueChange = { entered ->
                    if (entered.all { it.isDigit() }) power = entered
                },
                keyboardType = KeyboardType.Number,
                mono = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SheetButton(
                    label = stringResource(R.string.action_cancel),
                    primary = false,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                SheetButton(
                    label = stringResource(R.string.action_save),
                    primary = true,
                    onClick = {
                        onSave(
                            normalizeCallsign(callsign),
                            normalizeGrid(grid),
                            normalizeAntenna(antenna),
                            parsePowerWatts(power),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun OperatorField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    mono: Boolean = false,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(BgSurface2, shape)
                .border(1.dp, Border, shape)
                .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            if (value.isEmpty()) {
                Text(text = placeholder, color = TextFaint, fontSize = 14.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontFamily = if (mono) GeistMonoFamily else FontFamily.Default,
                ),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SheetButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (primary) AccentSoft else BgSurface3, shape)
            .border(1.dp, if (primary) Accent.copy(alpha = 0.4f) else Border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (primary) Accent else TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
