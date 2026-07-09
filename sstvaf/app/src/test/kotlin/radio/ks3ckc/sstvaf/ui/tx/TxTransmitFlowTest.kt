package radio.ks3ckc.sstvaf.ui.tx

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.sstvaf.sstv.SstvMode

/**
 * [performTransmit] orchestration: the composite is persisted to the gallery
 * ONLY when the transmitter accepts the start — a rejected start must save
 * nothing, so the gallery never shows an image that never went to RF. Pure
 * JVM with recording fakes.
 */
class TxTransmitFlowTest {

    private class RecordingSaver : TxImageSaver {
        data class Call(
            val pixels: IntArray,
            val width: Int,
            val height: Int,
            val mode: SstvMode,
            val utcMillis: Long,
            val freqHz: Long,
        )

        val calls = mutableListOf<Call>()

        override fun save(
            pixels: IntArray,
            width: Int,
            height: Int,
            mode: SstvMode,
            utcMillis: Long,
            freqHz: Long,
        ) {
            calls.add(Call(pixels, width, height, mode, utcMillis, freqHz))
        }
    }

    private val pixels = IntArray(320 * 256) { it }
    private val log = mutableListOf<String>()

    private fun run(accepts: Boolean, saver: RecordingSaver): Boolean {
        val starterCalls = mutableListOf<String>()
        val started = performTransmit(
            pixels = pixels,
            width = 320,
            height = 256,
            mode = SstvMode.SCOTTIE_1,
            freqHz = 14_230_000L,
            utcMillis = 1_780_000_000_000L,
            starter = { p, w, h, m ->
                starterCalls.add("${p.size}:${w}x$h:$m")
                accepts
            },
            saver = saver,
            log = { log.add(it) },
        )
        assertThat(starterCalls).containsExactly("${320 * 256}:320x256:SCOTTIE_1")
        return started
    }

    @Test
    fun `accepted start saves the same composite once and logs started`() {
        val saver = RecordingSaver()

        val started = run(accepts = true, saver = saver)

        assertThat(started).isTrue()
        assertThat(saver.calls).hasSize(1)
        val call = saver.calls.single()
        assertThat(call.pixels).isSameInstanceAs(pixels)
        assertThat(call.width).isEqualTo(320)
        assertThat(call.height).isEqualTo(256)
        assertThat(call.mode).isEqualTo(SstvMode.SCOTTIE_1)
        assertThat(call.utcMillis).isEqualTo(1_780_000_000_000L)
        assertThat(call.freqHz).isEqualTo(14_230_000L)
        assertThat(log).hasSize(1)
        assertThat(log.single()).contains("started")
        assertThat(log.single()).contains("Scottie 1")
        assertThat(log.single()).contains("freqHz=14230000")
    }

    @Test
    fun `save failure after key-up is logged, not thrown — the transmission continues`() {
        // ReceivedImageStore.save throws IOException on encode/insert failure;
        // by then the rig is already transmitting, so performTransmit must
        // swallow it and still report a started transmission.
        val started = performTransmit(
            pixels = pixels,
            width = 320,
            height = 256,
            mode = SstvMode.SCOTTIE_1,
            freqHz = 14_230_000L,
            utcMillis = 1_780_000_000_000L,
            starter = { _, _, _, _ -> true },
            saver = { _, _, _, _, _, _ -> throw java.io.IOException("disk full") },
            log = { log.add(it) },
        )

        assertThat(started).isTrue()
        assertThat(log).hasSize(2)
        assertThat(log[0]).contains("save failed")
        assertThat(log[0]).contains("disk full")
        assertThat(log[1]).contains("started")
    }

    @Test
    fun `non-IOException from the saver propagates as a programmer error`() {
        // Only the store's documented IOException is swallowed; anything else
        // must surface loudly rather than vanish into the transmit log.
        var thrown: IllegalStateException? = null
        try {
            performTransmit(
                pixels = pixels,
                width = 320,
                height = 256,
                mode = SstvMode.SCOTTIE_1,
                freqHz = 1L,
                utcMillis = 2L,
                starter = { _, _, _, _ -> true },
                saver = { _, _, _, _, _, _ -> throw IllegalStateException("bug") },
                log = { log.add(it) },
            )
        } catch (e: IllegalStateException) {
            thrown = e
        }
        assertThat(thrown).isNotNull()
    }

    @Test
    fun `rejected start never saves and logs the rejection`() {
        val saver = RecordingSaver()

        val started = run(accepts = false, saver = saver)

        assertThat(started).isFalse()
        assertThat(saver.calls).isEmpty()
        assertThat(log).hasSize(1)
        assertThat(log.single()).contains("rejected")
        assertThat(log.single()).doesNotContain("started")
    }
}
