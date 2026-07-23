package radio.ks3ckc.sstvaf.ui.gallery

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.gallery.ImageDirection
import radio.ks3ckc.sstvaf.gallery.SavedImage
import radio.ks3ckc.sstvaf.sstv.SstvMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Pure decision/formatting logic for [GalleryScreen] and [ImageViewerSheet],
 * extracted per project testing policy (Composables stay thin wrappers; this
 * file carries the unit tests).
 */

/** The gallery's direction filter (the All / Received / Sent chip row). */
internal enum class GalleryFilter { ALL, RX, TX }

/** Which images the active filter shows. */
internal fun filterGalleryImages(
    images: List<SavedImage>,
    filter: GalleryFilter,
): List<SavedImage> = when (filter) {
    GalleryFilter.ALL -> images
    GalleryFilter.RX -> images.filter { it.direction == ImageDirection.RX }
    GalleryFilter.TX -> images.filter { it.direction == ImageDirection.TX }
}

/**
 * Newest first, matching the store's list order (utcMillis desc, id as the
 * tiebreak for two images finishing in the same millisecond).
 */
internal fun sortGalleryImages(images: List<SavedImage>): List<SavedImage> =
    images.sortedWith(
        compareByDescending<SavedImage> { it.utcMillis }.thenByDescending { it.id },
    )

/**
 * Mode display name → short code for the tight grid-cell line ("Scottie 1" →
 * "S1"). The store persists the display name, so map back through [SstvMode];
 * an unknown name (future mode, hand-edited DB) falls through unchanged.
 */
internal fun galleryModeShort(modeName: String): String =
    SstvMode.entries.firstOrNull { it.displayName == modeName }?.shortCode ?: modeName

/** Dial frequency in Hz → bare MHz label with kHz resolution, e.g. "14.230". */
internal fun formatGalleryFreqMhz(freqHz: Long): String =
    String.format(Locale.US, "%.3f", freqHz / 1_000_000.0)

/**
 * When the image landed, relative to [nowMillis]: "just now" under a minute,
 * "37m ago" under an hour, "5h ago" under a day, then the absolute UTC date
 * ("2026-07-04"). A timestamp ahead of the clock (skew) reads "just now".
 */
internal fun formatGalleryDate(utcMillis: Long, nowMillis: Long): String {
    val age = nowMillis - utcMillis
    return when {
        age < 60_000L -> "just now" // includes negative age (clock skew)
        age < 3_600_000L -> "${age / 60_000L}m ago"
        age < 86_400_000L -> "${age / 3_600_000L}h ago"
        else -> {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            fmt.format(Date(utcMillis))
        }
    }
}

/** The one-line metadata under a grid cell: mode short code + age, "S1 · 5h ago". */
internal fun galleryCellMeta(entry: SavedImage, nowMillis: Long): String =
    "${galleryModeShort(entry.mode)} · ${formatGalleryDate(entry.utcMillis, nowMillis)}"

/**
 * Empty-state copy for the active filter. TX images only exist from PR 8, so
 * the Sent filter gets its own line; All and Received both point at receiving.
 */
internal fun galleryEmptyStateRes(filter: GalleryFilter): Int = when (filter) {
    GalleryFilter.TX -> R.string.gallery_empty_tx
    else -> R.string.gallery_empty_rx
}

/**
 * Approximate collapsed height of the always-on TX status strip
 * (radio.ks3ckc.sstvaf.ui.components.TxStrip) with its volume slider hidden.
 * The strip has no fixed height — its size comes from its Column vertical
 * padding (8dp + 8dp) + 10dp inter-row spacing, plus the status row (~22dp
 * indicator) and the frequency/TUNE chip row (~30dp: 7dp+7dp padding around a
 * ~16dp label). That totals ~78dp, so this is a measured approximation used
 * only to bias the Gallery empty state up out from behind the strip (issue
 * #24). With the volume slider shown the strip is taller (~+46dp); the extra
 * spill in that rarer case is acceptable for a cosmetic empty state.
 */
internal val TxStripApproxHeight: Dp = 78.dp

/**
 * Bottom padding for the Gallery empty-state container (issue #24).
 *
 * In the app shell (see [radio.ks3ckc.sstvaf.SstvAfApp]) the tab content sits
 * in a `weight(1f)` Box and the always-on TX strip is the *next* sibling in the
 * Column, so the content area's height already excludes the strip. The problem
 * is that a Box does not clip its children: in a short / landscape canvas the
 * empty-state illustration + caption is taller than that content Box, so
 * centering it overflows the Box's bottom edge — and because the strip is
 * composed after the content, it draws over that spilled-out lower portion,
 * clipping the caption. Reserving the strip's height as bottom padding shrinks
 * the centering region to the visible band, biasing the content up so it no
 * longer overflows into the strip.
 *
 * A non-positive height (defensive against a bad measurement) yields no
 * padding, leaving the plain centered layout unchanged.
 */
internal fun emptyStateBottomPadding(txStripHeight: Dp = TxStripApproxHeight): Dp =
    txStripHeight.coerceAtLeast(0.dp)

// ---------------------------------------------------------------------------
// Viewer-sheet metadata formatting
// ---------------------------------------------------------------------------

/** Full UTC timestamp for the viewer, e.g. "2026-07-04 15:30:12 UTC". */
internal fun formatViewerUtc(utcMillis: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return "${fmt.format(Date(utcMillis))} UTC"
}

/** Image dimensions, e.g. "320 × 256". */
internal fun formatViewerDimensions(width: Int, height: Int): String = "$width × $height"

/** Engine quality (0..1) as a whole percent, clamped, e.g. "87%". */
internal fun formatViewerQuality(quality: Float): String =
    "${(quality.coerceIn(0f, 1f) * 100f).roundToInt()}%"

/** Frequency line for the viewer, e.g. "14.230 MHz". */
internal fun formatViewerFrequency(freqHz: Long): String =
    "${formatGalleryFreqMhz(freqHz)} MHz"

/** Direction → viewer label resource (Received / Sent). */
internal fun viewerDirectionRes(direction: ImageDirection): Int = when (direction) {
    ImageDirection.RX -> R.string.gallery_meta_direction_rx
    ImageDirection.TX -> R.string.gallery_meta_direction_tx
}

// ---------------------------------------------------------------------------
// Share caption
// ---------------------------------------------------------------------------

/** UTC timestamp to the minute for a share caption, e.g. "2026-07-04 15:30 UTC". */
internal fun formatShareUtc(utcMillis: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return "${fmt.format(Date(utcMillis))} UTC"
}

/**
 * Human-readable caption attached to a shared image (ACTION_SEND EXTRA_TEXT /
 * EXTRA_SUBJECT) so a picture arriving in a chat or on social media carries its
 * SSTV context: "SSTV Scottie 1 · 14.230 MHz · 2026-07-04 15:30 UTC". Mode name
 * comes from the store verbatim (an unknown/hand-edited name is used as-is).
 */
internal fun buildImageShareCaption(entry: SavedImage): String {
    val freq = formatViewerFrequency(entry.freqHz)
    return "SSTV ${entry.mode} · $freq · ${formatShareUtc(entry.utcMillis)}"
}

/** Completeness → viewer label resource (Complete / Partial). */
internal fun viewerCompletenessRes(complete: Boolean): Int =
    if (complete) R.string.gallery_meta_complete else R.string.gallery_meta_partial
