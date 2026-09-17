package radio.ks3ckc.sstvaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import com.k1af.ft8af.R
import com.k1af.ft8af.database.ControlMode
import com.k1af.ft8af.rigs.CatConnectionState
import radio.ks3ckc.sstvaf.theme.Border
import radio.ks3ckc.sstvaf.theme.BgSurface2
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.StatusBad
import radio.ks3ckc.sstvaf.theme.StatusConfirmed
import radio.ks3ckc.sstvaf.theme.StatusWarn
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary
import java.util.Locale

/**
 * The app header, shown on every tab: the screen title on the left, and on the
 * right a frequency chip and an overflow button.
 *
 * This is what replaced the TX strip. The strip sat above the tab bar on every
 * screen carrying the dial, a CAT chip, TUNE and a volume slider — permanently
 * spending a band of screen height, and putting TUNE one stray thumb away from
 * keying the rig mid-QSO. Everything it carried now lives behind the frequency
 * chip, in the Frequency sheet ([FrequencyPickerSheet]), which is two taps deep
 * on purpose.
 *
 * All the formatting/colour decisions live in the plain functions below so they
 * carry the unit tests; this composable only draws.
 */
@Composable
fun AppHeader(
    title: String,
    frequencyLabel: String,
    catDotColor: Color,
    catStateDescription: String,
    onOpenFrequency: () -> Unit,
    onOpenMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Both sides carry a non-filling weight: each is capped at half the
        // header, and the slack goes back to SpaceBetween (title left, controls
        // right). The cap is what stops one side eating the other. An unweighted
        // control group is measured against the full width first, so a long dial
        // label — a 23cm frequency, or any label at a large accessibility font
        // scale — would take the row and leave the title nothing. Capped, each
        // side ellipsizes within its own half instead.
        HeaderTitle(title = title, modifier = Modifier.weight(1f, fill = false))

        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The chip carries the weight, not the button. A Row measures its
            // unweighted children first against the whole available width, so an
            // unweighted chip — at a large accessibility font scale its label can
            // outgrow this half of the header on its own — would consume the row
            // and leave MoreButton measured at zero width: an overflow control
            // that is invisible and untappable. Weighted, the chip gets only what
            // is left after the fixed 34dp button is reserved, and ellipsizes.
            FrequencyChip(
                label = frequencyLabel,
                dotColor = catDotColor,
                catStateDescription = catStateDescription,
                onClick = onOpenFrequency,
                modifier = Modifier.weight(1f, fill = false),
            )
            MoreButton(onClick = onOpenMore)
        }
    }
}

/** The 22/600 screen title. */
@Composable
private fun HeaderTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        modifier = modifier,
        style = MaterialTheme.typography.headlineLarge.copy(
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.01).sp,
        ),
        color = TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The dial chip: a status dot, the frequency and band in mono, and a chevron.
 * Tapping opens the Frequency sheet.
 */
@Composable
private fun FrequencyChip(
    label: String,
    dotColor: Color,
    catStateDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The CAT state goes in the description, not just the dot: colour is the
    // chip's only visual indication of the link, so without it TalkBack cannot
    // tell connected from connecting, disconnected or errored — and neither can
    // a sighted operator who cannot distinguish the dot's green from its amber.
    val description = stringResource(
        R.string.header_frequency_chip_description, label, catStateDescription,
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(BgSurface2)
            .border(1.dp, Border, RoundedCornerShape(999.dp))
            .clickable(role = Role.Button, onClick = onClick)
            // contentDescription, not just clickable's onClickLabel: the label
            // names the ACTION, while TalkBack reads the description to say what
            // the control is. The chip's own text would otherwise be announced
            // as bare digits with no hint that it opens anything.
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(start = 9.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f, fill = false),
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        SstvAfIcons.ChevronDown(size = 11.dp, color = TextMuted, strokeWidth = 2.2f)
    }
}

/** The 34dp overflow circle that opens [MoreSheet]. */
@Composable
private fun MoreButton(onClick: () -> Unit) {
    val description = stringResource(R.string.header_more_description)
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(BgSurface2)
            .border(1.dp, Border, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            // The button is a glyph with no text, so without this it reaches
            // TalkBack as an unlabelled button.
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        SstvAfIcons.Dots(size = 18.dp, color = TextMuted)
    }
}

// ---------------------------------------------------------------------------
// Pure logic (unit-tested — see AppHeaderTest)
// ---------------------------------------------------------------------------

/**
 * The frequency chip's label: MHz to kHz resolution, then the band, e.g.
 * `"14.230 · 20m"`.
 *
 * No "MHz" unit. The chip is 12sp mono in a header that also has to fit a
 * 22sp title and two buttons on a 360dp phone, and an operator reading
 * "14.230 · 20m" does not need to be told the units. The frequency is formatted
 * with [Locale.US] so the digits and decimal separator match every other
 * numeric readout in the app rather than following the device locale.
 *
 * A blank band (a dial outside every known allocation) drops the separator
 * instead of trailing a bare middle dot.
 */
internal fun frequencyChipLabel(freqHz: Long, bandName: String): String {
    val mhz = String.format(Locale.US, "%.3f", freqHz / 1_000_000.0)
    val band = bandName.trim()
    return if (band.isEmpty()) mhz else "$mhz · $band"
}

/**
 * The colour of the chip's 6px status dot.
 *
 * Green when the rig is talking to us, amber while connecting, red on a
 * connection error, and muted grey when there is no CAT link to speak of. VOX
 * is always muted: an audio-only setup has no control link, so a red "error"
 * dot would be reporting the absence of something the operator switched off on
 * purpose. This is the same state vocabulary as [CatStatusChip], which is why
 * both read from [CatConnectionState] rather than each inventing a status.
 */
/**
 * The string resource naming the CAT link's state, e.g. `"connected"`.
 *
 * The text counterpart of [headerCatDotColor], and deliberately the same
 * vocabulary: the dot's colour and this phrase are two renderings of one state,
 * so they are decided by two functions reading the same inputs rather than
 * drifting apart. VOX reports "no CAT link" rather than "not connected" for the
 * same reason the dot goes muted instead of red — an audio-only setup has no
 * control link to be disconnected from.
 *
 * Lowercase because it is read both as a clause inside the chip's content
 * description and as one `·`-separated segment of the radio summary line.
 */
@StringRes
internal fun catStateDescriptionRes(controlMode: Int, state: CatConnectionState): Int =
    if (controlMode == ControlMode.VOX) {
        R.string.cat_state_vox
    } else {
        when (state) {
            CatConnectionState.CONNECTED -> R.string.cat_state_connected
            CatConnectionState.CONNECTING -> R.string.cat_state_connecting
            CatConnectionState.ERROR -> R.string.cat_state_error
            CatConnectionState.DISCONNECTED -> R.string.cat_state_disconnected
        }
    }

internal fun headerCatDotColor(controlMode: Int, state: CatConnectionState): Color =
    if (controlMode == ControlMode.VOX) {
        TextMuted
    } else {
        when (state) {
            CatConnectionState.CONNECTED -> StatusConfirmed
            CatConnectionState.CONNECTING -> StatusWarn
            CatConnectionState.ERROR -> StatusBad
            CatConnectionState.DISCONNECTED -> TextMuted
        }
    }
