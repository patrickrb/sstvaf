package radio.ks3ckc.sstvaf.sstv.digital

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.k1af.ft8af.GeneralVariables
import radio.ks3ckc.sstvaf.sstv.SstvSignalListener
import radio.ks3ckc.sstvaf.gallery.ImageDirection
import radio.ks3ckc.sstvaf.gallery.ReceivedImageStore
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Renders and persists completed digital-SSTV images: observes the signal
 * listener's [digitalResult] holder, decodes the compressed payload
 * (JPEG/PNG/WEBP via BitmapFactory) on a background thread, and files the
 * pixels through the shared [ReceivedImageStore] so digital receptions land
 * in the Gallery beside the analog ones.
 *
 * `quality` is stored as 1.0: a digital frame either survives its
 * Reed-Solomon/CRC gauntlet completely or is dropped before it gets here —
 * there is no partial-quality middle ground to report.
 */
class DigitalRxSaveController @JvmOverloads constructor(
    private val store: ReceivedImageStore,
    private val dialFrequency: () -> Long = { GeneralVariables.band },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = { GeneralVariables.fileLog(it) },
    private val ioExecutor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DigitalRxSave").apply { isDaemon = true }
    },
) {

    /**
     * Register on [listener]'s per-frame digital callback. Direct callback
     * rather than the LiveData holder: postValue coalesces under a busy main
     * looper, which could silently drop a completed frame between
     * dispatches, and this controller must save every frame exactly once.
     * The callback arrives on the decode thread; the save runs on
     * [ioExecutor].
     */
    fun attach(listener: SstvSignalListener) {
        listener.onDigitalImage = { result -> ioExecutor.execute { save(result) } }
    }

    internal fun save(result: DigitalRxImage) {
        try {
            val bitmap = decodePayload(result) ?: run {
                log(
                    "SSTV RX digital: payload not renderable" +
                        " (format=${result.format}, ${result.payload.size}B) — not saved",
                )
                return
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val saved = store.save(
                pixels = pixels,
                width = bitmap.width,
                height = bitmap.height,
                modeShortCode = result.mode.shortCode,
                modeDisplayName = result.mode.displayName,
                utcMillis = clock(),
                freqHz = dialFrequency(),
                direction = ImageDirection.RX,
                complete = true,
                quality = 1f,
            )
            log("SSTV RX digital: saved — ${saved.fileName} (${bitmap.width}x${bitmap.height})")
        } catch (t: Throwable) {
            log("SSTV RX digital: save FAILED: $t")
        }
    }

    internal fun decodePayload(result: DigitalRxImage): Bitmap? =
        when (result.format) {
            DigitalSstvCodec.Format.JPEG,
            DigitalSstvCodec.Format.PNG,
            DigitalSstvCodec.Format.WEBP,
            ->
                BitmapFactory.decodeByteArray(result.payload, 0, result.payload.size)

            else -> null // RAW/JPEG2000 have no renderer here yet.
        }
}
