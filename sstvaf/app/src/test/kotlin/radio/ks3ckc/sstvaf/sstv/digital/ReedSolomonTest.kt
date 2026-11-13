package radio.ks3ckc.sstvaf.sstv.digital

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/** Reed-Solomon FEC: systematic encode, within-budget correction, over-budget rejection. */
class ReedSolomonTest {

    @Test
    fun `encode is systematic and clean codeword decodes to itself`() {
        val rs = ReedSolomon(16)
        val data = IntArray(50) { (it * 7 + 3) and 0xFF }
        val cw = rs.encode(data)
        assertThat(cw.size).isEqualTo(66)
        // The data prefix is preserved verbatim.
        assertThat(cw.copyOfRange(0, 50)).isEqualTo(data)
        // A clean codeword decodes back to the data.
        assertThat(rs.decode(cw)).isEqualTo(data)
    }

    @Test
    fun `corrects up to t errors`() {
        val rnd = Random(1234)
        var trials = 0
        repeat(400) {
            val nsym = listOf(8, 16, 32).random(rnd)
            val rs = ReedSolomon(nsym)
            val k = rnd.nextInt(20, 200)
            val data = IntArray(k) { rnd.nextInt(256) }
            val cw = rs.encode(data)
            val nErr = rnd.nextInt(0, rs.correctable + 1)
            val positions = (cw.indices).shuffled(rnd).take(nErr)
            for (p in positions) cw[p] = cw[p] xor rnd.nextInt(1, 256)
            assertThat(rs.decode(cw)).isEqualTo(data)
            trials++
        }
        assertThat(trials).isEqualTo(400)
    }

    @Test
    fun `rejects rather than silently miscorrecting beyond t`() {
        val rnd = Random(99)
        val rs = ReedSolomon(16)
        var silentMiscorrections = 0
        repeat(300) {
            val data = IntArray(100) { rnd.nextInt(256) }
            val cw = rs.encode(data)
            val nErr = rnd.nextInt(rs.correctable + 1, cw.size + 1)
            for (p in cw.indices.shuffled(rnd).take(nErr)) cw[p] = cw[p] xor rnd.nextInt(1, 256)
            val decoded = rs.decode(cw)
            if (decoded != null && !decoded.contentEquals(data)) silentMiscorrections++
        }
        // The syndrome re-check makes silent miscorrection vanishingly unlikely.
        assertThat(silentMiscorrections).isEqualTo(0)
    }

    @Test
    fun `rejects oversize codeword rather than wrapping mod 255`() {
        val rs = ReedSolomon(16)
        // 256 symbols exceeds the RS(255, k) block length; the GF(256) Chien
        // search would wrap and potentially miscorrect, so decode must reject.
        assertThat(rs.decode(IntArray(256))).isNull()
        assertThat(rs.decode(IntArray(300) { it and 0xFF })).isNull()
        // A maximal 255-symbol codeword is still accepted.
        val data = IntArray(255 - 16) { (it * 3) and 0xFF }
        assertThat(rs.decode(rs.encode(data))).isEqualTo(data)
    }

    @Test
    fun `rejects invalid nsym`() {
        try {
            ReedSolomon(15)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // odd nsym is invalid
        }
    }
}
