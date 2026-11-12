package radio.ks3ckc.sstvaf.ui.tx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.gallery.SavedImage
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.AccentSoft
import radio.ks3ckc.sstvaf.theme.BgApp
import radio.ks3ckc.sstvaf.theme.BgSurface2
import radio.ks3ckc.sstvaf.theme.BgSurface3
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.StatusBad
import radio.ks3ckc.sstvaf.theme.TextFaint
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary
import radio.ks3ckc.sstvaf.ui.components.SstvAfIcons
import java.io.File

/**
 * What the Inset tool's picker strip needs from outside the composer: the
 * received pictures on offer and where each one's file is.
 *
 * Bundled so the tool panel takes one argument for it rather than two, and so
 * the default - nothing on offer - is a valid, renderable state for every
 * caller that does not care about insets.
 */
internal data class InsetPickerState(
    val received: List<SavedImage> = emptyList(),
    val fileOf: (SavedImage) -> File? = { null },
    /** Re-read [received]; called each time the strip comes on screen. */
    val refresh: () -> Unit = {},
)

/**
 * The Inset tool's panel: pick a received picture to pin, then place it.
 *
 * Two states in one panel rather than a sheet for the picker, for the reason
 * every other tool is inline - the canvas stays visible, so the operator sees
 * the received picture land on their own the moment they tap it.
 */
@Composable
internal fun InsetPanel(
    inset: ImageInset?,
    activeCorner: InsetCorner?,
    picker: InsetPickerState,
    onPick: (SavedImage) -> Unit,
    onCorner: (InsetCorner) -> Unit,
    onSize: (Float) -> Unit,
    onRemove: () -> Unit,
) {
    // Reset when the inset comes or goes, so removing it from the canvas ends
    // a change in progress. A pick ends it explicitly below - keying on the
    // pinned picture alone missed the operator tapping the one already
    // pinned, which left the picker open as if the tap had done nothing.
    var changing by remember(inset != null) { mutableStateOf(false) }

    if (insetPanelShowsPicker(hasInset = inset != null, changeRequested = changing)) {
        InsetPicker(
            picker = picker,
            cancellable = inset != null,
            onCancel = { changing = false },
            onPick = { entry ->
                changing = false
                onPick(entry)
            },
        )
    } else if (inset != null) {
        InsetPlacement(
            inset = inset,
            activeCorner = activeCorner,
            onChange = { changing = true },
            onCorner = onCorner,
            onSize = onSize,
            onRemove = onRemove,
        )
    }
}

/** The strip of recent received pictures. */
@Composable
private fun InsetPicker(
    picker: InsetPickerState,
    cancellable: Boolean,
    onCancel: () -> Unit,
    onPick: (SavedImage) -> Unit,
) {
    // The strip is read when it appears, not when the tool was first opened:
    // a picture received since then, or deleted from the gallery, would
    // otherwise be missing from - or dead in - a list the operator is about to
    // pick from.
    LaunchedEffect(Unit) { picker.refresh() }
    Hint(stringResource(R.string.tx_inset_hint_empty))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SectionLabel(stringResource(R.string.tx_inset_received_label))
        if (cancellable) {
            Pill(stringResource(R.string.action_cancel), onClick = onCancel)
        }
    }
    if (picker.received.isEmpty()) {
        Hint(stringResource(R.string.tx_inset_none_received))
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (entry in picker.received) {
            val caption = insetPickerCaption(entry)
            val description = stringResource(R.string.tx_inset_pick_description, caption)
            Column(
                modifier = Modifier
                    .width(76.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { onPick(entry) }
                    .semantics(mergeDescendants = true) { contentDescription = description },
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                AsyncImage(
                    model = picker.fileOf(entry),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgSurface3),
                    contentScale = ContentScale.Crop,
                )
                Text(
                    text = caption,
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = GeistMonoFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    Hint(stringResource(R.string.tx_inset_tap_to_pin))
}

/** The controls for an inset that is already on the picture. */
@Composable
private fun InsetPlacement(
    inset: ImageInset,
    activeCorner: InsetCorner?,
    onChange: () -> Unit,
    onCorner: (InsetCorner) -> Unit,
    onSize: (Float) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InsetThumbnail(inset = inset, modifier = Modifier.height(40.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.tx_inset_title),
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (inset.caption.isNotBlank()) {
                Text(
                    text = inset.caption,
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = GeistMonoFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Pill(stringResource(R.string.tx_change_photo_pill), onClick = onChange)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel(stringResource(R.string.tx_inset_corner))
            // Two Rows rather than a grid, as the callsign presets are: four
            // fixed cells need no laziness.
            for (row in listOf(
                listOf(InsetCorner.TOP_LEFT, InsetCorner.TOP_RIGHT),
                listOf(InsetCorner.BOTTOM_LEFT, InsetCorner.BOTTOM_RIGHT),
            )) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (corner in row) {
                        CornerCell(
                            corner = corner,
                            selected = corner == activeCorner,
                            onClick = { onCorner(corner) },
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionLabel(stringResource(R.string.tx_inset_size))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (size in INSET_SIZES) {
                    val isSelected = size == inset.widthFraction
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Accent else BgSurface3)
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                            ) { onSize(size) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(insetSizeLabelRes(size)),
                            color = if (isSelected) BgApp else TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = GeistMonoFamily,
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, StatusBad.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.tx_inset_remove),
                    color = StatusBad,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
    Hint(stringResource(R.string.tx_inset_hint_drag))
}

/**
 * One corner preset: a cell with a small filled rectangle where the inset will
 * land. A picture of the outcome, like the callsign presets beside it.
 */
@Composable
private fun CornerCell(corner: InsetCorner, selected: Boolean, onClick: () -> Unit) {
    val description = stringResource(insetCornerDescriptionRes(corner))
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Accent.copy(alpha = 0.14f) else BgSurface2)
            .border(
                1.dp,
                if (selected) Accent else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Box(
            modifier = Modifier
                .align(
                    when (corner) {
                        InsetCorner.TOP_LEFT -> Alignment.TopStart
                        InsetCorner.TOP_RIGHT -> Alignment.TopEnd
                        InsetCorner.BOTTOM_LEFT -> Alignment.BottomStart
                        InsetCorner.BOTTOM_RIGHT -> Alignment.BottomEnd
                    },
                )
                .padding(6.dp)
                .size(width = 16.dp, height = 12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) Accent else TextFaint),
        )
    }
}

/**
 * The pinned picture, small, in its own shape and with the border it carries
 * on the air - so the thumbnail reads as "that thing on the canvas".
 */
@Composable
private fun InsetThumbnail(inset: ImageInset, modifier: Modifier = Modifier) {
    AsyncImage(
        model = insetSourceFile(inset.sourceUri),
        contentDescription = null,
        modifier = modifier
            .aspectRatio(clampInsetAspect(inset.aspect))
            .clip(RoundedCornerShape(4.dp))
            .background(BgSurface3)
            .border(1.5.dp, Color.White, RoundedCornerShape(4.dp)),
        contentScale = ContentScale.FillBounds,
    )
}

/**
 * The reply banner over the Send screen's empty state: a received picture is
 * waiting to be pinned to whatever the operator sends next.
 *
 * It sits above the four ways to start rather than replacing any of them.
 * Replying does not narrow the choice of picture - a photo, a CQ card and
 * "Last sent" are all valid replies - it only adds something to it.
 */
@Composable
internal fun ReplyBanner(inset: ImageInset, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val dismissDescription = stringResource(R.string.tx_reply_banner_dismiss)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AccentSoft)
            .border(1.dp, Accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InsetThumbnail(inset = inset, modifier = Modifier.height(44.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.tx_reply_banner_title),
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (inset.caption.isNotBlank()) {
                Text(
                    text = inset.caption,
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = GeistMonoFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(R.string.tx_reply_banner_sub),
                color = TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(role = Role.Button, onClick = onDismiss)
                .semantics { contentDescription = dismissDescription },
            contentAlignment = Alignment.Center,
        ) {
            SstvAfIcons.Close(size = 14.dp, color = TextMuted, strokeWidth = 2.2f)
        }
    }
}

/** An uppercase section label. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
    )
}
