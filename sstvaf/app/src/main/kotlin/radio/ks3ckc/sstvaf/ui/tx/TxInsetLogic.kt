package radio.ks3ckc.sstvaf.ui.tx

import android.net.Uri
import androidx.annotation.StringRes
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.gallery.ImageDirection
import radio.ks3ckc.sstvaf.gallery.SavedImage
import radio.ks3ckc.sstvaf.sstv.SstvMode
import radio.ks3ckc.sstvaf.ui.gallery.formatViewerFrequency
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Decisions behind the Inset tool and the reply flow that are not geometry:
 * which received pictures are offered, how one is described, and how a gallery
 * row becomes an [ImageInset]. Plain functions so they carry the unit tests;
 * the composables in TxInsetPanel.kt only lay them out.
 */

/** How many received pictures the Inset tool's strip offers. */
internal const val INSET_PICKER_LIMIT = 12

/**
 * The received pictures the Inset tool offers, newest first.
 *
 * Received only: pinning one of the operator's *own* transmissions into a
 * reply proves nothing to the other station. Capped, because the strip is a
 * shortcut to the last few contacts - the Gallery is where the archive lives.
 * [all] is expected newest first, as `ReceivedImageStore.list()` returns it.
 */
internal fun recentReceived(
    all: List<SavedImage>,
    limit: Int = INSET_PICKER_LIMIT,
): List<SavedImage> =
    all.asSequence()
        .filter { it.direction == ImageDirection.RX }
        .take(limit.coerceAtLeast(0))
        .toList()

/** "HH:mm" in UTC - the time zone every logbook and every other station uses. */
private fun utcHourMinute(utcMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(utcMillis))

/**
 * The line that names a pinned picture: "Scottie 1 · 14:32 UTC · 14.230 MHz".
 *
 * The frequency is dropped when the row has none (0 - the rig was not
 * reporting), rather than printing "0.000 MHz" as if that were where the
 * picture was heard.
 */
internal fun insetCaption(mode: String, utcMillis: Long, freqHz: Long): String {
    val parts = mutableListOf<String>()
    if (mode.isNotBlank()) parts.add(mode.trim())
    parts.add(utcHourMinute(utcMillis) + " UTC")
    if (freqHz > 0L) parts.add(formatViewerFrequency(freqHz))
    return parts.joinToString(" · ")
}

/** The short caption under a picker thumbnail: "S1 · 14:32". */
internal fun insetPickerCaption(entry: SavedImage): String {
    val shortCode = SstvMode.entries.firstOrNull { it.displayName == entry.mode }?.shortCode
        ?: entry.mode
    val time = utcHourMinute(entry.utcMillis)
    return if (shortCode.isBlank()) time else "$shortCode · $time"
}

/**
 * The received picture's width over its height, from its gallery row.
 *
 * Read from the row rather than the decoded bitmap so the inset can be
 * described before anything is decoded; a hand-edited row with a zero
 * dimension degrades to 4:3 (see [clampInsetAspect]).
 */
internal fun insetAspectOf(entry: SavedImage): Float =
    clampInsetAspect(
        if (entry.width > 0 && entry.height > 0) entry.width.toFloat() / entry.height else Float.NaN,
    )

/**
 * The inset for a received gallery picture stored at [file], pinned to the
 * default corner of a frame shaped [frameAspect].
 *
 * [keepPlacementOf] carries the size and position over from an inset being
 * *replaced* (the tool's Change): the operator already said where they want
 * the received picture, and swapping which picture it is should not undo that.
 */
internal fun insetForReceived(
    entry: SavedImage,
    file: File,
    frameAspect: Float,
    keepPlacementOf: ImageInset? = null,
): ImageInset {
    val fresh = newImageInset(
        sourceUri = Uri.fromFile(file).toString(),
        aspect = insetAspectOf(entry),
        caption = insetCaption(entry.mode, entry.utcMillis, entry.freqHz),
        frameAspect = frameAspect,
        widthFraction = keepPlacementOf?.widthFraction ?: INSET_SIZE_MEDIUM,
    )
    if (keepPlacementOf == null) return fresh
    // Same corner if it was on one, otherwise the same centre - re-clamped,
    // because the new picture may be a different shape from the old.
    val corner = matchingInsetCorner(keepPlacementOf, frameAspect)
    val (x, y) = if (corner != null) {
        insetCornerCentre(corner, fresh.widthFraction, fresh.aspect, frameAspect)
    } else {
        clampInsetCentre(
            keepPlacementOf.xPercent,
            insetWidthPercent(fresh.widthFraction, fresh.aspect, frameAspect),
        ) to
            clampInsetCentre(
                keepPlacementOf.yPercent,
                insetHeightPercent(fresh.widthFraction, fresh.aspect, frameAspect),
            )
    }
    return fresh.copy(xPercent = x, yPercent = y, corner = corner)
}

/**
 * Whether the Inset panel shows the picker strip rather than the placement
 * controls: always when nothing is pinned, and on request (Change) when
 * something is.
 */
internal fun insetPanelShowsPicker(hasInset: Boolean, changeRequested: Boolean): Boolean =
    !hasInset || changeRequested

/** The spoken name for a corner cell, which is otherwise a bare rectangle. */
@StringRes
internal fun insetCornerDescriptionRes(corner: InsetCorner): Int = when (corner) {
    InsetCorner.TOP_LEFT -> R.string.tx_inset_corner_top_left
    InsetCorner.TOP_RIGHT -> R.string.tx_inset_corner_top_right
    InsetCorner.BOTTOM_LEFT -> R.string.tx_inset_corner_bottom_left
    InsetCorner.BOTTOM_RIGHT -> R.string.tx_inset_corner_bottom_right
}

/** The S / M / L label for an inset size (the text sizes' own strings). */
@StringRes
internal fun insetSizeLabelRes(widthFraction: Float): Int = when (widthFraction) {
    INSET_SIZE_SMALL -> R.string.tx_size_small
    INSET_SIZE_LARGE -> R.string.tx_size_large
    else -> R.string.tx_size_medium
}

/**
 * What the canvas's delete button removes, named for a screen reader: the
 * selected text when there is one, otherwise the inset.
 */
@StringRes
internal fun deleteTargetDescriptionRes(overlaySelected: Boolean): Int =
    if (overlaySelected) R.string.tx_overlay_delete_description else R.string.tx_inset_remove

/**
 * Whether the inset reads as selected on the canvas: the Inset tool is open,
 * there is an inset, and no text overlay holds the selection.
 *
 * Derived rather than stored. "The Inset tool is open" and "the inset is
 * selected" are the same fact to the operator, and keeping a second flag in
 * step with the tool is exactly the kind of state that drifts.
 */
internal fun insetIsSelected(tool: TxTool, hasInset: Boolean, overlaySelected: Boolean): Boolean =
    tool == TxTool.INSET && hasInset && !overlaySelected

/**
 * Whether a stored edit list can be replayed onto its original source, or the
 * reopen has to fall back to the flattened picture.
 *
 * It needs the source, and - if the list has an inset - the received picture
 * the inset points at. Replaying without that picture would quietly resend
 * something the flattened file in the gallery shows was never what went out.
 */
internal fun editListIsReplayable(
    sourceLoaded: Boolean,
    insetWanted: Boolean,
    insetLoaded: Boolean,
): Boolean = sourceLoaded && (!insetWanted || insetLoaded)

/** What the canvas's delete button removes. */
internal enum class DeleteTarget { OVERLAY, INSET, NONE }

/**
 * What the delete button acts on: the selected text if there is one, otherwise
 * the inset while its tool is open - the same rule that decides which of them
 * wears the selection outline (see [insetIsSelected]), so the button always
 * removes the thing that looks selected.
 */
internal fun deleteTarget(overlaySelected: Boolean, tool: TxTool, hasInset: Boolean): DeleteTarget =
    when {
        overlaySelected -> DeleteTarget.OVERLAY
        insetIsSelected(tool, hasInset, overlaySelected = false) -> DeleteTarget.INSET
        else -> DeleteTarget.NONE
    }
