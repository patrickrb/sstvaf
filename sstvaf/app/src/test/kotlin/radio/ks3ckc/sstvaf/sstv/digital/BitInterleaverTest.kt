package radio.ks3ckc.sstvaf.sstv.digital

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/** Block interleaver: exact invertibility and burst-error spreading. */
class BitInterleaverTest {

    @Test
    fun `roundtrips arbitrary lengths and depths`() {
        val rnd = Random(7)
        for (rows in listOf(1, 4, 13, 32)) {
            val il = BitInterleaver(rows)
            for (len in listOf(0, 1, 7, 100, 257)) {
                val bits = IntArray(len) { rnd.nextInt(2) }
                val back = il.deinterleave(il.interleave(bits), len)
                assertThat(back).isEqualTo(bits)
            }
        }
    }

    @Test
    fun `spreads a contiguous burst across the codeword`() {
        // A burst in the interleaved (channel) domain must map back to widely
        // separated original positions — that is the whole point of interleaving.
        val rows = 16
        val il = BitInterleaver(rows)
        val bits = IntArray(rows * 10) // 160 bits -> 10 columns
        val interleaved = il.interleave(bits)
        // Corrupt a contiguous run of 10 interleaved bits.
        val marked = interleaved.copyOf()
        for (i in 20 until 30) marked[i] = 1
        val deint = il.deinterleave(marked, bits.size)
        val hitPositions = deint.indices.filter { deint[it] == 1 }
        // No two original error positions are adjacent.
        val gaps = hitPositions.zipWithNext { a, b -> b - a }
        assertThat(gaps.all { it > 1 }).isTrue()
    }

    @Test
    fun `rejects non-positive depth`() {
        try {
            BitInterleaver(0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // depth must be >= 1
        }
    }
}
