package radio.ks3ckc.sstvaf.sstv.digital

/**
 * Systematic Reed-Solomon code over [Gf256], the forward-error-correction
 * layer of the digital-SSTV modem.
 *
 * A codeword is `k` data bytes followed by [nsym] parity bytes; the decoder
 * corrects up to `nsym / 2` byte errors anywhere in the codeword. Errors
 * beyond that budget are (with overwhelming probability) rejected rather than
 * silently miscorrected — [decode] returns `null`, which the container layer
 * turns into a "block missing, please retransmit" report.
 *
 * The generator roots are `g^0 .. g^(nsym-1)` (generator element `2`), the
 * DRM/DVB convention. Polynomials are big-endian: index 0 is the highest
 * degree term.
 */
internal class ReedSolomon(val nsym: Int) {
    init {
        require(nsym in 2..254 && nsym % 2 == 0) { "nsym must be even in [2,254]: $nsym" }
    }

    /** Max correctable byte errors. */
    val correctable: Int get() = nsym / 2

    private val generator: IntArray = buildGenerator(nsym)

    /** Systematic encode: returns `data` followed by [nsym] parity bytes. */
    fun encode(data: IntArray): IntArray {
        require(data.size + nsym <= 255) { "codeword too long: ${data.size}+$nsym > 255" }
        val out = IntArray(data.size + nsym)
        System.arraycopy(data, 0, out, 0, data.size)
        // Polynomial long division remainder into the parity tail.
        for (i in data.indices) {
            val coef = out[i]
            if (coef != 0) {
                for (j in 1 until generator.size) {
                    out[i + j] = out[i + j] xor Gf256.mul(generator[j], coef)
                }
            }
        }
        // The data prefix is overwritten by the division above; restore it.
        System.arraycopy(data, 0, out, 0, data.size)
        return out
    }

    /**
     * Correct a received codeword in place-safe fashion.
     *
     * @return the corrected `k` data bytes (parity stripped), or `null` when
     *   the codeword carries more errors than [correctable].
     */
    fun decode(codeword: IntArray): IntArray? {
        // GF(256) log/exp arithmetic and the Chien search assume the RS(255, k)
        // block length; a longer codeword would wrap mod 255 and miscorrect, so
        // reject it rather than returning a plausible-but-wrong result.
        if (codeword.size > 255) return null
        val k = codeword.size - nsym
        if (k <= 0) return null
        val synd = syndromes(codeword)
        if (synd.all { it == 0 }) return codeword.copyOfRange(0, k)

        val errLoc = errorLocator(synd)
        if (errLoc.size - 1 > correctable) return null
        val positions = findErrors(errLoc, codeword.size) ?: return null

        val fixed = correctErrata(codeword, synd, positions) ?: return null
        if (syndromes(fixed).any { it != 0 }) return null
        return fixed.copyOfRange(0, k)
    }

    // --- internals (ported from the verified reference algorithm) ---

    private fun syndromes(cw: IntArray): IntArray = IntArray(nsym) { i -> polyEval(cw, Gf256.expGen(i)) }

    private fun errorLocator(synd: IntArray): IntArray {
        var errLoc = intArrayOf(1)
        var oldLoc = intArrayOf(1)
        for (i in 0 until nsym) {
            var delta = synd[i]
            for (j in 1 until errLoc.size) {
                delta = delta xor Gf256.mul(errLoc[errLoc.size - 1 - j], synd[i - j])
            }
            oldLoc = oldLoc + 0
            if (delta != 0) {
                if (oldLoc.size > errLoc.size) {
                    val newLoc = polyScale(oldLoc, delta)
                    oldLoc = polyScale(errLoc, Gf256.inv(delta))
                    errLoc = newLoc
                }
                errLoc = polyAdd(errLoc, polyScale(oldLoc, delta))
            }
        }
        var start = 0
        while (start < errLoc.size && errLoc[start] == 0) start++
        return errLoc.copyOfRange(start, errLoc.size)
    }

    private fun findErrors(errLoc: IntArray, msgLen: Int): IntArray? {
        val errs = errLoc.size - 1
        val positions = ArrayList<Int>()
        for (i in 0 until msgLen) {
            if (polyEval(errLoc, Gf256.pow(Gf256.GENERATOR, 255 - i)) == 0) {
                positions.add(msgLen - 1 - i)
            }
        }
        if (positions.size != errs) return null
        return positions.toIntArray()
    }

    private fun correctErrata(cw: IntArray, synd: IntArray, positions: IntArray): IntArray? {
        val coefPos = IntArray(positions.size) { cw.size - 1 - positions[it] }
        // Error locator polynomial from the found positions.
        var q = intArrayOf(1)
        for (i in coefPos) q = polyMul(q, intArrayOf(Gf256.pow(Gf256.GENERATOR, i), 1))
        // Error evaluator Omega = S(x) * q(x), keep the low `positions.size` terms.
        val syndRev = synd.reversedArray()
        var eEval = polyMul(syndRev, q)
        eEval = eEval.copyOfRange(eEval.size - positions.size, eEval.size)

        val out = cw.copyOf()
        val qLoHi = q.reversedArray() // low-degree first for the formal derivative
        for (k in coefPos.indices) {
            val xi = Gf256.pow(Gf256.GENERATOR, coefPos[k])
            val xiInv = Gf256.inv(xi)
            var der = 0
            var d = 1
            while (d < qLoHi.size) {
                if (d % 2 == 1) der = der xor Gf256.mul(qLoHi[d], Gf256.pow(xiInv, d - 1))
                d++
            }
            if (der == 0) return null
            val y = Gf256.mul(polyEval(eEval, xiInv), xi)
            out[positions[k]] = out[positions[k]] xor Gf256.div(y, der)
        }
        return out
    }

    companion object {
        private fun buildGenerator(nsym: Int): IntArray {
            var g = intArrayOf(1)
            for (i in 0 until nsym) g = polyMul(g, intArrayOf(1, Gf256.expGen(i)))
            return g
        }

        private fun polyScale(p: IntArray, s: Int): IntArray = IntArray(p.size) { Gf256.mul(p[it], s) }

        private fun polyAdd(p: IntArray, q: IntArray): IntArray {
            val r = IntArray(maxOf(p.size, q.size))
            for (i in p.indices) r[i + r.size - p.size] = p[i]
            for (i in q.indices) r[i + r.size - q.size] = r[i + r.size - q.size] xor q[i]
            return r
        }

        private fun polyMul(p: IntArray, q: IntArray): IntArray {
            val r = IntArray(p.size + q.size - 1)
            for (i in p.indices) {
                for (j in q.indices) r[i + j] = r[i + j] xor Gf256.mul(p[i], q[j])
            }
            return r
        }

        private fun polyEval(p: IntArray, x: Int): Int {
            var y = p[0]
            for (i in 1 until p.size) y = Gf256.mul(y, x) xor p[i]
            return y
        }
    }
}
