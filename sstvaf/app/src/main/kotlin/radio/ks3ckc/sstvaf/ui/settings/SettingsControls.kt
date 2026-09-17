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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.AccentSoft
import radio.ks3ckc.sstvaf.theme.BgSurface2
import radio.ks3ckc.sstvaf.theme.BgSurface3
import radio.ks3ckc.sstvaf.theme.Border
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.TextFaint
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.selection.selectable

/**
 * The redesign shared settings controls: a segmented selector, a chip row, a
 * slider row and a label/value row. Radio & audio is built almost entirely out
 * of these four, which is what keeps a screen with this many options from
 * reading as a wall of identical rows.
 */

/**
 * A horizontal segmented selector: one visible control, every choice legible at
 * once, and the current one filled.
 *
 * Preferred over a row that opens a picker dialog wherever there are two to
 * four choices, because the whole point of these particular settings is that an
 * operator chasing a fault wants to see what the alternatives are without
 * committing to a dialog first.
 *
 * [label] is the display text for a choice. [contentDescription] supplies the
 * accessible name where the label alone is too terse to speak aloud, as the
 * three-letter PTT options are.
 */
@Composable
internal fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: (@Composable (T) -> String)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(BgSurface2, shape)
            .border(1.dp, Border, shape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val segmentShape = RoundedCornerShape(8.dp)
            val segmentName = contentDescription?.invoke(option)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(segmentShape)
                    .background(if (isSelected) AccentSoft else BgSurface2, segmentShape)
                    // selectable, not clickable: only the selected state tells
                    // a screen reader which segment is active, and the fill is
                    // the sole visual cue. The description names the control
                    // rather than the action - onClickLabel, which this used,
                    // only changes the spoken action hint.
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) },
                    )
                    .then(
                        if (segmentName != null) {
                            Modifier.semantics { this.contentDescription = segmentName }
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    color = if (isSelected) Accent else TextMuted,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The explanatory line under a [SegmentedChoice]: what the current choice
 * actually does.
 *
 * Kept as a single line that changes with the selection rather than a legend
 * covering all of them, so the screen says one true thing at a time.
 */
@Composable
internal fun ChoiceHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = TextFaint,
        fontSize = 12.sp,
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

/**
 * A row of small selectable chips, for a short list of fixed numeric values
 * such as the baud rates, where a segmented control would be too wide to read.
 */
@Composable
internal fun <T> ChipChoiceRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val shape = RoundedCornerShape(8.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(if (isSelected) AccentSoft else BgSurface3, shape)
                    .border(1.dp, if (isSelected) Accent.copy(alpha = 0.4f) else Border, shape)
                    // Same reasoning as the segmented control: the chips are
                    // one of a set, and which one is current has to be spoken.
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) },
                    )
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    color = if (isSelected) Accent else TextMuted,
                    fontFamily = GeistMonoFamily,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * A labelled slider with its current value shown in mono on the right.
 *
 * [onValueChange] fires continuously while dragging so the reading tracks the
 * thumb, and [onValueChangeFinished] is where the caller persists. Dragging a
 * slider across its range would otherwise write the config row on every frame.
 */
@Composable
internal fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    description: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, color = TextPrimary, fontSize = 14.sp)
            Text(
                text = valueLabel,
                color = Accent,
                fontFamily = GeistMonoFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (description != null) {
            Text(text = description, color = TextMuted, fontSize = 12.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = BgSurface3,
            ),
        )
    }
}

/**
 * A label with a tappable value chip on the right, for a setting whose value is
 * a name rather than a number, such as an audio device or the rig model.
 */
@Composable
internal fun ValueChipRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = label, color = TextPrimary, fontSize = 14.sp)
            if (description != null) {
                Text(text = description, color = TextMuted, fontSize = 12.sp)
            }
        }
        val shape = RoundedCornerShape(8.dp)
        Box(
            modifier = Modifier
                .clip(shape)
                .background(BgSurface3, shape)
                .border(1.dp, Border, shape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = value,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
