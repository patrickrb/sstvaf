package radio.ks3ckc.sstvaf.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import com.k1af.ft8af.R
import com.k1af.ft8af.database.ControlMode
import com.k1af.ft8af.rigs.CatConnectionState
import radio.ks3ckc.sstvaf.theme.StatusBad
import radio.ks3ckc.sstvaf.theme.StatusConfirmed
import radio.ks3ckc.sstvaf.theme.StatusWarn
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary

/**
 * The app header, shown on every tab: just the screen title.
 *
 * It used to also carry a small frequency chip and an overflow button in the
 * top-right. Both are gone: band selection moved into the far more prominent
 * [BandBar] directly below (the chip was the app's most-used control hiding in
 * its least obvious corner), and the overflow sheet's destinations are all
 * tabs or Settings categories now, so there is nothing left to overflow.
 *
 * The formatting/colour decisions for the dial and the CAT dot live in the
 * plain functions below so they carry the unit tests; they are consumed by
 * [BandBar] and the Frequency sheet.
 */
@Composable
fun AppHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderTitle(title = title)
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

// ---------------------------------------------------------------------------
// Pure logic (unit-tested — see AppHeaderTest)
// ---------------------------------------------------------------------------

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
