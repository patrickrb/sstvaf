package radio.ks3ckc.sstvaf.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.ks3ckc.sstvaf.theme.BgSurface
import radio.ks3ckc.sstvaf.theme.BgSurface3
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary
import androidx.compose.foundation.selection.selectable

/**
 * The gallery's All / Received / Sent filter, as one segmented pill.
 *
 * A segmented control rather than the chip row it replaces, because these three
 * options are mutually exclusive and cover everything — a chip row reads as
 * "add filters", a segment reads as "pick one view", which is what this is. It
 * also fixes the row's width: chips sized to their labels shifted every time a
 * count changed from 9 to 10.
 */
@Composable
internal fun GallerySegmentedControl(
    options: List<GalleryFilter>,
    selected: GalleryFilter,
    label: (GalleryFilter) -> String,
    onSelected: (GalleryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(BgSurface)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        for (option in options) {
            val isSelected = option == selected
            // The 32dp pill is the visual; the touch target around it is
            // 48dp. These are the three primary gallery filters, and a 32dp
            // target is below the minimum anyone with a motor impairment can
            // reliably hit.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .selectable(
                        selected = isSelected,
                        role = Role.Tab,
                        onClick = { onSelected(option) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (isSelected) BgSurface3 else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label(option),
                        color = if (isSelected) TextPrimary else TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
