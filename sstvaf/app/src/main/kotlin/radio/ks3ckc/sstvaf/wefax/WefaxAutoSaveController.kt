package radio.ks3ckc.sstvaf.wefax

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.k1af.ft8af.GeneralVariables
import radio.ks3ckc.sstvaf.gallery.ImageDirection
import radio.ks3ckc.sstvaf.gallery.ReceivedImageStore
import radio.ks3ckc.sstvaf.gallery.SavedImage
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Expands an 8-bit gray fax strip to the 0xAARRGGBB pixels the image store
 * takes: every gray byte becomes an opaque pixel with R = G = B = gray.
 */
internal fun wefaxGrayToArgb(gray: ByteArray, count: Int): IntArray {
    val n = count.coerceAtMost(gray.size)
    return IntArray(n) { i ->
        val g = gray[i].toInt() and 0xFF
        (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }
}

/** The mode short code stored in the file name of a saved fax strip. */
internal const val WEFAX_MODE_SHORT_CODE = "WEFAX"

/** The mode display string for a saved fax strip, e.g. "WEFAX 120/576". */
internal fun wefaxModeDisplayName(lpm: Int, ioc: Int): String = "WEFAX $lpm/$ioc"

/**
 * Persists finished WEFAX strips: [attach] points the listener's
 * `onImageFinished` here, and each strip is converted to ARGB and written
 * through the shared [ReceivedImageStore] (PNG + `sstv_images` row + optional
 * Photos copy) on a background thread — the callback arrives on the
 * listener's stop-finalizer thread, and the store is synchronous by
 * contract. [lastSaved] publishes the stored row so the fax screen can show
 * a "saved" confirmation; a failed save publishes nothing but logs.
 */
class WefaxAutoSaveController @JvmOverloads constructor(
    private val store: ReceivedImageStore,
    private val dialFrequency: () -> Long = { GeneralVariables.band },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = { GeneralVariables.fileLog(it) },
    private val ioExecutor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WefaxSave").apply { isDaemon = true }
    },
) {

    private val mutableLastSaved = MutableLiveData<SavedImage?>(null)
    val lastSaved: LiveData<SavedImage?> get() = mutableLastSaved

    fun attach(listener: WefaxSignalListener) {
        listener.onImageFinished = { img -> ioExecutor.execute { save(img) } }
    }

    private fun save(img: WefaxSignalListener.FinishedImage) {
        try {
            val saved = store.save(
                pixels = wefaxGrayToArgb(img.gray, img.width * img.rows),
                width = img.width,
                height = img.rows,
                modeShortCode = WEFAX_MODE_SHORT_CODE,
                modeDisplayName = wefaxModeDisplayName(img.lpm, img.ioc),
                utcMillis = clock(),
                freqHz = dialFrequency(),
                direction = ImageDirection.RX,
                complete = true,
                // Radiofax has no per-image quality metric (nothing like the
                // SSTV sync-hit blend), so the row records zero rather than a
                // made-up score.
                quality = 0f,
            )
            log("WEFAX RX: strip saved — ${saved.fileName} (${img.width}x${img.rows})")
            mutableLastSaved.postValue(saved)
        } catch (t: Throwable) {
            log("WEFAX RX: strip save FAILED (${img.width}x${img.rows}): $t")
        }
    }
}
