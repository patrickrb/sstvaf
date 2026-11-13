package radio.ks3ckc.sstvaf.gallery

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
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
 * - [saveState] reports what actually happened. The UI cannot use
 *   [SstvRxState.Complete] as a persistence signal: it fires when the *decoder*
 *   stops, which is before this controller has written anything, may be
 *   accompanied by `frameAvailable = false` (nothing to write at all), and says
 *   nothing about a save that threw. A screen that refreshes its gallery list
 *   or shows a "Saved ✓" badge off the decoder state races the insert and lies
 *   when it fails.
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

    private val mutableSaveState = MutableLiveData(RxSaveOutcome.NONE)

    /**
     * The outcome of the most recent auto-save. Published on the main thread
     * (the save runs on a worker), so Compose can observe it directly.
     *
     * [RxSaveOutcome.SAVED] is the only value that means an image reached the
     * Gallery — it is posted *after* [FrameSaver.save] returns, so a screen that
     * reloads the store on it cannot read ahead of the insert.
     */
    val saveState: LiveData<RxSaveOutcome> get() = mutableSaveState

    /**
     * Where an outcome goes. Defaults to [saveState]; tests swap it so they can
     * assert the outcome sequence without dragging in LiveData's main-thread
     * executor, which is the only Android dependency this class would otherwise
     * have.
     */
    internal var publishOutcome: (RxSaveOutcome) -> Unit = { mutableSaveState.postValue(it) }

    /** Observe forever — the controller lives as long as the ViewModel. Main thread only. */
    fun attach(rxState: LiveData<SstvRxState>) {
        rxState.observeForever { state -> onState(state) }
    }

    /** One state transition. Internal so tests can drive it synchronously. */
    internal fun onState(state: SstvRxState) {
        if (state !is SstvRxState.Complete) return
        // A completion with nothing snapshotted is a decode that produced no
        // image. Reporting FAILED rather than staying silent is what stops the
        // screen badging it as saved.
        if (!state.frameAvailable) {
            publishOutcome(RxSaveOutcome.FAILED)
            return
        }
        val frame = LastDecodedImage.frame
        if (frame == null || !frame.complete) {
            publishOutcome(RxSaveOutcome.FAILED)
            return
        }
        // A re-delivered terminal state is the same frame we already wrote, so
        // the outcome already published still stands — do not re-announce it.
        if (frame === lastSavedFrame) return
        lastSavedFrame = frame
        publishOutcome(RxSaveOutcome.PENDING)
        dispatch {
            try {
                saver.save(frame)
                log(
                    "SSTV RX: image auto-saved — mode=${frame.mode.displayName}" +
                        " ${frame.width}x${frame.height} quality=${frame.quality}" +
                        " freq=${frame.dialFrequencyHz}Hz utc=${frame.utcMillis}",
                )
                publishOutcome(RxSaveOutcome.SAVED)
            } catch (t: Throwable) {
                log("SSTV RX: image auto-save FAILED: $t")
                publishOutcome(RxSaveOutcome.FAILED)
            }
        }
    }
}

/**
 * What [RxAutoSaveController] has reported about the latest completed decode.
 *
 * Mirrors `RxSaveState` in the RX screen's logic, which is the UI-side vocabulary;
 * kept separate so the gallery package does not depend on the screen.
 */
enum class RxSaveOutcome {
    /** Nothing has completed yet this session. */
    NONE,

    /** A save is in flight. */
    PENDING,

    /** The store confirmed the write. */
    SAVED,

    /** Nothing was written: no frame to snapshot, or the save threw. */
    FAILED,
}
