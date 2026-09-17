package radio.ks3ckc.sstvaf.ui.tx

import radio.ks3ckc.sstvaf.gallery.SavedImage
import radio.ks3ckc.sstvaf.sstv.SstvMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The text-only cards an operator can send without a photo, and the "last sent"
 * shortcut.
 *
 * These exist because a lot of real SSTV traffic is not photographs — it is a
 * callsign and a report, legibly, over a noisy path. Making that a one-tap
 * start means an operator with nothing suitable in their camera roll is not
 * stuck.
 */

/** The grid card's second line, e.g. "FN42 · 59 599", or just the report. */
internal fun gridCardSubline(grid: String, report: String = DEFAULT_REPORT): String {
    val locator = grid.trim().uppercase(Locale.ROOT)
    return if (locator.isEmpty()) report else "$locator · $report"
}

/**
 * The signal report the grid card starts with.
 *
 * RSV 59 599 is the conventional "you're fine" exchange, and a starting point
 * the operator edits with the Text tool rather than a claim: nobody sends a
 * report before hearing the other station, so this is a template, not a
 * measurement.
 */
internal const val DEFAULT_REPORT = "59 599"

/**
 * The overlays a CQ card starts with: a large accent "CQ SSTV" over the
 * operator's callsign, both outlined.
 *
 * Outlined rather than barred, because a card with no photo under it has no
 * busy background to hide — bars would just be two black stripes across an
 * otherwise clean gradient.
 */
internal fun cqCardOverlays(callsign: String): List<TextOverlay> {
    val call = callsign.trim().uppercase(Locale.ROOT)
    return listOf(
        TextOverlay(
            id = "t1",
            text = "CQ SSTV",
            xPercent = 50f,
            yPercent = 40f,
            colorArgb = OVERLAY_COLOR_CYAN,
            sizeFraction = OVERLAY_SIZE_LARGE,
            style = OverlayStyle.OUTLINE,
        ),
        TextOverlay(
            id = CALLSIGN_OVERLAY_ID,
            text = if (call.isEmpty()) "" else "de $call",
            xPercent = 50f,
            yPercent = 60f,
            colorArgb = OVERLAY_COLOR_WHITE,
            sizeFraction = OVERLAY_SIZE_MEDIUM,
            style = OverlayStyle.OUTLINE,
        ),
    ).filter { it.text.isNotBlank() }
}

/**
 * The overlays a grid card starts with: the callsign in a top bar and the
 * grid/report in a bottom bar — the classic SSTV card layout.
 */
internal fun gridCardOverlays(callsign: String, grid: String): List<TextOverlay> {
    val call = callsign.trim().uppercase(Locale.ROOT)
    return listOf(
        TextOverlay(
            id = CALLSIGN_OVERLAY_ID,
            text = call,
            xPercent = 50f,
            yPercent = TOP_BAR_Y_PERCENT,
            colorArgb = OVERLAY_COLOR_WHITE,
            sizeFraction = OVERLAY_SIZE_MEDIUM,
            style = OverlayStyle.BAR,
        ),
        TextOverlay(
            id = "t1",
            text = gridCardSubline(grid),
            xPercent = 50f,
            yPercent = BOTTOM_BAR_Y_PERCENT,
            colorArgb = OVERLAY_COLOR_AMBER,
            sizeFraction = OVERLAY_SIZE_SMALL,
            style = OverlayStyle.BAR,
        ),
    ).filter { it.text.isNotBlank() }
}

/**
 * The gradient a text-only card is drawn on, as ARGB pairs.
 *
 * A gradient rather than flat colour, and a shallow one: SSTV encodes
 * brightness as frequency, so a large flat area transmits as a long constant
 * tone that is easy to mistake for a fault, while a steep gradient wastes the
 * limited contrast range on background. This is the middle.
 */
internal fun cardGradientArgb(kind: TxCardKind): Pair<Int, Int> = when (kind) {
    TxCardKind.CQ -> 0xFF0E131E.toInt() to 0xFF1D2538.toInt()
    TxCardKind.GRID -> 0xFF1A2A4A.toInt() to 0xFF0E131E.toInt()
}

/** Which text-only card to build. */
internal enum class TxCardKind { CQ, GRID }

/**
 * The caption on the "Last sent" row, e.g. "S1 · 14:02 · text kept, swap it".
 *
 * The trailing clause is the point of the row: it promises the overlays come
 * back with the picture, which is what makes this a shortcut rather than just
 * a thumbnail.
 */
internal fun lastSentCaption(entry: SavedImage, keptNote: String): String {
    val shortCode = SstvMode.entries.firstOrNull { it.displayName == entry.mode }?.shortCode
        ?: entry.mode
    val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(entry.utcMillis))
    return "$shortCode · $time · $keptNote"
}
