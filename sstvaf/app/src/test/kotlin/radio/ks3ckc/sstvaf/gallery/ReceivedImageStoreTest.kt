package radio.ks3ckc.sstvaf.gallery

import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.database.DatabaseOpr
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import radio.ks3ckc.sstvaf.sstv.SstvMode
import java.io.File
import java.time.Instant

/**
 * [ReceivedImageStore]: PNG + metadata persistence, list ordering, delete
 * semantics, file-name goldens, and the MediaStore (Photos) export gate.
 */
@RunWith(RobolectricTestRunner::class)
class ReceivedImageStoreTest {

    private lateinit var context: Context
    private lateinit var opr: DatabaseOpr
    private val logLines = mutableListOf<String>()
    private var photosSetting = true

    /** 2026-07-04T15:30:12Z — the golden timestamp from the PR spec. */
    private val goldenUtc = Instant.parse("2026-07-04T15:30:12Z").toEpochMilli()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // In-memory DB (name=null) through the real helper so the store runs
        // against the genuine v19 schema.
        opr = DatabaseOpr(context, null, null, 19)
        photosSetting = true
    }

    @After
    fun tearDown() {
        opr.close()
        File(context.filesDir, SSTV_IMAGES_DIR).deleteRecursively()
    }

    private fun store() = ReceivedImageStore(
        context = context,
        db = opr.db,
        log = { logLines.add(it) },
        saveToPhotosEnabled = { photosSetting },
    )

    private fun saveOne(
        utcMillis: Long = goldenUtc,
        direction: ImageDirection = ImageDirection.RX,
        mode: SstvMode = SstvMode.SCOTTIE_1,
        complete: Boolean = true,
    ): SavedImage {
        val pixels = IntArray(mode.width * mode.height) { 0xFF336699.toInt() }
        return store().save(
            pixels = pixels,
            width = mode.width,
            height = mode.height,
            mode = mode,
            utcMillis = utcMillis,
            freqHz = 14_230_000L,
            direction = direction,
            complete = complete,
            quality = 0.87f,
        )
    }

    private fun mediaStoreInserts() =
        Shadows.shadowOf(context.contentResolver).insertStatements
            .filter { it.uri == MediaStore.Images.Media.EXTERNAL_CONTENT_URI }

    // ----- buildImageFileName goldens ---------------------------------------

    @Test
    fun fileName_goldenRx() {
        assertThat(buildImageFileName(goldenUtc, "S1", 14_230_000L, ImageDirection.RX))
            .isEqualTo("SSTV_20260704T153012Z_S1_14230000_RX.png")
    }

    @Test
    fun fileName_goldenTx() {
        assertThat(buildImageFileName(goldenUtc, "PD120", 7_171_000L, ImageDirection.TX))
            .isEqualTo("SSTV_20260704T153012Z_PD120_7171000_TX.png")
    }

    @Test
    fun fileName_zeroPadsUtcFields() {
        val early = Instant.parse("2026-01-02T03:04:05Z").toEpochMilli()
        assertThat(buildImageFileName(early, "R36", 3_730_000L, ImageDirection.RX))
            .isEqualTo("SSTV_20260102T030405Z_R36_3730000_RX.png")
    }

    @Test
    fun fileName_formatsInUtcRegardlessOfDefaultZone() {
        val zone = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
            assertThat(buildImageFileName(goldenUtc, "S1", 14_230_000L, ImageDirection.RX))
                .isEqualTo("SSTV_20260704T153012Z_S1_14230000_RX.png")
        } finally {
            java.util.TimeZone.setDefault(zone)
        }
    }

    // ----- save --------------------------------------------------------------

    @Test
    fun save_writesPngFile() {
        val saved = saveOne()

        val file = store().imageFile(saved)
        assertThat(file.exists()).isTrue()
        assertThat(file.length()).isGreaterThan(0L)
        assertThat(file.parentFile!!.name).isEqualTo(SSTV_IMAGES_DIR)
        // PNG magic bytes — the file really is a PNG, not raw pixels.
        val header = file.inputStream().use { it.readNBytes(8) }
        assertThat(header).isEqualTo(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
        )
    }

    @Test
    fun save_insertsMetadataRow() {
        val saved = saveOne()

        val listed = store().list()
        assertThat(listed).hasSize(1)
        val row = listed[0]
        assertThat(row.id).isEqualTo(saved.id)
        assertThat(row.fileName).isEqualTo("SSTV_20260704T153012Z_S1_14230000_RX.png")
        assertThat(row.direction).isEqualTo(ImageDirection.RX)
        assertThat(row.mode).isEqualTo("Scottie 1")
        assertThat(row.freqHz).isEqualTo(14_230_000L)
        assertThat(row.utcMillis).isEqualTo(goldenUtc)
        assertThat(row.width).isEqualTo(320)
        assertThat(row.height).isEqualTo(256)
        assertThat(row.complete).isTrue()
        assertThat(row.quality).isWithin(1e-6f).of(0.87f)
        assertThat(row.notes).isEmpty()
    }

    @Test
    fun save_partialImage_recordsIncomplete() {
        saveOne(complete = false)
        assertThat(store().list()[0].complete).isFalse()
    }

    // ----- list ----------------------------------------------------------------

    @Test
    fun list_returnsNewestFirst() {
        val older = saveOne(utcMillis = goldenUtc - 60_000, mode = SstvMode.ROBOT_36)
        val newest = saveOne(utcMillis = goldenUtc + 60_000, mode = SstvMode.MARTIN_1)
        val middle = saveOne(utcMillis = goldenUtc, mode = SstvMode.PD_90)

        assertThat(store().list().map { it.id })
            .containsExactly(newest.id, middle.id, older.id)
            .inOrder()
    }

    // ----- delete ----------------------------------------------------------------

    @Test
    fun delete_removesRowAndFile() {
        val saved = saveOne()
        val file = store().imageFile(saved)
        assertThat(file.exists()).isTrue()

        store().delete(saved.id)

        assertThat(store().list()).isEmpty()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun delete_toleratesMissingFile() {
        val saved = saveOne()
        assertThat(store().imageFile(saved).delete()).isTrue() // file vanishes out-of-band

        store().delete(saved.id) // must not throw

        assertThat(store().list()).isEmpty()
    }

    @Test
    fun delete_unknownId_isNoOp() {
        saveOne()
        store().delete(9999L)
        assertThat(store().list()).hasSize(1)
    }

    // ----- updateNotes ---------------------------------------------------------

    @Test
    fun updateNotes_persistsNoteOnRow() {
        val saved = saveOne()
        assertThat(store().list()[0].notes).isEmpty()

        assertThat(store().updateNotes(saved.id, "de W1AW")).isTrue()

        assertThat(store().list()[0].notes).isEqualTo("de W1AW")
    }

    @Test
    fun updateNotes_overwritesAndClears() {
        val saved = saveOne()
        store().updateNotes(saved.id, "first")
        assertThat(store().list()[0].notes).isEqualTo("first")

        store().updateNotes(saved.id, "")
        assertThat(store().list()[0].notes).isEmpty()
    }

    @Test
    fun updateNotes_unknownId_returnsFalse() {
        saveOne()
        assertThat(store().updateNotes(9999L, "nope")).isFalse()
    }

    // ----- MediaStore (Photos) export -----------------------------------------

    @Test
    @Config(sdk = [29])
    fun save_rxOn29_exportsToMediaStore() {
        saveOne(direction = ImageDirection.RX)

        val inserts = mediaStoreInserts()
        assertThat(inserts).hasSize(1)
        val values = inserts[0].contentValues
        assertThat(values.getAsString(MediaStore.Images.Media.DISPLAY_NAME))
            .isEqualTo("SSTV_20260704T153012Z_S1_14230000_RX.png")
        assertThat(values.getAsString(MediaStore.Images.Media.MIME_TYPE)).isEqualTo("image/png")
        assertThat(values.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
            .isEqualTo("Pictures/SSTVAF")
    }

    @Test
    @Config(sdk = [28])
    fun save_rxPre29_skipsMediaStoreSilently() {
        saveOne(direction = ImageDirection.RX)

        assertThat(mediaStoreInserts()).isEmpty()
        // Still fully saved to app storage.
        assertThat(store().list()).hasSize(1)
    }

    @Test
    @Config(sdk = [29])
    fun save_rxSettingOff_skipsMediaStore() {
        photosSetting = false
        saveOne(direction = ImageDirection.RX)

        assertThat(mediaStoreInserts()).isEmpty()
        assertThat(store().list()).hasSize(1)
    }

    @Test
    @Config(sdk = [29])
    fun save_tx_neverExportsToMediaStore() {
        saveOne(direction = ImageDirection.TX)

        assertThat(mediaStoreInserts()).isEmpty()
        assertThat(store().list()).hasSize(1)
    }

    // ----- manual Photos export (gallery viewer, PR 7) --------------------------

    @Test
    @Config(sdk = [29])
    fun exportToPhotos_manual_insertsMediaStoreCopy() {
        photosSetting = false // manual export ignores the auto-save setting
        val saved = saveOne(direction = ImageDirection.TX) // ...and the direction gate

        assertThat(store().exportToPhotos(saved)).isTrue()

        val inserts = mediaStoreInserts()
        assertThat(inserts).hasSize(1)
        val values = inserts[0].contentValues
        assertThat(values.getAsString(MediaStore.Images.Media.DISPLAY_NAME))
            .isEqualTo(saved.fileName)
        assertThat(values.getAsString(MediaStore.Images.Media.MIME_TYPE)).isEqualTo("image/png")
        assertThat(values.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
            .isEqualTo("Pictures/SSTVAF")
    }

    @Test
    @Config(sdk = [28])
    fun exportToPhotos_manual_pre29_returnsFalse() {
        val saved = saveOne()

        assertThat(store().exportToPhotos(saved)).isFalse()
        assertThat(mediaStoreInserts()).isEmpty()
    }

    @Test
    @Config(sdk = [29])
    fun exportToPhotos_manual_missingFile_returnsFalse() {
        photosSetting = false // keep the auto-export out of the insert list
        val saved = saveOne()
        assertThat(store().imageFile(saved).delete()).isTrue()

        assertThat(store().exportToPhotos(saved)).isFalse()
        assertThat(mediaStoreInserts()).isEmpty()
    }

    // ----- pure gate + mapping helpers -----------------------------------------

    @Test
    fun shouldExportToPhotos_gateCombinations() {
        assertThat(shouldExportToPhotos(ImageDirection.RX, true, 29)).isTrue()
        assertThat(shouldExportToPhotos(ImageDirection.RX, true, 34)).isTrue()
        assertThat(shouldExportToPhotos(ImageDirection.RX, true, 28)).isFalse()
        assertThat(shouldExportToPhotos(ImageDirection.RX, false, 29)).isFalse()
        assertThat(shouldExportToPhotos(ImageDirection.TX, true, 29)).isFalse()
    }

    @Test
    fun imageDirectionFromDb_degradesUnknownToRx() {
        assertThat(imageDirectionFromDb("RX")).isEqualTo(ImageDirection.RX)
        assertThat(imageDirectionFromDb("TX")).isEqualTo(ImageDirection.TX)
        assertThat(imageDirectionFromDb(null)).isEqualTo(ImageDirection.RX)
        assertThat(imageDirectionFromDb("bogus")).isEqualTo(ImageDirection.RX)
    }
}
