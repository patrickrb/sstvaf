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
 * How many images each filter would show, for the chip-row count badges. A
 * single pass over the (unfiltered) list; ALL is the sum so it always equals
 * RX + TX. Every filter is present in the map even when its count is 0 — the
 * [require] below guards that contract so a future [GalleryFilter] value can't
 * silently ship a chip with no count (it would fail here and in the unit test
 * rather than rendering a blank/0 badge).
 */
internal fun galleryFilterCounts(images: List<SavedImage>): Map<GalleryFilter, Int> {
    var rx = 0
    var tx = 0
    for (image in images) {
        when (image.direction) {
            ImageDirection.RX -> rx++
            ImageDirection.TX -> tx++
        }
    }
    val counts = mapOf(
        GalleryFilter.ALL to rx + tx,
        GalleryFilter.RX to rx,
        GalleryFilter.TX to tx,
    )
    require(counts.keys == GalleryFilter.entries.toSet()) {
        "galleryFilterCounts missing entries for ${GalleryFilter.entries - counts.keys}"
    }
    return counts
}

/**
 * Chip label with a trailing count badge, e.g. "Received 9". The count is shown
 * even when 0 ("Sent 0") so the row reads as a stable at-a-glance history
 * summary rather than hiding empty categories.
 *
 * [pattern] is the `gallery_filter_chip_label` resource (`"%1$s %2$d"`) so the
 * base-label/number ordering and spacing live in a string resource a translator
 * can reorder (e.g. for RTL) rather than being hard-coded here. Formatted with
 * [Locale.US] so the digits stay Western, matching the frequency/quality
 * readouts elsewhere in the gallery.
 */
internal fun galleryFilterChipLabel(pattern: String, baseLabel: String, count: Int): String =
    String.format(Locale.US, pattern, baseLabel, count)

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

// ---------------------------------------------------------------------------
// Date grouping (section headers) for the gallery grid
// ---------------------------------------------------------------------------

/**
 * A gallery section header. Grouping is by UTC calendar day (consistent with
 * the app's UTC date display elsewhere), so the buckets are deterministic
 * regardless of the device time zone.
 */
internal sealed interface GallerySectionHeader {
    /** Same UTC day as "now". */
    data object Today : GallerySectionHeader

    /** The UTC day immediately before "now". */
    data object Yesterday : GallerySectionHeader

    /** An older UTC day, carrying its absolute date label, e.g. "2026-07-04". */
    data class Earlier(val dateLabel: String) : GallerySectionHeader
}

/** One contiguous run of images sharing a UTC day, with its header. */
internal data class GallerySection(
    val header: GallerySectionHeader,
    val images: List<SavedImage>,
)

/** UTC calendar-day index (days since the epoch); [Math.floorDiv] handles skew. */
private fun utcDayIndex(utcMillis: Long): Long = Math.floorDiv(utcMillis, 86_400_000L)

/**
 * Groups [images] into date sections for the grid, newest day first and newest
 * image first within each day. Input order is not trusted — the list is sorted
 * with [sortGalleryImages] first, so callers can pass a raw or a pre-filtered
 * list. A timestamp ahead of [nowMillis] (clock skew) is clamped into the
 * Today bucket rather than forming a spurious future day. Returns an empty list
 * for empty input.
 */
internal fun buildGallerySections(
    images: List<SavedImage>,
    nowMillis: Long,
): List<GallerySection> {
    val sorted = sortGalleryImages(images)
    if (sorted.isEmpty()) return emptyList()

    val todayIdx = utcDayIndex(nowMillis)
    val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    val sections = mutableListOf<GallerySection>()
    var currentIdx: Long? = null
    var bucket = mutableListOf<SavedImage>()

    fun flush() {
        val idx = currentIdx ?: return
        val header = when (todayIdx - idx) {
            0L -> GallerySectionHeader.Today
            1L -> GallerySectionHeader.Yesterday
            // Label from that day's UTC midnight so it never depends on which
            // image in the bucket happened to be formatted.
            else -> GallerySectionHeader.Earlier(dateFmt.format(Date(idx * 86_400_000L)))
        }
        sections.add(GallerySection(header, bucket))
    }

    for (image in sorted) {
        val idx = minOf(utcDayIndex(image.utcMillis), todayIdx)
        if (idx != currentIdx) {
            flush()
            currentIdx = idx
            bucket = mutableListOf()
        }
        bucket.add(image)
    }
    flush()
    return sections
}

/** Stable LazyGrid key for a section header (Today/Yesterday collapse to a slug). */
internal fun gallerySectionKey(header: GallerySectionHeader): String = when (header) {
    GallerySectionHeader.Today -> "today"
    GallerySectionHeader.Yesterday -> "yesterday"
    is GallerySectionHeader.Earlier -> header.dateLabel
}

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

/**
 * The image's SSTV mode → its nominal on-air transmission length as "m:ss"
 * (e.g. "Scottie 1" → "1:51"), for the viewer's "Air time" row. This is the
 * mode's full-frame duration (calibration header + image scan), so it is the
 * same length a partial RX capture would have taken had it completed — hence
 * it is shown for incomplete images too, as a property of the mode rather than
 * the individual capture. The store persists the mode's display name, so map
 * back through [SstvMode]; an unknown/hand-edited name yields null and the
 * caller omits the row (matching the band row's behaviour). Rounded to whole
 * seconds to match the TX mode-chip/confirm-sheet durations.
 */
internal fun formatViewerAirTime(modeName: String): String? {
    val seconds = SstvMode.entries
        .firstOrNull { it.displayName == modeName }
        ?.txDurationSeconds
        ?.roundToInt()
        ?.coerceAtLeast(0)
        ?: return null
    return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
}

/** Engine quality (0..1) as a whole percent, clamped, e.g. "87%". */
internal fun formatViewerQuality(quality: Float): String =
    "${(quality.coerceIn(0f, 1f) * 100f).roundToInt()}%"

/** Frequency line for the viewer, e.g. "14.230 MHz". */
internal fun formatViewerFrequency(freqHz: Long): String =
    "${formatGalleryFreqMhz(freqHz)} MHz"

// ---------------------------------------------------------------------------
// Amateur band lookup
// ---------------------------------------------------------------------------

/** One amateur band: its inclusive Hz range and short wavelength label. */
private data class AmateurBand(val loHz: Long, val hiHz: Long, val label: String)

/**
 * IARU amateur allocations, ascending and non-overlapping. These are the same
 * explicit band branches [com.k1af.ft8af.rigs.BaseRigOperation.getMeterFromFreq]
 * enumerates, so a frequency shows the same label wherever the app names it,
 * with two deliberate departures: the CB "11m" segment (26.965–27.405 MHz) and
 * that helper's computed out-of-band fallback (`calculationMeterFromFreq`) are
 * both omitted here — an out-of-band frequency yields null rather than a guessed
 * label (see [amateurBand]) — and the 4m allocation (70.0–70.5 MHz), which the
 * rig helper does not list, is included. Not a blanket mirror of every branch.
 */
private val AMATEUR_BANDS = listOf(
    AmateurBand(135_700L, 137_800L, "2200m"),
    AmateurBand(472_000L, 479_000L, "630m"),
    AmateurBand(1_800_000L, 2_000_000L, "160m"),
    AmateurBand(3_500_000L, 4_000_000L, "80m"),
    AmateurBand(5_351_500L, 5_366_500L, "60m"),
    AmateurBand(7_000_000L, 7_300_000L, "40m"),
    AmateurBand(10_100_000L, 10_150_000L, "30m"),
    AmateurBand(14_000_000L, 14_350_000L, "20m"),
    AmateurBand(18_068_000L, 18_168_000L, "17m"),
    AmateurBand(21_000_000L, 21_450_000L, "15m"),
    AmateurBand(24_890_000L, 24_990_000L, "12m"),
    AmateurBand(28_000_000L, 29_700_000L, "10m"),
    AmateurBand(50_000_000L, 54_000_000L, "6m"),
    AmateurBand(70_000_000L, 70_500_000L, "4m"),
    AmateurBand(144_000_000L, 148_000_000L, "2m"),
    AmateurBand(222_000_000L, 225_000_000L, "1.25m"),
    AmateurBand(420_000_000L, 450_000_000L, "70cm"),
    AmateurBand(902_000_000L, 928_000_000L, "33cm"),
    AmateurBand(1_240_000_000L, 1_300_000_000L, "23cm"),
)

/**
 * The amateur-radio band a dial frequency falls in ("20m", "40m", …), or null
 * when the frequency is outside every known amateur allocation — 0, a
 * hand-edited row, or an out-of-band capture (e.g. CB 27 MHz). Ranges are
 * inclusive. Surfaced next to the raw MHz so an operator recognises the band
 * at a glance without reading the digits.
 */
internal fun amateurBand(freqHz: Long): String? =
    AMATEUR_BANDS.firstOrNull { freqHz in it.loHz..it.hiHz }?.label

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
 * SSTV context: "SSTV Scottie 1 · 14.230 MHz · 20m · 2026-07-04 15:30 UTC". The
 * band segment is only present when the frequency maps to a known amateur band
 * (see [amateurBand]); an out-of-band capture drops it rather than showing a
 * blank. Mode name comes from the store verbatim (an unknown/hand-edited name
 * is used as-is).
 */
internal fun buildImageShareCaption(entry: SavedImage): String {
    val freq = formatViewerFrequency(entry.freqHz)
    val bandSegment = amateurBand(entry.freqHz)?.let { " · $it" } ?: ""
    return "SSTV ${entry.mode} · $freq$bandSegment · ${formatShareUtc(entry.utcMillis)}"
}

/** Completeness → viewer label resource (Complete / Partial). */
internal fun viewerCompletenessRes(complete: Boolean): Int =
    if (complete) R.string.gallery_meta_complete else R.string.gallery_meta_partial
