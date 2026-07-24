package radio.ks3ckc.sstvaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.ks3ckc.sstvaf.theme.*

/**
 * A horizontal row of selectable pill chips, keyed by an arbitrary [options]
 * value [T] (an enum, a domain object, …). The chip's visible text comes from
 * [label] and selection is compared by value equality, so callers keep the
 * typed key end to end — [onSelected] hands back the chosen [T] directly rather
 * than its rendered label, avoiding a fragile text→key reverse lookup that
 * localization or a formatting change (e.g. appended counts) could break.
 */
@Composable
fun <T> FilterChips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val shape = RoundedCornerShape(999.dp)

    Row(
        modifier = modifier
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (option in options) {
            val isSelected = option == selected
            val bgColor = if (isSelected) AccentSoft else BgSurface2
            val borderColor = if (isSelected) BorderAmber else Border
            val textColor = if (isSelected) Accent else TextMuted

            Row(
                modifier = Modifier
                    .height(32.dp)
                    .clip(shape)
                    .background(bgColor, shape)
                    .border(1.dp, borderColor, shape)
                    .clickable { onSelected(option) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label(option),
                    color = textColor,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    letterSpacing = 0.02.sp,
                )
            }
        }
    }
}
