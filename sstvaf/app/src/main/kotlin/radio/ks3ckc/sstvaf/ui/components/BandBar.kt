package radio.ks3ckc.sstvaf.ui.components

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
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.theme.BgSurface2
import radio.ks3ckc.sstvaf.theme.Border
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary
import java.util.Locale

/**
 * The band selector: a full-width dropdown-style bar under the header on the
 * operating tabs, opening the band picker sheet ([FrequencyPickerSheet]).
 *
 * This replaced the header's small frequency chip. The chip read fine once you
 * knew it was a button, but nothing about a 12sp pill in the top corner said
 * "this is where you change band" — the single most common rig action in the
 * app was hiding in its least prominent control. The bar spells it out: a
 * caption naming what it is, the band and dial in large type, a chevron saying
 * it opens, and the rig-link dot carried over from the chip.
 */
@Composable
fun BandBar(
    bandName: String,
    freqHz: Long,
    dotColor: Color,
    catStateDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = bandBarLabel(freqHz, bandName)
    // Same accessibility contract as the old chip: the CAT state rides in the
    // description because the dot's colour is the only visual link indicator,
    // which TalkBack (and anyone who can't tell the green from the amber)
    // cannot read.
    val description = stringResource(
        R.string.header_frequency_chip_description, label, catStateDescription,
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(BgSurface2)
            .border(1.dp, Border, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.band_bar_caption).uppercase(Locale.getDefault()),
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistMonoFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SstvAfIcons.ChevronDown(size = 16.dp, color = TextMuted, strokeWidth = 2.2f)
    }
}

// ---------------------------------------------------------------------------
// Pure logic (unit-tested — see BandBarTest)
// ---------------------------------------------------------------------------

/**
 * The bar's main line: band first, then the dial with units, e.g.
 * `"20m · 14.230 MHz"`.
 *
 * Band leads because this control is the band *selector* — the band is what
 * the operator came to change, the exact dial is the detail. Unlike the old
 * header chip there is room for the "MHz" unit, so it is spelled out.
 * [Locale.US] formatting keeps the digits and decimal separator matching
 * every other numeric readout in the app. A dial outside every known
 * allocation shows just the frequency rather than leading with a blank band.
 */
internal fun bandBarLabel(freqHz: Long, bandName: String): String {
    val mhz = String.format(Locale.US, "%.3f MHz", freqHz / 1_000_000.0)
    val band = bandName.trim()
    return if (band.isEmpty()) mhz else "$band · $mhz"
}

/**
 * Which tabs carry the band bar. Only the surfaces you operate a radio from:
 * Receive, Send and WEFAX care what the dial says (a fax chart starts with
 * tuning the station's broadcast frequency); Gallery, Logbook and Settings do
 * not, and giving them the bar would just push their content down.
 */
internal fun showsBandBar(tab: SstvTab): Boolean =
    tab == SstvTab.RX || tab == SstvTab.TX || tab == SstvTab.WEFAX
