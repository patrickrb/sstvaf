package radio.ks3ckc.sstvaf.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import com.k1af.ft8af.rigs.CatConnectionState
import radio.ks3ckc.sstvaf.theme.*

/**
 * Clamp a volume value after a +/- step to the 0–100 range.
 * Extracted so it can be unit-tested without Compose.
 */
internal fun clampVolume(current: Int, delta: Int): Int =
    (current + delta).coerceIn(0, 100)

/**
 * Label for the TUNE chip: the plain label when idle, "label countdown" while
 * the carrier is up (e.g. "TUNE 7s") so the operator sees the safety timeout
 * running. Extracted so it can be unit-tested without Compose.
 */
internal fun tuneChipLabel(label: String, isTuning: Boolean, remainingSec: Int): String =
    if (isTuning) "$label ${remainingSec.coerceAtLeast(0)}s" else label

/**
 * The TX status strip: pulse dot + TX/RX state (TX indicator), CAT status chip,
 * frequency/band pill, the TUNE toggle, and the inline TX volume slider.
 *
 * Slimmed down from the FT8 version: the CQ/STOP/HUNT/DX/slot/mode controls
 * were part of the FT8 QSO engine and are gone; SSTV TX controls land in a
 * later PR.
 */
@Composable
fun TxStrip(
    isTransmitting: Boolean,
    frequencyLabel: String,
    catState: CatConnectionState = CatConnectionState.DISCONNECTED,
    showCatChip: Boolean = false,
    txVolume: Int = 80,
    showVolumeSlider: Boolean = false,
    isTuning: Boolean = false,
    tuneRemainingSec: Int = 0,
    onToggleTune: () -> Unit = {},
    onVolumeChange: (Int) -> Unit = {},
    onVolumeChangeFinished: () -> Unit = {},
    onReconnectCat: () -> Unit = {},
    onOpenFrequencyPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor = if (isTransmitting) {
        Brush.horizontalGradient(
            listOf(
                Color(0x1FFFAF5E),  // rgba(255,175,94,0.12)
                Color(0x0AFFAF5E),  // rgba(255,175,94,0.04)
            )
        )
    } else {
        Brush.horizontalGradient(listOf(BgSurface, BgSurface))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor)
            .drawBehind {
                drawLine(
                    color = Border,
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                    strokeWidth = 1f,
                )
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---- Info row: status (left) · frequency / TUNE chips (right) ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left: pulse dot + state + CAT chip. weight(1f, fill=false) lets the
            // status label ellipsize before it can shove the right-hand chips off
            // screen on a narrow device.
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PulseDot(color = if (isTransmitting) Accent else Signal)
                Text(
                    text = if (isTransmitting) stringResource(R.string.tx_transmitting)
                    else stringResource(R.string.tx_listening),
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistMonoFamily,
                    letterSpacing = 0.02.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showCatChip) {
                    CatStatusChip(state = catState, onReconnect = onReconnectCat)
                }
            }

            // Right: frequency/band pill + TUNE toggle.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Frequency / band pill — opens the frequency picker.
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgSurface3)
                        .clickable { onOpenFrequencyPicker() }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = frequencyLabel,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistMonoFamily,
                        letterSpacing = 0.02.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                    SstvAfIcons.ChevronDown(size = 12.dp, color = TextMuted, strokeWidth = 2f)
                }

                // TUNE toggle pill: tap keys a steady carrier at the TX offset
                // for antenna/amplifier tuning; tap again stops it. While active
                // it turns red and counts down the code-enforced safety timeout.
                val tuneDescription = stringResource(R.string.tune_content_description)
                Box(modifier = Modifier.semantics { contentDescription = tuneDescription }) {
                    TxChip(
                        label = tuneChipLabel(
                            stringResource(R.string.tune_button), isTuning, tuneRemainingSec,
                        ),
                        background = if (isTuning) StatusBad else BgSurface3,
                        textColor = if (isTuning) Color.White else TextMuted,
                        bold = isTuning,
                        enabled = true,
                        onClick = onToggleTune,
                    )
                }
            }
        }

        // ---- Inline TX volume slider (togglable from Settings) ----
        val volumeDecrease = stringResource(R.string.tx_volume_decrease)
        val volumeIncrease = stringResource(R.string.tx_volume_increase)
        if (showVolumeSlider) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Minus button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgSurface3)
                        .semantics { role = Role.Button; contentDescription = volumeDecrease }
                        .clickable {
                            onVolumeChange(clampVolume(txVolume, -5))
                            onVolumeChangeFinished()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "−", // minus sign
                        color = TextMuted,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistMonoFamily,
                    )
                }

                // Slider
                IntSlider(
                    value = txVolume,
                    onValueChange = { v ->
                        onVolumeChange(v.coerceIn(0, 100))
                    },
                    onValueChangeFinished = onVolumeChangeFinished,
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                )

                // Plus button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgSurface3)
                        .semantics { role = Role.Button; contentDescription = volumeIncrease }
                        .clickable {
                            onVolumeChange(clampVolume(txVolume, 5))
                            onVolumeChangeFinished()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+",
                        color = TextMuted,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistMonoFamily,
                    )
                }

                // Percentage label
                Text(
                    text = "${txVolume}%",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistMonoFamily,
                    letterSpacing = 0.02.sp,
                )
            }
        }
    }
}

/** A small rounded text chip (TUNE). Keeps button semantics when disabled. */
@Composable
private fun TxChip(
    label: String,
    background: Color,
    textColor: Color,
    bold: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            // Disable via clickable(enabled=…) rather than dropping the modifier, so the
            // chip keeps its button semantics and TalkBack still announces it as a disabled
            // control instead of vanishing from accessibility entirely.
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
            letterSpacing = 0.02.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun PulseDot(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseAlpha",
    )
    val pulseSize by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseSize",
    )

    Box(
        modifier = Modifier.size(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Pulse ring
        Box(
            modifier = Modifier
                .size((6 + pulseSize * 2).dp)
                .clip(CircleShape)
                .background(color.copy(alpha = pulseAlpha * 0.18f))
        )
        // Solid dot
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}
