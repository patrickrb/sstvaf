package radio.ks3ckc.sstvaf.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary

/**
 * Title bar for a screen that is not one of the three tabs — a full screen
 * reached from the header's overflow sheet, or a Settings drill-down.
 *
 * Passing [onBack] prepends a back chevron next to the title. It is the app's
 * one back affordance: Settings, Radio & audio, Logbook and every Settings
 * detail screen all render the same back circle in the same place, so "go back"
 * never moves or changes shape between screens.
 *
 * The three tabs use [AppHeader] instead, which carries the dial chip and the
 * overflow button in place of a back chevron.
 */
@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: @Composable (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 1dp + the 48dp target's 7dp of slack puts the chevron in the same
            // place the old 34dp circle sat at 8dp.
            .padding(start = if (onBack != null) 1.dp else 18.dp, end = 18.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = if (onBack != null) Alignment.CenterVertically else Alignment.Top,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (onBack != null) {
                BackButton(onBack = onBack)
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.01).sp,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (subtitle != null) {
                    subtitle()
                }
            }
        }
        if (actions != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                actions()
            }
        }
    }
}

/**
 * The back control — a right-pointing chevron rotated to point back, drawn in a
 * 34dp circle inside a 48dp touch target.
 *
 * The 48dp is not decoration: it is Android's minimum accessible target size,
 * and it is what the Material [androidx.compose.material3.IconButton] this
 * replaced provided for free. A 34dp target is a quarter of the area, which is
 * the difference between "back" being reliable and being a miss for anyone with
 * a tremor or a large thumb. The circle stays 34dp so the chevron sits exactly
 * where the design puts it; only the clickable bounds grew.
 */
@Composable
internal fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val backLabel = stringResource(R.string.action_back)
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onBack)
            // A bare chevron: contentDescription is the only thing TalkBack has
            // to announce it with.
            .semantics { contentDescription = backLabel },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(34.dp),
            contentAlignment = Alignment.Center,
        ) {
            SstvAfIcons.Chevron(
                color = TextPrimary,
                size = 20.dp,
                strokeWidth = 2f,
                modifier = Modifier.rotate(180f),
            )
        }
    }
}

@Composable
fun TopBarSubtitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.padding(top = 2.dp),
        color = TextMuted,
        fontSize = 12.sp,
    )
}
