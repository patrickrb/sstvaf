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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.AccentSoft
import radio.ks3ckc.sstvaf.theme.BgSurface2
import radio.ks3ckc.sstvaf.theme.BorderAmber
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.StatusConfirmed
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary

/**
 * The operator identity card at the top of Settings: the callsign this station
 * transmits under, what it is running, and whether the rig is answering.
 *
 * The callsign gets the largest type on the screen because it is the one piece
 * of configuration that goes out over the air on every transmission, and a
 * wrong one is both useless to the receiving operator and, in most
 * jurisdictions, not legal. Making it unmissable is the point of the card.
 *
 * [onEdit] opens the operator sheet; [rigStatus] is the pre-composed status
 * sentence from [rigStatusLine].
 */
@Composable
fun OperatorCard(
    callsign: String,
    detailLine: String,
    rigStatus: String,
    rigConnected: Boolean,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(AccentSoft, Color(0x0AFFAF5E)),
                    start = Offset.Zero,
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                ),
                shape = shape,
            )
            .border(1.dp, BorderAmber, shape)
            .drawBehind {
                // Decorative glow in the top-right corner.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Accent.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width * 0.92f, size.height * 0.08f),
                        radius = size.minDimension * 0.55f,
                    ),
                    radius = size.minDimension * 0.55f,
                    center = Offset(size.width * 0.92f, size.height * 0.08f),
                )
            }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = callsign.uppercase().ifEmpty { stringResource(R.string.op_no_call) },
                    color = TextPrimary,
                    fontFamily = GeistMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                    letterSpacing = 1.0.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detailLine.ifEmpty { stringResource(R.string.op_no_grid) },
                    color = TextMuted,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = stringResource(R.string.action_edit),
                color = Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.operator_sheet_title),
                        onClick = onEdit,
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        // Inset rig-status strip. Its own surface, so it reads as a live
        // readout rather than as another line of the operator identity.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(BgSurface2.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(
                        if (rigConnected) StatusConfirmed else TextMuted,
                        CircleShape,
                    ),
            )
            Text(
                text = rigStatus,
                color = TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
