package radio.ks3ckc.sstvaf.ui.rx

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.AccentGlow
import radio.ks3ckc.sstvaf.theme.BgApp
import radio.ks3ckc.sstvaf.theme.BgSurface
import radio.ks3ckc.sstvaf.theme.BgSurface3
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.StatusConfirmed
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary

/**
 * The Receive screen's canvas: one framed rectangle that is the whole point of
 * the screen, in three states.
 *
 * Listening shows a scanning glow so an idle receiver still reads as *awake* —
 * a blank box is indistinguishable from a crashed app. Decoding reveals the
 * picture top-down behind a glowing line, which is what an operator sees on a
 * hardware SSTV monitor and makes the progress bar almost redundant. Complete
 * holds the finished picture with the mode it arrived in, because that is the
 * fact you want when deciding whether to reply.
 *
 * All the decisions live in RxScreenLogic.kt; this file only draws.
 */
@Composable
internal fun RxCanvas(
    image: ImageBitmap?,
    aspect: Float,
    revealFraction: Float,
    showsImage: Boolean,
    listening: Boolean,
    receiveEnabled: Boolean,
    frequencyLabel: String,
    completedModeName: String?,
    showsSavedBadge: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface)
            .border(1.dp, BgSurface3, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (showsImage && image != null) {
            RevealedImage(image = image, revealFraction = revealFraction)
            ScanlineTexture(alpha = 0.16f)
            if (revealFraction > 0f && revealFraction < 1f) {
                DecodeLine(fraction = revealFraction)
            }
        } else if (listening) {
            ListeningIndicator(
                receiveEnabled = receiveEnabled,
                frequencyLabel = frequencyLabel,
            )
        }

        if (completedModeName != null) {
            CompletionBadges(
                modeName = completedModeName,
                // The "Saved ✓" half is a promise that the picture is in the
                // Gallery, so it waits for the store to confirm the write. The
                // mode badge is about the decode and shows either way.
                showsSaved = showsSavedBadge,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
        }
    }
}

/**
 * The image, clipped to the fraction decoded so far.
 *
 * `drawWithContent` + `clipRect` rather than cropping the bitmap: the decoder
 * hands over a full-height buffer with the undecoded rows still black, so
 * clipping shows the frame's own background below the line instead of a band
 * of black that looks like part of the picture.
 */
@Composable
private fun RevealedImage(image: ImageBitmap, revealFraction: Float) {
    Image(
        bitmap = image,
        contentDescription = stringResource(R.string.rx_canvas_description),
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                val visible = size.height * revealFraction.coerceIn(0f, 1f)
                if (visible <= 0f) return@drawWithContent
                clipRect(top = 0f, left = 0f, right = size.width, bottom = visible) {
                    this@drawWithContent.drawContent()
                }
            },
        contentScale = ContentScale.FillBounds,
        // No smoothing: an SSTV frame is 320 px wide and its scan lines are the
        // data. Bilinear upscaling would blur exactly what the operator is
        // judging the decode by.
        filterQuality = FilterQuality.None,
    )
}

/** The 2px accent line riding the reveal edge, with its glow. */
@Composable
private fun DecodeLine(fraction: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val y = size.height * fraction.coerceIn(0f, 1f)
        // Glow first, then the hard line over it.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Accent.copy(alpha = 0.45f)),
                startY = y - 14.dp.toPx(),
                endY = y,
            ),
            topLeft = Offset(0f, (y - 14.dp.toPx()).coerceAtLeast(0f)),
            size = Size(size.width, 14.dp.toPx().coerceAtMost(y.coerceAtLeast(0f))),
        )
        drawRect(
            color = AccentGlow,
            topLeft = Offset(0f, y - 1.dp.toPx()),
            size = Size(size.width, 2.dp.toPx()),
        )
    }
}

/**
 * The 1-on-3 pixel horizontal scanline overlay.
 *
 * Cosmetic, and deliberately so: it reads as "this came off the air" rather
 * than "this is a photo from your library", which matters on a screen where
 * received pictures and the phone's own photos sit two taps apart.
 */
@Composable
private fun ScanlineTexture(alpha: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val line = 1.dp.toPx()
        val step = 3.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawRect(
                color = Color.Black.copy(alpha = alpha),
                topLeft = Offset(0f, y),
                size = Size(size.width, line),
            )
            y += step
        }
    }
}

/** Listening: a scanning glow, the state, and the dial being watched. */
@Composable
private fun ListeningIndicator(
    receiveEnabled: Boolean,
    frequencyLabel: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScanningBars(animated = receiveEnabled)
        Text(
            text = stringResource(
                if (receiveEnabled) R.string.rx_listening else R.string.rx_disabled,
            ),
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = frequencyLabel,
            color = TextMuted,
            fontSize = 12.sp,
            fontFamily = GeistMonoFamily,
        )
    }
}

/**
 * The 180×60 scanning ellipse: vertical bars sweeping through a soft glow,
 * masked to fade at both ends so they appear to pass through rather than
 * start and stop.
 *
 * Frozen when receive is off. An animation that keeps running while the
 * receiver is disabled would be claiming the app is listening when it is not.
 */
@Composable
private fun ScanningBars(animated: Boolean) {
    val offsetPx = if (animated) {
        val transition = rememberInfiniteTransition(label = "rx-scan")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(3000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "rx-scan-offset",
        )
        value
    } else {
        0f
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth(0.5f)
            .aspectRatio(3f),
    ) {
        val barSpacing = 13.dp.toPx()
        val barWidth = 1.dp.toPx()
        // Soft radial glow behind the bars.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(AccentGlow.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = size.minDimension,
            ),
            radius = size.minDimension,
            center = Offset(size.width / 2f, size.height / 2f),
        )
        // Bars, translated by the animation and wrapped so the sweep is seamless.
        val shift = offsetPx * barSpacing
        var x = -barSpacing + shift
        while (x < size.width + barSpacing) {
            // Fade toward both edges: the sweep reads as passing through.
            val centreDistance = kotlin.math.abs((x / size.width) - 0.5f) * 2f
            val edgeFade = (1f - centreDistance).coerceIn(0f, 1f)
            if (x >= 0f && x <= size.width) {
                drawRect(
                    color = Accent.copy(alpha = 0.6f * edgeFade),
                    topLeft = Offset(x, 0f),
                    size = Size(barWidth, size.height),
                )
            }
            x += barSpacing
        }
    }
}

/** "Saved ✓" plus the mode, over a scrim in the canvas corner. */
@Composable
private fun CompletionBadges(
    modeName: String,
    showsSaved: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showsSaved) {
            Badge(
                text = stringResource(R.string.rx_saved_chip),
                color = StatusConfirmed,
                bold = true,
            )
        }
        Badge(text = modeName, color = TextPrimary, mono = true)
    }
}

@Composable
private fun Badge(
    text: String,
    color: Color,
    bold: Boolean = false,
    mono: Boolean = false,
) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(BgApp.copy(alpha = 0.8f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = color,
        fontSize = 11.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
        fontFamily = if (mono) GeistMonoFamily else null,
    )
}
