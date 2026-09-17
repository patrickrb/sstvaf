package radio.ks3ckc.sstvaf.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import com.k1af.ft8af.spectrum.AudioInputLevel
import com.k1af.ft8af.wave.InputAudioLevel
import radio.ks3ckc.sstvaf.theme.BgSurface3
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.StatusBad
import radio.ks3ckc.sstvaf.theme.StatusConfirmed
import radio.ks3ckc.sstvaf.theme.StatusWarn
import radio.ks3ckc.sstvaf.theme.TextFaint
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary
import radio.ks3ckc.sstvaf.ui.components.classifyInputLevel
import radio.ks3ckc.sstvaf.ui.components.inputLevelColor
import radio.ks3ckc.sstvaf.ui.components.inputLevelFraction
import radio.ks3ckc.sstvaf.ui.components.inputLevelPercent

/**
 * The live receive input-level meter on Radio & audio.
 *
 * Setting receive gain by ear does not work: an SSTV image decodes cleanly or
 * arrives washed out, and by then the transmission is over. The meter is the
 * only way to set it before a QSO, so it reads the same audio the decoder does
 * and classifies it with [AudioInputLevel], the app single level classifier.
 *
 * The tick sits at [AudioInputLevel.HOT_PEAK], the exact level at which that
 * classifier starts calling the input hot, so the mark and the verdict can
 * never disagree.
 */
@Composable
internal fun InputLevelMeter(
    levels: InputAudioLevel.Levels?,
    modifier: Modifier = Modifier,
) {
    val status = classifyInputLevel(levels)
    val rms = levels?.rms ?: 0f
    val peak = levels?.peak ?: 0f
    val silent = inputLevelIsSilent(levels)
    val adviceRes = inputLevelAdviceRes(status)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.radio_input_level),
                color = TextPrimary,
                fontSize = 14.sp,
            )
            Text(
                text = stringResource(inputLevelWordRes(status)),
                color = inputLevelColor(status),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        MeterBar(rms = rms, peak = peak)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (adviceRes != null) {
                    stringResource(adviceRes)
                } else {
                    stringResource(R.string.radio_level_advice_good)
                },
                color = TextFaint,
                fontSize = 11.sp,
            )
            // A silent input has no meaningful percentage to report, and
            // printing "0%" beside a "check the cable" line invites the
            // operator to chase the gain instead of the connection.
            if (!silent) {
                Text(
                    text = stringResource(R.string.settings_percent_format, inputLevelPercent(peak)),
                    color = TextMuted,
                    fontFamily = GeistMonoFamily,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/**
 * The bar itself: an RMS-filled gradient with a peak tick and a fixed mark at
 * the hot threshold.
 */
@Composable
private fun MeterBar(rms: Float, peak: Float) {
    val barShape = RoundedCornerShape(999.dp)
    val fill = inputLevelFraction(rms)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(barShape)
            .background(BgSurface3, barShape),
    ) {
        val trackWidth = maxWidth
        val trackPx = with(LocalDensity.current) { trackWidth.toPx() }

        // The gradient is stretched by the inverse of the fill so each colour
        // stays pinned to its own level rather than to the end of the bar.
        // fill * scale is 1 for any non-zero fill, i.e. the gradient always
        // spans the full track even when the filled part of it is short.
        val gradientEndX = trackPx * fill * inputLevelGradientScale(fill)
        if (fill > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fill)
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(StatusConfirmed, StatusWarn, StatusBad),
                            startX = 0f,
                            endX = gradientEndX,
                        ),
                    ),
            )
        }

        // Fixed mark at the level where the classifier starts calling it hot.
        Box(
            modifier = Modifier
                .offset(x = trackWidth * AudioInputLevel.HOT_PEAK)
                .width(1.dp)
                .fillMaxHeight()
                .background(TextFaint),
        )

        // Peak tick for the current window.
        if (peak > 0f) {
            Box(
                modifier = Modifier
                    .offset(x = trackWidth * inputLevelFraction(peak) - 1.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(TextPrimary),
            )
        }
    }
}
