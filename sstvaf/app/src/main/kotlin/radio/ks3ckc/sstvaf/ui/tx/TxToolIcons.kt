package radio.ks3ckc.sstvaf.ui.tx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * The six tool-rail glyphs, drawn on the same 24dp grid and stroke weights as
 * [radio.ks3ckc.sstvaf.ui.components.SstvAfIcons].
 *
 * They live here rather than in that object because they are specific to the
 * composer's rail: putting six single-use glyphs into the app-wide icon set
 * would invite them being reused somewhere they do not mean the same thing.
 */
@Composable
internal fun TxToolIcon(tool: TxTool, color: Color, size: androidx.compose.ui.unit.Dp = 20.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.width / 24f
        val stroke = Stroke(width = 1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (tool) {
            // Two overlapping crop corners.
            TxTool.CROP -> {
                val a = Path().apply {
                    moveTo(6f * s, 2f * s); lineTo(6f * s, 16f * s)
                    quadraticBezierTo(6f * s, 18f * s, 8f * s, 18f * s)
                    lineTo(22f * s, 18f * s)
                }
                val b = Path().apply {
                    moveTo(2f * s, 6f * s); lineTo(16f * s, 6f * s)
                    quadraticBezierTo(18f * s, 6f * s, 18f * s, 8f * s)
                    lineTo(18f * s, 22f * s)
                }
                drawPath(a, color, style = stroke)
                drawPath(b, color, style = stroke)
            }
            // A serif "T".
            TxTool.TEXT -> {
                val top = Path().apply {
                    moveTo(5f * s, 6f * s); lineTo(5f * s, 4f * s); lineTo(19f * s, 4f * s)
                    lineTo(19f * s, 6f * s)
                }
                drawPath(top, color, style = stroke)
                drawLine(color, Offset(12f * s, 4f * s), Offset(12f * s, 20f * s), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(9f * s, 20f * s), Offset(15f * s, 20f * s), stroke.width, StrokeCap.Round)
            }
            // A name badge: a card with two text lines.
            TxTool.CALLSIGN -> {
                drawRoundRect(
                    color,
                    topLeft = Offset(4f * s, 6f * s),
                    size = Size(16f * s, 12f * s),
                    cornerRadius = CornerRadius(2f * s, 2f * s),
                    style = stroke,
                )
                drawLine(color, Offset(7f * s, 11f * s), Offset(17f * s, 11f * s), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(7f * s, 15f * s), Offset(13f * s, 15f * s), stroke.width, StrokeCap.Round)
            }
            // A square inside a square.
            TxTool.FRAME -> {
                drawRect(color, topLeft = Offset(3f * s, 3f * s), size = Size(18f * s, 18f * s), style = stroke)
                drawRect(color, topLeft = Offset(7f * s, 7f * s), size = Size(10f * s, 10f * s), style = stroke)
            }
            // Three slider tracks with handles.
            TxTool.ADJUST -> {
                drawLine(color, Offset(4f * s, 7f * s), Offset(20f * s, 7f * s), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(4f * s, 12f * s), Offset(20f * s, 12f * s), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(4f * s, 17f * s), Offset(20f * s, 17f * s), stroke.width, StrokeCap.Round)
                drawCircle(color, 2f * s, Offset(9f * s, 7f * s))
                drawCircle(color, 2f * s, Offset(15f * s, 12f * s))
                drawCircle(color, 2f * s, Offset(7f * s, 17f * s))
            }
            // A pencil on its tail.
            TxTool.DRAW -> {
                val body = Path().apply {
                    moveTo(4f * s, 20f * s); lineTo(8f * s, 19f * s); lineTo(19f * s, 8f * s)
                    lineTo(16f * s, 5f * s); lineTo(5f * s, 16f * s); close()
                }
                drawPath(body, color, style = stroke)
                drawLine(color, Offset(14f * s, 6f * s), Offset(17f * s, 9f * s), stroke.width, StrokeCap.Round)
            }
        }
    }
}
