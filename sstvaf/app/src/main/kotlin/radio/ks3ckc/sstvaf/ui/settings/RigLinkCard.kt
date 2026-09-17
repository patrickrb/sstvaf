package radio.ks3ckc.sstvaf.ui.settings

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.AccentSoft
import radio.ks3ckc.sstvaf.theme.BgSurface
import radio.ks3ckc.sstvaf.theme.BgSurface3
import radio.ks3ckc.sstvaf.theme.Border
import radio.ks3ckc.sstvaf.theme.StatusBad
import radio.ks3ckc.sstvaf.theme.StatusConfirmed
import radio.ks3ckc.sstvaf.theme.StatusWarn
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary
import radio.ks3ckc.sstvaf.ui.components.SstvAfIcons

/**
 * The colour standing for a link state.
 *
 * Kept next to the card rather than in the palette file because it is the same
 * green/amber/red vocabulary the header dot uses, and the two must agree: the
 * header dot is what an operator glances at, and this card is where they come
 * to find out what the dot meant.
 */
internal fun rigLinkColor(state: RigLinkState): Color = when (state) {
    RigLinkState.CONNECTED -> StatusConfirmed
    RigLinkState.CONNECTING -> StatusWarn
    RigLinkState.DISCONNECTED -> StatusBad
    RigLinkState.VOX -> TextMuted
}

/**
 * The card at the top of Radio & audio: what the rig link is doing right now,
 * and the one action that changes it.
 *
 * The border is tinted with the state colour so the answer to "is my rig
 * talking?" is readable before any of the text is, which is what an operator
 * mid-QSO actually needs from this screen.
 */
@Composable
internal fun RigLinkCard(
    state: RigLinkState,
    rigName: String,
    hasRigModel: Boolean,
    detail: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = rigLinkColor(state)
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(BgSurface, shape)
            .border(1.dp, tint.copy(alpha = 0.32f), shape)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Icon tile, tinted to the state.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            SstvAfIcons.Waterfall(
                modifier = Modifier.size(20.dp),
                color = tint,
                strokeWidth = 1.8f,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                LinkDot(state = state, tint = tint)
                Text(
                    text = stringResource(rigLinkTitleRes(state, hasRigModel), rigName),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = detail,
                color = TextMuted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        LinkActionPill(state = state, onAction = onAction)
    }
}

/**
 * The state dot. It pulses only while connecting, because a steady dot means a
 * settled answer and an operator should not have to watch it to know which.
 */
@Composable
private fun LinkDot(state: RigLinkState, tint: Color) {
    val alpha = if (state == RigLinkState.CONNECTING) {
        val transition = rememberInfiniteTransition(label = "linkDot")
        val pulse by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "linkDotAlpha",
        )
        pulse
    } else {
        1f
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = alpha), CircleShape),
    )
}

@Composable
private fun LinkActionPill(state: RigLinkState, onAction: () -> Unit) {
    val enabled = rigLinkActionEnabled(state)
    val primary = rigLinkActionIsPrimary(state)
    val shape = RoundedCornerShape(999.dp)
    val background = when {
        !enabled -> BgSurface3
        primary -> AccentSoft
        else -> BgSurface3
    }
    val textColor = when {
        !enabled -> TextMuted
        primary -> Accent
        else -> TextPrimary
    }

    Box(
        modifier = Modifier
            .clip(shape)
            .background(background, shape)
            .border(1.dp, if (primary && enabled) Accent.copy(alpha = 0.4f) else Border, shape)
            .then(
                if (enabled) {
                    Modifier.clickable(role = Role.Button, onClick = onAction)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            text = stringResource(rigLinkActionRes(state)),
            color = textColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
