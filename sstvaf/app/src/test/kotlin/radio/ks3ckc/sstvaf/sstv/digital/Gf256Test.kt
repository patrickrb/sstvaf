package radio.ks3ckc.sstvaf.sstv.digital

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** GF(2^8) field axioms used by [ReedSolomon]. */
class Gf256Test {

    @Test
    fun `mul by zero and one`() {
        for (a in 0..255) {
            assertThat(Gf256.mul(a, 0)).isEqualTo(0)
            assertThat(Gf256.mul(0, a)).isEqualTo(0)
            assertThat(Gf256.mul(a, 1)).isEqualTo(a)
        }
    }

    @Test
    fun `mul is commutative`() {
        for (a in 0..255) {
            for (b in 0..255) assertThat(Gf256.mul(a, b)).isEqualTo(Gf256.mul(b, a))
        }
    }

    @Test
    fun `inverse and division`() {
        for (a in 1..255) {
            val inv = Gf256.inv(a)
            assertThat(Gf256.mul(a, inv)).isEqualTo(1)
            assertThat(Gf256.div(1, a)).isEqualTo(inv)
            assertThat(Gf256.div(a, a)).isEqualTo(1)
        }
    }

    @Test
    fun `division inverts multiplication`() {
        for (a in 0..255) {
            for (b in 1..255) {
                assertThat(Gf256.div(Gf256.mul(a, b), b)).isEqualTo(a)
            }
        }
    }

    @Test
    fun `pow matches repeated multiplication`() {
        for (a in 1..255) {
            var acc = 1
            for (n in 0..10) {
                assertThat(Gf256.pow(a, n)).isEqualTo(acc)
                acc = Gf256.mul(acc, a)
            }
        }
    }

    @Test
    fun `generator powers cycle with period 255`() {
        assertThat(Gf256.expGen(0)).isEqualTo(1)
        assertThat(Gf256.expGen(255)).isEqualTo(1)
        // The generator is primitive: its powers hit every non-zero element.
        val seen = (0 until 255).map { Gf256.expGen(it) }.toSet()
        assertThat(seen).hasSize(255)
        assertThat(seen).doesNotContain(0)
    }
}
