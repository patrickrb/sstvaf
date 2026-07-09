package radio.ks3ckc.sstvaf.gallery

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import radio.ks3ckc.sstvaf.sstv.LastDecodedImage
import radio.ks3ckc.sstvaf.sstv.SstvMode
import radio.ks3ckc.sstvaf.sstv.SstvRxState

/**
 * [RxAutoSaveController]: exactly one save per completed decode, nothing for
 * aborted/partial frames, and re-delivered terminal states never double-save.
 * Pure JVM — the saver is faked and the dispatcher runs inline.
 */
class RxAutoSaveControllerTest {

    private val savedFrames = mutableListOf<LastDecodedImage.Frame>()
    private val logLines = mutableListOf<String>()

    private fun controller(
        saver: FrameSaver = FrameSaver { savedFrames.add(it) },
    ) = RxAutoSaveController(
        saver = saver,
        log = { logLines.add(it) },
        dispatch = { it.run() }, // synchronous for tests
    )

    private fun frame(utcMillis: Long = 1_000L, complete: Boolean = true) =
        LastDecodedImage.Frame(
            pixels = IntArray(SstvMode.SCOTTIE_1.width * SstvMode.SCOTTIE_1.height),
            width = SstvMode.SCOTTIE_1.width,
            height = SstvMode.SCOTTIE_1.height,
            mode = SstvMode.SCOTTIE_1,
            rowsDecoded = SstvMode.SCOTTIE_1.height,
            quality = 0.9f,
            slantPpm = 2f,
            utcMillis = utcMillis,
            dialFrequencyHz = 14_230_000L,
            complete = complete,
        )

    private fun completeState() = SstvRxState.Complete(SstvMode.SCOTTIE_1, 0.9f, true)

    @Before
    fun setUp() {
        LastDecodedImage.frame = null
    }

    @After
    fun tearDown() {
        LastDecodedImage.frame = null
    }

    @Test
    fun completeTransition_savesTheSnapshotOnce() {
        val c = controller()
        val f = frame()
        LastDecodedImage.frame = f

        c.onState(completeState())

        assertThat(savedFrames).containsExactly(f)
        assertThat(logLines.single()).contains("auto-saved")
    }

    @Test
    fun redeliveredCompleteState_doesNotDoubleSave() {
        val c = controller()
        LastDecodedImage.frame = frame()

        c.onState(completeState())
        c.onState(completeState()) // LiveData re-delivery (e.g. re-observe)

        assertThat(savedFrames).hasSize(1)
    }

    @Test
    fun secondDecode_withNewSnapshot_savesAgain() {
        val c = controller()
        LastDecodedImage.frame = frame(utcMillis = 1_000L)
        c.onState(completeState())

        LastDecodedImage.frame = frame(utcMillis = 2_000L)
        c.onState(completeState())

        assertThat(savedFrames).hasSize(2)
    }

    @Test
    fun distinctFramesSharingAUtcMillis_bothSave() {
        // Regression: the guard must key on snapshot identity, not timestamp —
        // two different frames can share a coarse clock value.
        val c = controller()
        LastDecodedImage.frame = frame(utcMillis = 5_000L)
        c.onState(completeState())

        LastDecodedImage.frame = frame(utcMillis = 5_000L) // new object, same clock
        c.onState(completeState())

        assertThat(savedFrames).hasSize(2)
    }

    @Test
    fun abortedTransition_savesNothing() {
        val c = controller()
        // Even with a (partial) snapshot present, Aborted must not save.
        LastDecodedImage.frame = frame(complete = false)

        c.onState(SstvRxState.Aborted(42, SstvMode.SCOTTIE_1))

        assertThat(savedFrames).isEmpty()
    }

    @Test
    fun nonTerminalStates_saveNothing() {
        val c = controller()
        LastDecodedImage.frame = frame()

        c.onState(SstvRxState.Idle)
        c.onState(SstvRxState.Leader)
        c.onState(SstvRxState.Decoding(SstvMode.SCOTTIE_1, 10, 256, 0.5f, 0f))

        assertThat(savedFrames).isEmpty()
    }

    @Test
    fun completeWithoutSnapshot_savesNothing() {
        val c = controller()
        LastDecodedImage.frame = null

        c.onState(completeState())

        assertThat(savedFrames).isEmpty()
    }

    @Test
    fun completeStateWithFrameUnavailable_savesNothing() {
        val c = controller()
        LastDecodedImage.frame = frame()

        c.onState(SstvRxState.Complete(SstvMode.SCOTTIE_1, 0.9f, frameAvailable = false))

        assertThat(savedFrames).isEmpty()
    }

    @Test
    fun stalePartialSnapshot_isNeverSavedByAComplete() {
        val c = controller()
        // Defensive: a Complete state paired with an aborted-partial snapshot
        // (shouldn't happen — the engine snapshots before publishing) is skipped.
        LastDecodedImage.frame = frame(complete = false)

        c.onState(completeState())

        assertThat(savedFrames).isEmpty()
    }

    @Test
    fun saverFailure_isLoggedAndDoesNotThrow() {
        val c = controller(saver = FrameSaver { throw RuntimeException("disk full") })
        LastDecodedImage.frame = frame()

        c.onState(completeState()) // must not propagate

        assertThat(savedFrames).isEmpty()
        assertThat(logLines.single()).contains("FAILED")
    }
}
