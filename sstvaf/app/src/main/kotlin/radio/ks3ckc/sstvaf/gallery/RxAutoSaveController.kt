package radio.ks3ckc.sstvaf.gallery

import androidx.lifecycle.LiveData
import com.k1af.ft8af.GeneralVariables
import radio.ks3ckc.sstvaf.sstv.LastDecodedImage
import radio.ks3ckc.sstvaf.sstv.SstvRxState

/** Persists one finished RX frame (see [RxAutoSaveController]). */
fun interface FrameSaver {
    fun save(frame: LastDecodedImage.Frame)
}

/**
 * Auto-saves completed SSTV decodes: watches [SstvRxState] transitions and, on
 * [SstvRxState.Complete], pulls the [LastDecodedImage] snapshot and hands it to
 * the [FrameSaver] (in the app: [ReceivedImageStore.save] with direction RX).
 *
 * - Partial ([SstvRxState.Aborted]) frames are NOT saved.
 * - The same snapshot is never saved twice (identity guard) — LiveData can
 *   re-deliver the terminal Complete state (e.g. on re-observe), but it
 *   re-delivers the same Frame object, so identity is the right key; a
 *   timestamp guard could wrongly skip a distinct frame sharing a coarse
 *   clock value.
 * - The save itself (PNG encode + DB insert + MediaStore) runs off the calling
 *   thread via [dispatch]; the double-save guard is taken synchronously first.
 */
class RxAutoSaveController(
    private val saver: FrameSaver,
    private val log: (String) -> Unit = { GeneralVariables.fileLog(it) },
    private val dispatch: (Runnable) -> Unit = { Thread(it, "SstvImageSave").start() },
) {

    /** App wiring: save into the [ReceivedImageStore] as a received image. */
    constructor(store: ReceivedImageStore) : this(
        FrameSaver { frame ->
            store.save(
                pixels = frame.pixels,
                width = frame.width,
                height = frame.height,
                mode = frame.mode,
                utcMillis = frame.utcMillis,
                freqHz = frame.dialFrequencyHz,
                direction = ImageDirection.RX,
                complete = frame.complete,
                quality = frame.quality,
            )
        },
    )

    private var lastSavedFrame: LastDecodedImage.Frame? = null

    /** Observe forever — the controller lives as long as the ViewModel. Main thread only. */
    fun attach(rxState: LiveData<SstvRxState>) {
        rxState.observeForever { state -> onState(state) }
    }

    /** One state transition. Internal so tests can drive it synchronously. */
    internal fun onState(state: SstvRxState) {
        if (state !is SstvRxState.Complete || !state.frameAvailable) return
        val frame = LastDecodedImage.frame ?: return
        if (!frame.complete) return
        if (frame === lastSavedFrame) return
        lastSavedFrame = frame
        dispatch {
            try {
                saver.save(frame)
                log(
                    "SSTV RX: image auto-saved — mode=${frame.mode.displayName}" +
                        " ${frame.width}x${frame.height} quality=${frame.quality}" +
                        " freq=${frame.dialFrequencyHz}Hz utc=${frame.utcMillis}",
                )
            } catch (t: Throwable) {
                log("SSTV RX: image auto-save FAILED: $t")
            }
        }
    }
}
