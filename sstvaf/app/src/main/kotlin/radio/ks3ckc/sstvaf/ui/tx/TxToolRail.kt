package radio.ks3ckc.sstvaf.ui.tx

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.TextMuted

/**
 * The composer's six tools.
 *
 * Six inline tools with their controls right under the canvas, rather than the
 * form-and-sub-sheet arrangement this replaces. The old composer put text
 * editing behind a modal sheet, which meant the operator could not see the
 * picture while typing the text that was going onto it. Every tool here keeps
 * the canvas visible.
 */
enum class TxTool(@StringRes val labelRes: Int) {
    /** Reposition and zoom the picture inside the mode's frame. */
    CROP(R.string.tx_tool_crop),

    /** Add and edit free text overlays. */
    TEXT(R.string.tx_tool_text),

    /** Stamp the operator's callsign at one of six positions. */
    CALLSIGN(R.string.tx_tool_callsign),

    /** Pick the border burned into the picture. */
    FRAME(R.string.tx_tool_frame),

    /** Brightness, contrast, saturation. */
    ADJUST(R.string.tx_tool_adjust),

    /** Freehand strokes. */
    DRAW(R.string.tx_tool_draw),
}

/**
 * The tool rail: six equal columns under the canvas, the active one filled with
 * a soft accent.
 *
 * Equal columns and always all six visible. A scroller or an overflow menu
 * would hide tools the operator has not learned about yet, and six is few
 * enough to fit across a compact phone at this size.
 */
@Composable
internal fun TxToolRail(
    active: TxTool,
    enabled: Boolean,
    onSelect: (TxTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (tool in TxTool.entries) {
            val isActive = tool == active
            val tint = if (!enabled) {
                TextMuted.copy(alpha = 0.4f)
            } else if (isActive) {
                Accent
            } else {
                TextMuted
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isActive) Accent.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable(enabled = enabled, role = Role.Tab) { onSelect(tool) }
                    .padding(top = 8.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                TxToolIcon(tool = tool, color = tint)
                Text(
                    text = stringResource(tool.labelRes),
                    color = tint,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}
