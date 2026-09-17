package radio.ks3ckc.sstvaf.ui.rx

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.BgSurface
import radio.ks3ckc.sstvaf.theme.BgSurface3
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.Signal
import radio.ks3ckc.sstvaf.theme.StatusBad
import radio.ks3ckc.sstvaf.theme.StatusConfirmed
import radio.ks3ckc.sstvaf.theme.TextFaint
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary

/**
 * The status card under the Receive canvas: what the receiver is doing, how far
 * along it is, and the three numbers that tell an operator whether a decode is
 * going well.
 *
 * The numbers are the point. Rows-decoded says how much of the picture is in;
 * quality says whether it is worth keeping; slant says whether the sound card
 * clock needs correcting — the one thing that makes every picture come out
 * skewed and the one thing an operator cannot see by looking at the image
 * until it is too late.
 */
@Composable
internal fun RxStatusCard(
    statusLabel: String,
    rightLabel: String?,
    progress: Float,
    rowsLabel: String,
    qualityLabel: String,
    slantLabel: String,
    kind: RxStatusKind,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PulsingDot(color = rxStatusDotColor(kind), pulsing = rxStatusPulses(kind))
                Text(
                    text = statusLabel,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (rightLabel != null) {
                Text(
                    text = rightLabel,
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = GeistMonoFamily,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        ProgressBar(progress = progress, color = rxStatusDotColor(kind))

        // Three fixed columns, not SpaceBetween. The slant readout is blank
        // whenever nothing is decoding, and with SpaceBetween that made the
        // remaining two readouts slide across the card every time a decode
        // started or ended. Weighted columns hold their places, so the numbers
        // stay where the eye last found them.
        Row(modifier = Modifier.fillMaxWidth()) {
            Meta(text = rowsLabel, modifier = Modifier.weight(1f))
            Meta(
                text = qualityLabel,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Meta(
                text = slantLabel,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
            )
        }
    }
}

/** One of the three mono readouts under the bar. */
@Composable
private fun Meta(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    Text(
        text = text,
        modifier = modifier,
        color = TextFaint,
        fontSize = 11.sp,
        fontFamily = GeistMonoFamily,
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
    )
}

/** The 6dp progress bar: a full-width track with the state's colour filling it. */
@Composable
private fun ProgressBar(progress: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(BgSurface3),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
    }
}

/**
 * A 6dp dot with an expanding halo.
 *
 * The halo is the only thing on an idle Receive screen that moves, and that is
 * its job: it is the difference between "waiting for a signal" and "the app has
 * stopped". It stops pulsing on a lost signal, where a heartbeat would be
 * telling the operator everything is fine.
 */
@Composable
private fun PulsingDot(color: Color, pulsing: Boolean) {
    val scale: Float
    val alpha: Float
    if (pulsing) {
        val transition = rememberInfiniteTransition(label = "rx-dot")
        val t by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "rx-dot-pulse",
        )
        scale = 1f + t * 2.6f
        alpha = 0.4f * (1f - t)
    } else {
        scale = 1f
        alpha = 0f
    }

    Box(
        modifier = Modifier.size(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (alpha > 0f) {
            Box(
                modifier = Modifier
                    .size((6 * scale).dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha)),
            )
        }
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/**
 * The status colour, which the dot and the progress bar share.
 *
 * Amber while listening (the app is armed but nothing is happening), cyan while
 * decoding (the accent — this is the app doing its job), green on a saved
 * picture, red on a lost one. Shared between the two so the card reads as one
 * state rather than two indicators that might disagree.
 */
internal fun rxStatusDotColor(kind: RxStatusKind): Color = when (kind) {
    // Muted, like the header's CAT dot under VOX: receive being off is a state
    // the operator chose, not a fault to colour red.
    RxStatusKind.OFF -> TextMuted
    RxStatusKind.LISTENING -> Signal
    RxStatusKind.DECODING -> Accent
    // A decode that finished but is not yet confirmed in the Gallery is not
    // green yet — green is the store's word, not the decoder's.
    RxStatusKind.COMPLETE -> Accent
    RxStatusKind.SAVED -> StatusConfirmed
    RxStatusKind.SAVE_FAILED -> StatusBad
    RxStatusKind.LOST -> StatusBad
}
