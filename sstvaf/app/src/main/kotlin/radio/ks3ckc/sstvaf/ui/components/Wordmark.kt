package radio.ks3ckc.sstvaf.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.TextPrimary

/**
 * SSTVAF wordmark — Geist Mono 600 with an accent-tinted "AF" after mono whites.
 *
 * Use sparingly: splash screen, About screen, share-card surfaces.
 */
@Composable
fun Wordmark(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 36.sp,
    baseColor: Color = TextPrimary,
    accentColor: Color = Accent,
) {
    Row(modifier = modifier) {
        Text(
            text = "SSTV",
            color = baseColor,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
            letterSpacing = 0.04.em,
        )
        Text(
            text = "AF",
            color = accentColor,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            fontFamily = GeistMonoFamily,
            letterSpacing = 0.04.em,
        )
    }
}
