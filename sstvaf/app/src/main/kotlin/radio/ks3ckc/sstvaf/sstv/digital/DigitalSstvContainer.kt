package radio.ks3ckc.sstvaf.sstv.digital

/**
 * The digital-SSTV image container and its block-ARQ framing — the layer
 * EasyPal/DigTRX call the "file transfer": a compressed image payload is
 * described by a small metadata header and split into fixed-size blocks, each
 * independently Reed-Solomon coded and CRC-checked so the receiver can tell
 * exactly which blocks arrived intact and request only the rest.
 *
 * On the wire a *frame* is one header codeword followed by the codewords of
 * the blocks carried in that frame. A first transmission carries every block;
 * a retransmission carries only the blocks a receiver's
 * [encodeRetransmitRequest] asked for. Blocks self-identify by index, so a
 * receiver merges blocks across frames ([DigitalSstvReceiver]) until the whole
 * payload is present and its CRC-32 checks out.
 *
 * All multi-byte fields are big-endian. The layout constants are fixed (not
 * negotiated) so two independent builds interoperate.
 */
internal object DigitalSstvContainer {

    private const val MAGIC0 = 'D'.code
    private const val MAGIC1 = 'S'.code
    const val VERSION = 1

    /** Header plaintext byte count (before RS parity). */
    const val HEADER_DATA_LEN = 22
    const val HEADER_RS = 16
    const val HEADER_CW = HEADER_DATA_LEN + HEADER_RS

    /** Payload bytes carried by one block. */
    const val BLOCK_DATA_LEN = 128
    private const val BLOCK_OVERHEAD = 6 // index(2) + dataLen(2) + crc16(2)
    const val BLOCK_PLAINTEXT = BLOCK_DATA_LEN + BLOCK_OVERHEAD
    const val BLOCK_RS = 16
    const val BLOCK_CW = BLOCK_PLAINTEXT + BLOCK_RS

    private val headerRs = ReedSolomon(HEADER_RS)
    private val blockRs = ReedSolomon(BLOCK_RS)

    /** Metadata describing one transmitted image. */
    data class Meta(
        val format: Int,
        val width: Int,
        val height: Int,
        val payloadLen: Int,
        val payloadCrc32: Long,
        val blockCount: Int,
        val blocksInFrame: Int,
    )

    /** Result of decoding one received frame. */
    data class DecodedFrame(
        val meta: Meta,
        /** Successfully recovered blocks, keyed by block index. */
        val blocks: Map<Int, ByteArray>,
        /** Blocks whose codeword was present but failed FEC/CRC. */
        val corruptCount: Int,
    )

    fun metadataFor(format: Int, width: Int, height: Int, payload: ByteArray): Meta {
        require(format in 0..255) { "format tag out of range" }
        val blockCount = (payload.size + BLOCK_DATA_LEN - 1) / BLOCK_DATA_LEN
        return Meta(
            format = format,
            width = width,
            height = height,
            payloadLen = payload.size,
            payloadCrc32 = Crc.crc32(payload),
            blockCount = maxOf(blockCount, 1),
            blocksInFrame = maxOf(blockCount, 1),
        )
    }

    // --- header ---

    fun encodeHeader(meta: Meta): ByteArray {
        val d = IntArray(HEADER_DATA_LEN)
        d[0] = MAGIC0
        d[1] = MAGIC1
        d[2] = VERSION
        d[3] = meta.format and 0xFF
        putU16(d, 4, meta.width)
        putU16(d, 6, meta.height)
        putU32(d, 8, meta.payloadLen.toLong())
        putU32(d, 12, meta.payloadCrc32)
        putU16(d, 16, meta.blockCount)
        putU16(d, 18, BLOCK_DATA_LEN)
        putU16(d, 20, meta.blocksInFrame)
        return toBytes(headerRs.encode(d))
    }

    /** Decode a header codeword; null if the magic/version/FEC is unrecoverable. */
    fun decodeHeader(cw: ByteArray, from: Int = 0): Meta? {
        if (from + HEADER_CW > cw.size) return null
        val d = headerRs.decode(toInts(cw, from, HEADER_CW)) ?: return null
        if (d[0] != MAGIC0 || d[1] != MAGIC1 || d[2] != VERSION) return null
        val blockDataLen = getU16(d, 18)
        if (blockDataLen != BLOCK_DATA_LEN) return null
        val blockCount = getU16(d, 16)
        // A corrupt-but-FEC-valid header must not produce a payloadLen that
        // wraps negative or blows up an allocation (e.g. ByteArray(payloadLen)
        // in assemble()). Parse it as unsigned and require it to fit the
        // declared block span before trusting it.
        val payloadLen = getU32(d, 8)
        if (payloadLen < 0L || payloadLen > blockCount.toLong() * BLOCK_DATA_LEN) return null
        return Meta(
            format = d[3],
            width = getU16(d, 4),
            height = getU16(d, 6),
            payloadLen = payloadLen.toInt(),
            payloadCrc32 = getU32(d, 12),
            blockCount = blockCount,
            blocksInFrame = getU16(d, 20),
        )
    }

    // --- blocks ---

    fun encodeBlock(payload: ByteArray, index: Int): ByteArray {
        val d = IntArray(BLOCK_PLAINTEXT)
        putU16(d, 0, index)
        val start = index * BLOCK_DATA_LEN
        val len = minOf(BLOCK_DATA_LEN, payload.size - start).coerceAtLeast(0)
        putU16(d, 2, len)
        for (i in 0 until len) d[4 + i] = payload[start + i].toInt() and 0xFF
        val crc = Crc.crc16(toBytes(d), 0, 4 + BLOCK_DATA_LEN)
        putU16(d, 4 + BLOCK_DATA_LEN, crc)
        return toBytes(blockRs.encode(d))
    }

    /** Decode a block codeword to `(index, data)`, or null on FEC/CRC failure. */
    fun decodeBlock(cw: ByteArray, from: Int): Pair<Int, ByteArray>? {
        if (from + BLOCK_CW > cw.size) return null
        val d = blockRs.decode(toInts(cw, from, BLOCK_CW)) ?: return null
        val bytes = toBytes(d)
        val storedCrc = getU16(d, 4 + BLOCK_DATA_LEN)
        if (Crc.crc16(bytes, 0, 4 + BLOCK_DATA_LEN) != storedCrc) return null
        val index = getU16(d, 0)
        val len = getU16(d, 2)
        if (len > BLOCK_DATA_LEN) return null
        return index to bytes.copyOfRange(4, 4 + len)
    }

    // --- whole frames ---

    /**
     * Byte stream for a frame carrying [indices] (in order). The returned
     * [Meta] reflects `indices.size` as `blocksInFrame`.
     */
    fun encodeFrame(baseMeta: Meta, payload: ByteArray, indices: List<Int>): ByteArray {
        val meta = baseMeta.copy(blocksInFrame = indices.size)
        val out = ByteArray(HEADER_CW + indices.size * BLOCK_CW)
        System.arraycopy(encodeHeader(meta), 0, out, 0, HEADER_CW)
        var pos = HEADER_CW
        for (idx in indices) {
            System.arraycopy(encodeBlock(payload, idx), 0, out, pos, BLOCK_CW)
            pos += BLOCK_CW
        }
        return out
    }

    /** A first transmission carrying every block, in order. */
    fun encodeFullFrame(meta: Meta, payload: ByteArray): ByteArray =
        encodeFrame(meta, payload, (0 until meta.blockCount).toList())

    /**
     * Decode a whole frame byte stream: the header, then `blocksInFrame` block
     * codewords. Returns null only when the header itself is unrecoverable.
     */
    fun decodeFrame(bytes: ByteArray): DecodedFrame? {
        val meta = decodeHeader(bytes, 0) ?: return null
        val blocks = LinkedHashMap<Int, ByteArray>()
        var corrupt = 0
        var pos = HEADER_CW
        for (i in 0 until meta.blocksInFrame) {
            if (pos + BLOCK_CW > bytes.size) break
            val b = decodeBlock(bytes, pos)
            if (b != null && b.first < meta.blockCount) blocks[b.first] = b.second else corrupt++
            pos += BLOCK_CW
        }
        return DecodedFrame(meta, blocks, corrupt)
    }

    // --- reassembly + ARQ ---

    /** Block indices not yet in [have], given a total of [blockCount]. */
    fun missingBlocks(blockCount: Int, have: Set<Int>): List<Int> =
        (0 until blockCount).filter { it !in have }

    /**
     * Concatenate blocks 0..blockCount-1 into the payload and verify its
     * CRC-32. Returns null if any block is missing or the CRC fails.
     */
    fun assemble(meta: Meta, blocks: Map<Int, ByteArray>): ByteArray? {
        if (missingBlocks(meta.blockCount, blocks.keys).isNotEmpty()) return null
        val out = ByteArray(meta.payloadLen)
        var pos = 0
        for (i in 0 until meta.blockCount) {
            val data = blocks[i] ?: return null
            val n = minOf(data.size, out.size - pos)
            System.arraycopy(data, 0, out, pos, n)
            pos += n
        }
        if (pos != meta.payloadLen) return null
        return if (Crc.crc32(out) == meta.payloadCrc32) out else null
    }

    /** Encode a Block-Sequence-Report: the sorted list of missing indices. */
    fun encodeRetransmitRequest(missing: List<Int>): ByteArray {
        val sorted = missing.distinct().sorted()
        val out = ByteArray(2 + sorted.size * 2)
        putU16Bytes(out, 0, sorted.size)
        for ((i, idx) in sorted.withIndex()) putU16Bytes(out, 2 + i * 2, idx)
        return out
    }

    fun decodeRetransmitRequest(bytes: ByteArray): List<Int> {
        if (bytes.size < 2) return emptyList()
        val n = getU16Bytes(bytes, 0)
        val out = ArrayList<Int>(n)
        for (i in 0 until n) {
            val off = 2 + i * 2
            if (off + 2 > bytes.size) break
            out.add(getU16Bytes(bytes, off))
        }
        return out
    }

    // --- byte helpers (IntArray element domain is [0,255]) ---

    private fun putU16(d: IntArray, off: Int, v: Int) {
        d[off] = (v ushr 8) and 0xFF
        d[off + 1] = v and 0xFF
    }

    private fun getU16(d: IntArray, off: Int): Int = (d[off] shl 8) or d[off + 1]

    private fun putU32(d: IntArray, off: Int, v: Long) {
        d[off] = ((v ushr 24) and 0xFF).toInt()
        d[off + 1] = ((v ushr 16) and 0xFF).toInt()
        d[off + 2] = ((v ushr 8) and 0xFF).toInt()
        d[off + 3] = (v and 0xFF).toInt()
    }

    private fun getU32(d: IntArray, off: Int): Long =
        (d[off].toLong() shl 24) or (d[off + 1].toLong() shl 16) or
            (d[off + 2].toLong() shl 8) or d[off + 3].toLong()

    private fun toBytes(d: IntArray): ByteArray = ByteArray(d.size) { (d[it] and 0xFF).toByte() }

    private fun toInts(b: ByteArray, from: Int, len: Int): IntArray =
        IntArray(len) { b[from + it].toInt() and 0xFF }

    private fun putU16Bytes(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 8) and 0xFF).toByte()
        b[off + 1] = (v and 0xFF).toByte()
    }

    private fun getU16Bytes(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
}
