package radio.ks3ckc.sstvaf.ui.wefax

import androidx.annotation.StringRes
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.wefax.WefaxRxState

/**
 * Pure decision/formatting logic for [WefaxScreen], extracted per project
 * testing policy (Composables stay thin wrappers; this file carries the unit
 * tests).
 */

/** Rows the live preview shows: the strip's most recent lines, fax-printer style. */
internal const val WEFAX_PREVIEW_ROWS = 400

/** The status line's text resource for an engine state. */
@StringRes
internal fun wefaxStatusLabelRes(state: WefaxRxState): Int = when (state) {
    is WefaxRxState.Idle -> R.string.wefax_status_idle
    is WefaxRxState.Listening -> R.string.wefax_status_listening
    is WefaxRxState.Decoding -> R.string.wefax_status_receiving
    is WefaxRxState.Stopped -> R.string.wefax_status_stopped
}

/** Decoded-line count to show, or null when the state has none. */
internal fun wefaxRowsCount(state: WefaxRxState): Int? = when (state) {
    is WefaxRxState.Decoding -> state.rowsReady
    is WefaxRxState.Stopped -> state.rows.takeIf { it > 0 }
    else -> null
}

/** Whether the preset selector may be changed (not while receiving). */
internal fun wefaxPresetSelectable(receiving: Boolean): Boolean = !receiving

/**
 * Roll [nNew] freshly decoded pixels (already ARGB, [width] per row) into the
 * fixed-height preview buffer of [previewRows] rows: existing content shifts
 * up and the new rows enter at the bottom — the strip emerges the way paper
 * leaves a fax printer. When more new rows arrive at once than the preview
 * holds, only the newest [previewRows] survive. Returns the number of rows
 * now populated (saturates at [previewRows]).
 */
internal fun wefaxRollPreview(
    buffer: IntArray,
    width: Int,
    previewRows: Int,
    populatedRows: Int,
    newPixels: IntArray,
    nNewRows: Int,
): Int {
    require(buffer.size >= width * previewRows) { "preview buffer too small" }
    if (nNewRows <= 0 || width <= 0) return populatedRows
    val keepNew = nNewRows.coerceAtMost(previewRows)
    val srcOffset = (nNewRows - keepNew) * width
    val shift = (populatedRows + keepNew - previewRows).coerceAtLeast(0)
    if (shift > 0) {
        System.arraycopy(buffer, shift * width, buffer, 0, (populatedRows - shift) * width)
    }
    val dstRow = (populatedRows - shift).coerceAtLeast(0)
    System.arraycopy(newPixels, srcOffset, buffer, dstRow * width, keepNew * width)
    return (populatedRows + keepNew).coerceAtMost(previewRows)
}
