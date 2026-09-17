package radio.ks3ckc.sstvaf.ui.tx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.gallery.SavedImage
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.BgSurface
import radio.ks3ckc.sstvaf.theme.BgSurface3
import radio.ks3ckc.sstvaf.theme.BorderStrong
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.Signal
import radio.ks3ckc.sstvaf.theme.TextFaint
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary
import radio.ks3ckc.sstvaf.ui.components.SstvAfIcons

/**
 * The Send screen with no picture loaded: four ways to start.
 *
 * Four, because "pick a photo" is not always the answer. Two of these need no
 * photo at all — an operator with nothing suitable in their camera roll can
 * still get a legible card on the air in one tap, which is most of what SSTV
 * traffic actually is. "Last sent" exists because the commonest real action is
 * sending the same card again with one line changed.
 */
@Composable
internal fun TxEmptyState(
    callsign: String,
    grid: String,
    lastSent: SavedImage?,
    lastSentFile: java.io.File?,
    lastSentCaption: String,
    onChoosePhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onCqCard: () -> Unit,
    onGridCard: () -> Unit,
    onLastSent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PhotoDropzone(onChoose = onChoosePhoto, onCamera = onTakePhoto)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TemplateCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.tx_card_cq),
                headline = stringResource(R.string.tx_card_cq_headline),
                headlineColor = Accent,
                subline = stringResource(R.string.tx_card_cq_sub, callsign),
                gradient = listOf(Color(0xFF0E131E), Color(0xFF1D2538)),
                onClick = onCqCard,
            )
            TemplateCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.tx_card_grid),
                headline = callsign,
                headlineColor = Signal,
                subline = gridCardSubline(grid),
                gradient = listOf(Color(0xFF1A2A4A), Color(0xFF0E131E)),
                onClick = onGridCard,
            )
        }

        if (lastSent != null) {
            LastSentRow(
                file = lastSentFile,
                caption = lastSentCaption,
                onClick = onLastSent,
            )
        }
    }
}

/** The dashed "Choose a photo" target, with a camera alternative. */
@Composable
private fun PhotoDropzone(onChoose: () -> Unit, onCamera: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(320f / 150f)
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface)
            .border(1.dp, BorderStrong, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onChoose)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SstvAfIcons.RxImage(size = 28.dp, color = Accent, strokeWidth = 1.6f)
        Box(modifier = Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.tx_choose_photo),
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Box(modifier = Modifier.size(4.dp))
        Text(
            text = stringResource(R.string.tx_choose_photo_sub),
            color = TextMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Box(modifier = Modifier.size(8.dp))
        // The camera is a secondary route, not a peer of the dropzone: most
        // pictures worth sending already exist, and a live capture is the
        // exception.
        Text(
            text = stringResource(R.string.tx_camera_button),
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(BgSurface3)
                .clickable(role = Role.Button, onClick = onCamera)
                .padding(horizontal = 12.dp, vertical = 5.dp),
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A CQ or grid card: a gradient with the text that will be burned into it. */
@Composable
private fun TemplateCard(
    label: String,
    headline: String,
    headlineColor: Color,
    subline: String,
    gradient: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(320f / 180f)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(gradient))
            .border(1.dp, BgSurface3, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(10.dp),
    ) {
        Column(
            modifier = Modifier.align(Alignment.TopStart),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = headline,
                color = headlineColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = GeistMonoFamily,
                maxLines = 1,
            )
            Text(
                text = subline,
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistMonoFamily,
                maxLines = 1,
            )
        }
        Text(
            text = label,
            modifier = Modifier.align(Alignment.BottomStart),
            color = TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** The "Last sent" row: reload the previous picture with its text intact. */
@Composable
private fun LastSentRow(
    file: java.io.File?,
    caption: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface)
            .border(1.dp, BgSurface3, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = file,
            contentDescription = null,
            modifier = Modifier
                .size(width = 56.dp, height = 42.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(BgSurface3),
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.tx_last_sent),
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = caption,
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = GeistMonoFamily,
                maxLines = 1,
            )
        }
        SstvAfIcons.Chevron(size = 18.dp, color = TextFaint, strokeWidth = 1.8f)
    }
}
