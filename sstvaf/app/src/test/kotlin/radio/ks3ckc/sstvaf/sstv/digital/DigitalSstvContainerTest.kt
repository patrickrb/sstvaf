package radio.ks3ckc.sstvaf.sstv.digital

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/** Container framing, FEC-protected header/blocks, reassembly, and ARQ bookkeeping. */
class DigitalSstvContainerTest {

    private fun payload(n: Int, seed: Int = 0): ByteArray {
        val rnd = Random(seed.toLong())
        return ByteArray(n) { rnd.nextInt(256).toByte() }
    }

    @Test
    fun `header round trips through FEC`() {
        val p = payload(300)
        val meta = DigitalSstvContainer.metadataFor(2, 320, 256, p)
        val cw = DigitalSstvContainer.encodeHeader(meta)
        assertThat(cw.size).isEqualTo(DigitalSstvContainer.HEADER_CW)
        val decoded = DigitalSstvContainer.decodeHeader(cw)!!
        assertThat(decoded.format).isEqualTo(2)
        assertThat(decoded.width).isEqualTo(320)
        assertThat(decoded.height).isEqualTo(256)
        assertThat(decoded.payloadLen).isEqualTo(300)
        assertThat(decoded.payloadCrc32).isEqualTo(Crc.crc32(p))
        assertThat(decoded.blockCount).isEqualTo(3) // ceil(300/128)
    }

    @Test
    fun `header FEC corrects byte errors`() {
        val meta = DigitalSstvContainer.metadataFor(1, 160, 120, payload(50))
        val cw = DigitalSstvContainer.encodeHeader(meta)
        // Flip up to t = HEADER_RS/2 bytes.
        for (i in 0 until DigitalSstvContainer.HEADER_RS / 2) cw[i * 2] = (cw[i * 2].toInt() xor 0xAA).toByte()
        assertThat(DigitalSstvContainer.decodeHeader(cw)).isNotNull()
    }

    @Test
    fun `block round trips and rejects corruption beyond FEC`() {
        val p = payload(400, seed = 5)
        val cw = DigitalSstvContainer.encodeBlock(p, 1)
        assertThat(cw.size).isEqualTo(DigitalSstvContainer.BLOCK_CW)
        val (idx, data) = DigitalSstvContainer.decodeBlock(cw, 0)!!
        assertThat(idx).isEqualTo(1)
        assertThat(data.toList()).isEqualTo(
            p.copyOfRange(128, 256).toList(),
        )
        // Overwhelm the FEC: half the codeword corrupted -> rejected.
        for (i in cw.indices.filter { it % 2 == 0 }) cw[i] = (cw[i].toInt() xor 0x5A).toByte()
        assertThat(DigitalSstvContainer.decodeBlock(cw, 0)).isNull()
    }

    @Test
    fun `last short block carries only the remaining bytes`() {
        val p = payload(130) // 1 full block + 2 bytes
        val meta = DigitalSstvContainer.metadataFor(0, 10, 10, p)
        assertThat(meta.blockCount).isEqualTo(2)
        val last = DigitalSstvContainer.decodeBlock(DigitalSstvContainer.encodeBlock(p, 1), 0)!!
        assertThat(last.second.size).isEqualTo(2)
        assertThat(last.second.toList()).isEqualTo(p.copyOfRange(128, 130).toList())
    }

    @Test
    fun `decodeFrame recovers all blocks and assemble validates CRC`() {
        val p = payload(500, seed = 9)
        val meta = DigitalSstvContainer.metadataFor(1, 320, 256, p)
        val frame = DigitalSstvContainer.encodeFullFrame(meta, p)
        val decoded = DigitalSstvContainer.decodeFrame(frame)!!
        assertThat(decoded.blocks.keys).containsExactlyElementsIn(0 until meta.blockCount)
        val assembled = DigitalSstvContainer.assemble(decoded.meta, decoded.blocks)
        assertThat(assembled).isEqualTo(p)
    }

    @Test
    fun `assemble fails on missing block or CRC mismatch`() {
        val p = payload(300)
        val meta = DigitalSstvContainer.metadataFor(1, 1, 1, p)
        val decoded = DigitalSstvContainer.decodeFrame(DigitalSstvContainer.encodeFullFrame(meta, p))!!
        val partial = decoded.blocks.filterKeys { it != 1 }
        assertThat(DigitalSstvContainer.assemble(decoded.meta, partial)).isNull()

        // Tamper a block so the reassembled CRC-32 no longer matches.
        val tampered = decoded.blocks.toMutableMap()
        tampered[0] = tampered[0]!!.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThat(DigitalSstvContainer.assemble(decoded.meta, tampered)).isNull()
    }

    @Test
    fun `missingBlocks lists the gaps`() {
        assertThat(DigitalSstvContainer.missingBlocks(5, setOf(0, 2, 4))).isEqualTo(listOf(1, 3))
        assertThat(DigitalSstvContainer.missingBlocks(3, setOf(0, 1, 2))).isEmpty()
    }

    @Test
    fun `retransmit request round trips`() {
        val missing = listOf(7, 2, 2, 40, 0)
        val bytes = DigitalSstvContainer.encodeRetransmitRequest(missing)
        assertThat(DigitalSstvContainer.decodeRetransmitRequest(bytes)).isEqualTo(listOf(0, 2, 7, 40))
    }

    @Test
    fun `decodeHeader rejects garbage`() {
        assertThat(DigitalSstvContainer.decodeHeader(ByteArray(DigitalSstvContainer.HEADER_CW))).isNull()
    }

    @Test
    fun `decodeHeader rejects payloadLen beyond the block span`() {
        // A corrupt-but-FEC-valid header must not yield a payloadLen that
        // exceeds the declared block span — that would over-allocate or wrap
        // in assemble()'s ByteArray(payloadLen).
        val span = 3 * DigitalSstvContainer.BLOCK_DATA_LEN
        val bad = DigitalSstvContainer.Meta(
            format = 1, width = 320, height = 256,
            payloadLen = span + 1, // one byte past what 3 blocks can hold
            payloadCrc32 = 0L, blockCount = 3, blocksInFrame = 3,
        )
        assertThat(DigitalSstvContainer.decodeHeader(DigitalSstvContainer.encodeHeader(bad))).isNull()
        // Exactly the block span is still accepted.
        val ok = bad.copy(payloadLen = span)
        assertThat(DigitalSstvContainer.decodeHeader(DigitalSstvContainer.encodeHeader(ok))).isNotNull()
    }

    @Test
    fun `subset frame carries only requested blocks`() {
        val p = payload(600, seed = 3)
        val meta = DigitalSstvContainer.metadataFor(1, 320, 256, p)
        val frame = DigitalSstvContainer.encodeFrame(meta, p, listOf(1, 3))
        val decoded = DigitalSstvContainer.decodeFrame(frame)!!
        assertThat(decoded.meta.blocksInFrame).isEqualTo(2)
        assertThat(decoded.meta.blockCount).isEqualTo(meta.blockCount)
        assertThat(decoded.blocks.keys).containsExactly(1, 3)
    }
}
