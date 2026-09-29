package radio.ks3ckc.sstvaf.sstv.digital

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.database.DatabaseOpr
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.sstvaf.gallery.ImageDirection
import radio.ks3ckc.sstvaf.gallery.ReceivedImageStore
import radio.ks3ckc.sstvaf.gallery.SSTV_IMAGES_DIR
import radio.ks3ckc.sstvaf.sstv.FakeSstvCodec
import radio.ks3ckc.sstvaf.sstv.SstvSignalListener
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executor

/**
 * [DigitalRxSaveController] against the real [ReceivedImageStore] on an
 * in-memory DB (same pattern as ReceivedImageStoreTest). The executor is
 * synchronous so saves land before assertions.
 */
@RunWith(RobolectricTestRunner::class)
class DigitalRxSaveControllerTest {

    private lateinit var context: Context
    private lateinit var opr: DatabaseOpr
    private val logLines = mutableListOf<String>()
    private val directExecutor = Executor { it.run() }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        opr = DatabaseOpr(context, null, null, 19)
    }

    @After
    fun tearDown() {
        opr.close()
        File(context.filesDir, SSTV_IMAGES_DIR).deleteRecursively()
    }

    private fun store() = ReceivedImageStore(
        context = context,
        db = opr.db,
        log = { logLines += it },
        saveToPhotosEnabled = { false },
    )

    private fun controller(store: ReceivedImageStore = store()) = DigitalRxSaveController(
        store = store,
        dialFrequency = { 14_230_000L },
        clock = { 1_720_000_000_000L },
        log = { logLines += it },
        ioExecutor = directExecutor,
    )

    private fun jpegImage(w: Int = 16, h: Int = 12): DigitalRxImage {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(40, 80, 120))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return DigitalRxImage(
            DigitalSstvMode.STANDARD, DigitalSstvCodec.Format.JPEG, w, h, out.toByteArray(),
        )
    }

    @Test
    fun savesAJpegFrameThroughTheStore() {
        val st = store()
        controller(st).save(jpegImage())

        val rows = st.list()
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row.mode).isEqualTo("Digital Standard")
        assertThat(row.direction).isEqualTo(ImageDirection.RX)
        assertThat(row.complete).isTrue()
        assertThat(row.freqHz).isEqualTo(14_230_000L)
        assertThat(st.imageFile(row).exists()).isTrue()
        assertThat(logLines.any { it.contains("saved") }).isTrue()
    }

    @Test
    fun unrenderableFormatIsLoggedNotSaved() {
        val st = store()
        val raw = DigitalRxImage(
            DigitalSstvMode.FAST, DigitalSstvCodec.Format.RAW, 8, 8, ByteArray(64),
        )
        controller(st).save(raw)

        assertThat(st.list()).isEmpty()
        assertThat(logLines.any { it.contains("not renderable") }).isTrue()
    }

    // NOTE: a corrupt-JPEG payload can't be tested here — Robolectric's
    // graphics shadow decodes any byte array into a placeholder bitmap. The
    // decode-null branch is the same not-renderable path the RAW-format test
    // above exercises.

    @Test
    fun decodePayloadHandlesEachFormatTag() {
        val c = controller()
        assertThat(c.decodePayload(jpegImage())).isNotNull()
        val png = DigitalRxImage(
            DigitalSstvMode.STANDARD, DigitalSstvCodec.Format.JPEG2000, 8, 8, ByteArray(8),
        )
        assertThat(c.decodePayload(png)).isNull() // JPEG2000 has no renderer
    }

    @Test
    fun attachRegistersTheDirectCallbackAndSavesEveryFrame() {
        val st = store()
        val codec = FakeSstvCodec()
        val listener = SstvSignalListener(codec, 12000, 8, { logLines += it }, { 0L }, { 0L })
        controller(st).attach(listener)

        // Two frames delivered back-to-back through the direct callback must
        // BOTH be saved — the LiveData path would have coalesced them.
        listener.onDigitalImage!!.invoke(jpegImage(16, 12))
        listener.onDigitalImage!!.invoke(jpegImage(20, 10))

        assertThat(st.list()).hasSize(2)
    }
}
