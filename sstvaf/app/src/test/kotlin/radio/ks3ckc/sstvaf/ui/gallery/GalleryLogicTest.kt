package radio.ks3ckc.sstvaf.ui.gallery

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test
import radio.ks3ckc.sstvaf.gallery.ImageDirection
import radio.ks3ckc.sstvaf.gallery.SavedImage

/**
 * GalleryLogic.kt: filter/sort decisions and the label formatting for grid
 * cells and the viewer sheet. Pure logic — no Robolectric (the R.string
 * references are plain compile-time ints).
 */
class GalleryLogicTest {

    /** 2026-07-04T15:30:12Z. */
    private val goldenUtc = 1_783_179_012_000L

    private fun image(
        id: Long = 1L,
        direction: ImageDirection = ImageDirection.RX,
        mode: String = "Scottie 1",
        freqHz: Long = 14_230_000L,
        utcMillis: Long = goldenUtc,
        width: Int = 320,
        height: Int = 256,
        complete: Boolean = true,
        quality: Float = 0.87f,
        notes: String = "",
    ) = SavedImage(id, "f$id.png", direction, mode, freqHz, utcMillis, width, height, complete, quality, notes)

    // ----- filtering ---------------------------------------------------------

    @Test
    fun filter_all_keepsEverything() {
        val images = listOf(
            image(id = 1, direction = ImageDirection.RX),
            image(id = 2, direction = ImageDirection.TX),
        )
        assertThat(filterGalleryImages(images, GalleryFilter.ALL)).isEqualTo(images)
    }

    @Test
    fun filter_rx_keepsOnlyReceived() {
        val images = listOf(
            image(id = 1, direction = ImageDirection.RX),
            image(id = 2, direction = ImageDirection.TX),
            image(id = 3, direction = ImageDirection.RX),
        )
        assertThat(filterGalleryImages(images, GalleryFilter.RX).map { it.id })
            .containsExactly(1L, 3L).inOrder()
    }

    @Test
    fun filter_tx_keepsOnlySent() {
        val images = listOf(
            image(id = 1, direction = ImageDirection.RX),
            image(id = 2, direction = ImageDirection.TX),
        )
        assertThat(filterGalleryImages(images, GalleryFilter.TX).map { it.id })
            .containsExactly(2L)
    }

    @Test
    fun filter_emptyInput_staysEmpty() {
        for (filter in GalleryFilter.entries) {
            assertThat(filterGalleryImages(emptyList(), filter)).isEmpty()
        }
    }

    // ----- sorting -----------------------------------------------------------

    @Test
    fun sort_newestFirst() {
        val sorted = sortGalleryImages(
            listOf(
                image(id = 1, utcMillis = goldenUtc - 60_000),
                image(id = 2, utcMillis = goldenUtc + 60_000),
                image(id = 3, utcMillis = goldenUtc),
            ),
        )
        assertThat(sorted.map { it.id }).containsExactly(2L, 3L, 1L).inOrder()
    }

    @Test
    fun sort_sameMillisecond_higherIdFirst() {
        val sorted = sortGalleryImages(
            listOf(
                image(id = 1, utcMillis = goldenUtc),
                image(id = 2, utcMillis = goldenUtc),
            ),
        )
        assertThat(sorted.map { it.id }).containsExactly(2L, 1L).inOrder()
    }

    // ----- mode short code ---------------------------------------------------

    @Test
    fun modeShort_mapsEveryDisplayName() {
        val cases = mapOf(
            "Robot 36" to "R36",
            "Robot 72" to "R72",
            "Martin 1" to "M1",
            "Martin 2" to "M2",
            "Scottie 1" to "S1",
            "Scottie 2" to "S2",
            "PD 50" to "PD50",
            "PD 90" to "PD90",
            "PD 120" to "PD120",
        )
        for ((displayName, short) in cases) {
            assertThat(galleryModeShort(displayName)).isEqualTo(short)
        }
    }

    @Test
    fun modeShort_unknownNameFallsThrough() {
        assertThat(galleryModeShort("AVT 90")).isEqualTo("AVT 90")
    }

    // ----- frequency ---------------------------------------------------------

    @Test
    fun frequency_formatsMhzWithThreeDecimals() {
        assertThat(formatGalleryFreqMhz(14_230_000L)).isEqualTo("14.230")
        assertThat(formatGalleryFreqMhz(7_171_000L)).isEqualTo("7.171")
        assertThat(formatGalleryFreqMhz(0L)).isEqualTo("0.000")
        assertThat(formatGalleryFreqMhz(144_500_000L)).isEqualTo("144.500")
    }

    @Test
    fun viewerFrequency_appendsUnit() {
        assertThat(formatViewerFrequency(14_230_000L)).isEqualTo("14.230 MHz")
    }

    // ----- date labels -------------------------------------------------------

    @Test
    fun date_relativeAndAbsoluteBands() {
        val now = goldenUtc
        val cases = mapOf(
            now to "just now",
            now - 59_000L to "just now",
            now + 5_000L to "just now", // clock skew: never negative
            now - 60_000L to "1m ago",
            now - 37 * 60_000L to "37m ago",
            now - 59 * 60_000L to "59m ago",
            now - 3_600_000L to "1h ago",
            now - 5 * 3_600_000L to "5h ago",
            now - 23 * 3_600_000L to "23h ago",
            now - 24 * 3_600_000L to "2026-07-03",
            now - 3 * 86_400_000L to "2026-07-01",
        )
        for ((utc, expected) in cases) {
            assertThat(formatGalleryDate(utc, now)).isEqualTo(expected)
        }
    }

    @Test
    fun date_absoluteIsUtcRegardlessOfDefaultZone() {
        val zone = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
            // 2026-07-02T02:30:12Z is still July 1 22:30 in New York; the label
            // must show the UTC date.
            assertThat(formatGalleryDate(goldenUtc - 2 * 86_400_000L - 13 * 3_600_000L, goldenUtc))
                .isEqualTo("2026-07-02")
        } finally {
            java.util.TimeZone.setDefault(zone)
        }
    }

    // ----- grid cell meta line -----------------------------------------------

    @Test
    fun cellMeta_combinesModeShortAndAge() {
        val entry = image(mode = "Scottie 1", utcMillis = goldenUtc - 5 * 3_600_000L)
        assertThat(galleryCellMeta(entry, goldenUtc)).isEqualTo("S1 · 5h ago")
    }

    @Test
    fun cellMeta_oldImageUsesAbsoluteDate() {
        val entry = image(mode = "PD 120", utcMillis = goldenUtc - 10 * 86_400_000L)
        assertThat(galleryCellMeta(entry, goldenUtc)).isEqualTo("PD120 · 2026-06-24")
    }

    // ----- empty state -------------------------------------------------------

    @Test
    fun emptyState_perFilter() {
        assertThat(galleryEmptyStateRes(GalleryFilter.ALL)).isEqualTo(R.string.gallery_empty_rx)
        assertThat(galleryEmptyStateRes(GalleryFilter.RX)).isEqualTo(R.string.gallery_empty_rx)
        assertThat(galleryEmptyStateRes(GalleryFilter.TX)).isEqualTo(R.string.gallery_empty_tx)
    }

    // ----- empty-state bottom padding (#24) ----------------------------------

    @Test
    fun emptyStatePadding_reservesStripHeight() {
        // The default reserves the approximate TX-strip height so the centered
        // illustration clears the always-on strip in a short/landscape canvas.
        assertThat(emptyStateBottomPadding()).isEqualTo(TxStripApproxHeight)
        assertThat(TxStripApproxHeight.value).isGreaterThan(0f)
    }

    @Test
    fun emptyStatePadding_passesThroughPositiveHeight() {
        assertThat(emptyStateBottomPadding(64.dp)).isEqualTo(64.dp)
        assertThat(emptyStateBottomPadding(0.dp)).isEqualTo(0.dp)
    }

    @Test
    fun emptyStatePadding_clampsNegativeToZero() {
        // A bad/negative measurement must never yield negative padding.
        assertThat(emptyStateBottomPadding((-20).dp)).isEqualTo(0.dp)
    }

    // ----- viewer metadata ---------------------------------------------------

    @Test
    fun viewerUtc_fullTimestamp() {
        assertThat(formatViewerUtc(goldenUtc)).isEqualTo("2026-07-04 15:30:12 UTC")
    }

    @Test
    fun viewerDimensions() {
        assertThat(formatViewerDimensions(320, 256)).isEqualTo("320 × 256")
        assertThat(formatViewerDimensions(640, 496)).isEqualTo("640 × 496")
    }

    @Test
    fun viewerQuality_wholePercentClamped() {
        assertThat(formatViewerQuality(0.87f)).isEqualTo("87%")
        assertThat(formatViewerQuality(0f)).isEqualTo("0%")
        assertThat(formatViewerQuality(1f)).isEqualTo("100%")
        assertThat(formatViewerQuality(1.4f)).isEqualTo("100%") // clamped
        assertThat(formatViewerQuality(-0.2f)).isEqualTo("0%") // clamped
        assertThat(formatViewerQuality(0.005f)).isEqualTo("1%") // rounds
    }

    @Test
    fun viewerDirectionAndCompletenessLabels() {
        assertThat(viewerDirectionRes(ImageDirection.RX))
            .isEqualTo(R.string.gallery_meta_direction_rx)
        assertThat(viewerDirectionRes(ImageDirection.TX))
            .isEqualTo(R.string.gallery_meta_direction_tx)
        assertThat(viewerCompletenessRes(true)).isEqualTo(R.string.gallery_meta_complete)
        assertThat(viewerCompletenessRes(false)).isEqualTo(R.string.gallery_meta_partial)
    }

    @Test
    fun viewerAspectRatio_nativeOrFallback() {
        assertThat(viewerAspectRatio(320, 240)).isWithin(1e-6f).of(4f / 3f)
        assertThat(viewerAspectRatio(320, 256)).isWithin(1e-6f).of(1.25f)
        assertThat(viewerAspectRatio(0, 0)).isWithin(1e-6f).of(4f / 3f)
        assertThat(viewerAspectRatio(320, 0)).isWithin(1e-6f).of(4f / 3f)
    }

    // ----- share caption -----------------------------------------------------

    @Test
    fun shareUtc_minutePrecision() {
        assertThat(formatShareUtc(goldenUtc)).isEqualTo("2026-07-04 15:30 UTC")
    }

    @Test
    fun shareUtc_isUtcRegardlessOfDefaultZone() {
        val zone = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
            assertThat(formatShareUtc(goldenUtc)).isEqualTo("2026-07-04 15:30 UTC")
        } finally {
            java.util.TimeZone.setDefault(zone)
        }
    }

    @Test
    fun shareCaption_combinesModeFreqAndTime() {
        val entry = image(mode = "Scottie 1", freqHz = 14_230_000L, utcMillis = goldenUtc)
        assertThat(buildImageShareCaption(entry))
            .isEqualTo("SSTV Scottie 1 · 14.230 MHz · 2026-07-04 15:30 UTC")
    }

    @Test
    fun shareCaption_usesStoredModeNameVerbatim() {
        val entry = image(mode = "AVT 90", freqHz = 7_171_000L, utcMillis = goldenUtc)
        assertThat(buildImageShareCaption(entry))
            .isEqualTo("SSTV AVT 90 · 7.171 MHz · 2026-07-04 15:30 UTC")
    }

    @Test
    fun shareCaption_appendsNoteWhenPresent() {
        val entry = image(mode = "Scottie 1", freqHz = 14_230_000L, utcMillis = goldenUtc, notes = "W1AW")
        assertThat(buildImageShareCaption(entry))
            .isEqualTo("SSTV Scottie 1 · 14.230 MHz · 2026-07-04 15:30 UTC · W1AW")
    }

    @Test
    fun shareCaption_blankOrWhitespaceNoteOmitted() {
        val blank = image(notes = "")
        val spaces = image(notes = "   ")
        val base = "SSTV Scottie 1 · 14.230 MHz · 2026-07-04 15:30 UTC"
        assertThat(buildImageShareCaption(blank)).isEqualTo(base)
        assertThat(buildImageShareCaption(spaces)).isEqualTo(base)
    }

    @Test
    fun shareCaption_normalizesMultilineNote() {
        val entry = image(notes = "  de W1AW\n  73  ")
        assertThat(buildImageShareCaption(entry))
            .isEqualTo("SSTV Scottie 1 · 14.230 MHz · 2026-07-04 15:30 UTC · de W1AW 73")
    }

    // ----- note normalization / edit gate ------------------------------------

    @Test
    fun normalizeNote_trimsCollapsesAndSingleLines() {
        assertThat(normalizeNote("  W1AW  ")).isEqualTo("W1AW")
        assertThat(normalizeNote("de   W1AW")).isEqualTo("de W1AW")
        assertThat(normalizeNote("line1\nline2\tX")).isEqualTo("line1 line2 X")
        assertThat(normalizeNote("   ")).isEmpty()
        assertThat(normalizeNote("")).isEmpty()
    }

    @Test
    fun normalizeNote_capsLength() {
        val long = "A".repeat(MAX_NOTE_LENGTH + 40)
        assertThat(normalizeNote(long)).hasLength(MAX_NOTE_LENGTH)
    }

    @Test
    fun noteEdited_ignoresWhitespaceOnlyChurn() {
        assertThat(noteEdited("W1AW", "W1AW")).isFalse()
        assertThat(noteEdited("  W1AW ", "W1AW")).isFalse() // trailing space, not a change
        assertThat(noteEdited("de W1AW", "W1AW")).isTrue()
        assertThat(noteEdited("", "W1AW")).isTrue() // clearing a note is an edit
        assertThat(noteEdited("W1AW", "")).isTrue() // adding a note is an edit
        assertThat(noteEdited("", "")).isFalse()
    }

    // ----- filter chip labels ------------------------------------------------

    @Test
    fun filterChipLabels_wiredPerFilter() {
        assertThat(GalleryFilter.ALL.labelRes()).isEqualTo(R.string.gallery_filter_all)
        assertThat(GalleryFilter.RX.labelRes()).isEqualTo(R.string.gallery_filter_rx)
        assertThat(GalleryFilter.TX.labelRes()).isEqualTo(R.string.gallery_filter_tx)
    }
}
