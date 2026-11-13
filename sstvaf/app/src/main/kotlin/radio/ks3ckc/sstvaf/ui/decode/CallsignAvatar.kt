package radio.ks3ckc.sstvaf.ui.decode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.BgElev
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily

/**
 * Circular initials chip for a callsign. Replaced the QRZ profile-photo
 * avatar when the QRZ integration was removed — same footprint, no network.
 */
@Composable
fun CallsignAvatar(
    size: Dp,
    fallbackText: String,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(BgElev),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = fallbackText,
            color = Accent,
            fontFamily = GeistMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
    }
}
