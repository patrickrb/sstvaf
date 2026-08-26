package radio.ks3ckc.sstvaf.sstv.digital

import kotlin.math.ceil

/**
 * Top-level digital-SSTV (EasyPal/HamDRM-style) codec: turns a compressed
 * image payload into a COFDM audio waveform and back, tying together the
 * container/ARQ framing ([DigitalSstvContainer]), Reed-Solomon FEC, bit
 * interleaving, and the [OfdmModem] transport.
 *
 * Transmit: `header` and `payload` byte segments are each bit-interleaved,
 * QPSK-mapped, and OFDM-modulated; a known preamble symbol is prepended for
 * receiver timing. Receive: [decode] recovers timing, demodulates both
 * segments, and hands the reconstructed frame bytes to the container, yielding
 * the blocks that survived FEC plus the list still missing — the input to a
 * retransmit request.
 *
 * A [DigitalSstvReceiver] accumulates blocks across an initial transmission
 * and any retransmissions until the whole image is present.
 */
internal class DigitalSstvCodec(val mode: DigitalSstvMode = DigitalSstvMode.STANDARD) {

    private val modem = OfdmModem(mode.ofdm)
    private val interleaver = BitInterleaver(mode.interleaveRows.coerceAtLeast(1))
    private val symbolLength get() = modem.symbolLength
    private val bitsPerSymbol get() = modem.bitsPerSymbol

    /** A compressed image ready to transmit (opaque payload + metadata). */
    data class Image(val format: Int, val width: Int, val height: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Image && format == other.format && width == other.width &&
                height == other.height && payload.contentEquals(other.payload)

        override fun hashCode(): Int =
            (((format * 31 + width) * 31 + height) * 31) + payload.contentHashCode()
    }

    /** What [decode] recovered from one received transmission. */
    data class Decoded(
        val meta: DigitalSstvContainer.Meta,
        val blocks: Map<Int, ByteArray>,
        val missing: List<Int>,
        val corruptCount: Int,
    )

    /** Image-format tags carried in the container header. */
    object Format {
        const val RAW = 0
        const val JPEG = 1
        const val JPEG2000 = 2
        const val PNG = 3
        const val WEBP = 4
    }

    /** Encode a first transmission carrying every block. */
    fun encode(image: Image): FloatArray {
        val meta = DigitalSstvContainer.metadataFor(image.format, image.width, image.height, image.payload)
        return encodeFrame(meta, image.payload, (0 until meta.blockCount).toList())
    }

    /** Encode a retransmission carrying only [indices] (a BSR response). */
    fun encodeRetransmission(image: Image, indices: List<Int>): FloatArray {
        val meta = DigitalSstvContainer.metadataFor(image.format, image.width, image.height, image.payload)
        val wanted = indices.distinct().filter { it in 0 until meta.blockCount }.sorted()
        return encodeFrame(meta, image.payload, wanted)
    }

    private fun encodeFrame(
        meta: DigitalSstvContainer.Meta,
        payload: ByteArray,
        indices: List<Int>,
    ): FloatArray {
        val header = DigitalSstvContainer.encodeHeader(meta.copy(blocksInFrame = indices.size))
        val body = ByteArray(indices.size * DigitalSstvContainer.BLOCK_CW)
        var pos = 0
        for (idx in indices) {
            System.arraycopy(DigitalSstvContainer.encodeBlock(payload, idx), 0, body, pos, DigitalSstvContainer.BLOCK_CW)
            pos += DigitalSstvContainer.BLOCK_CW
        }
        val samples = ArrayList<Double>()
        modem.preamble().forEach { samples.add(it) }
        modulateSegment(header).forEach { samples.add(it) }
        modulateSegment(body).forEach { samples.add(it) }
        return normalize(samples)
    }

    /**
     * Decode one received transmission. [searchLimit] bounds the timing-sync
     * search (samples of leading noise/silence to scan). Returns null when the
     * preamble/header cannot be recovered.
     */
    fun decode(audio: FloatArray, searchLimit: Int = DEFAULT_SEARCH_LIMIT): Decoded? {
        val samples = DoubleArray(audio.size) { audio[it].toDouble() }
        val start = modem.findFrameStart(samples, searchLimit)
        var pos = start + symbolLength // skip preamble

        val headerBytes = demodulateSegment(samples, pos, DigitalSstvContainer.HEADER_CW) ?: return null
        val meta = DigitalSstvContainer.decodeHeader(headerBytes) ?: return null
        pos += segmentSymbolCount(DigitalSstvContainer.HEADER_CW) * symbolLength

        val bodyByteLen = meta.blocksInFrame * DigitalSstvContainer.BLOCK_CW
        val bodyBytes = demodulateSegment(samples, pos, bodyByteLen)
        val frame = if (bodyBytes == null) headerBytes else headerBytes + bodyBytes
        val decoded = DigitalSstvContainer.decodeFrame(frame) ?: return null
        val missing = DigitalSstvContainer.missingBlocks(decoded.meta.blockCount, decoded.blocks.keys)
        return Decoded(decoded.meta, decoded.blocks, missing, decoded.corruptCount)
    }

    // --- segment (byte stream) <-> OFDM symbols ---

    private fun modulateSegment(bytes: ByteArray): DoubleArray {
        val bits = bytesToBits(bytes)
        val interleaved = interleaver.interleave(bits)
        val nsym = segmentSymbolCount(bytes.size)
        val padded = IntArray(nsym * bitsPerSymbol)
        System.arraycopy(interleaved, 0, padded, 0, minOf(interleaved.size, padded.size))
        val out = DoubleArray(nsym * symbolLength)
        for (s in 0 until nsym) {
            val chunk = padded.copyOfRange(s * bitsPerSymbol, (s + 1) * bitsPerSymbol)
            System.arraycopy(modem.modulateSymbol(chunk), 0, out, s * symbolLength, symbolLength)
        }
        return out
    }

    private fun demodulateSegment(audio: DoubleArray, pos: Int, byteCount: Int): ByteArray? {
        val nbits = byteCount * 8
        val nsym = segmentSymbolCount(byteCount)
        if (pos < 0 || pos + nsym * symbolLength > audio.size) return null
        val bits = IntArray(nsym * bitsPerSymbol)
        for (s in 0 until nsym) {
            val sym = modem.demodulateSymbol(audio, pos + s * symbolLength)
            System.arraycopy(sym, 0, bits, s * bitsPerSymbol, bitsPerSymbol)
        }
        val deint = interleaver.deinterleave(bits.copyOf(interleavedBitCount(nbits)), nbits)
        return bitsToBytes(deint, byteCount)
    }

    /** Post-interleave bit count (rows * cols) for [nbits] input bits. */
    private fun interleavedBitCount(nbits: Int): Int {
        if (nbits == 0) return 0
        val rows = mode.interleaveRows.coerceAtLeast(1)
        val cols = ceil(nbits.toDouble() / rows).toInt()
        return rows * cols
    }

    /** OFDM symbols needed to carry [byteCount] bytes through the interleaver. */
    private fun segmentSymbolCount(byteCount: Int): Int {
        val interleavedLen = interleavedBitCount(byteCount * 8)
        if (interleavedLen == 0) return 0
        return ceil(interleavedLen.toDouble() / bitsPerSymbol).toInt()
    }

    private fun normalize(samples: List<Double>): FloatArray {
        var peak = 0.0
        for (v in samples) if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
        val scale = if (peak > 1e-9) TARGET_PEAK / peak else 1.0
        return FloatArray(samples.size) { (samples[it] * scale).toFloat() }
    }

    companion object {
        /** Peak amplitude of the encoded waveform (headroom below full scale). */
        const val TARGET_PEAK = 0.9

        /** Default leading samples scanned for the preamble during timing sync. */
        const val DEFAULT_SEARCH_LIMIT = 4096

        internal fun bytesToBits(bytes: ByteArray): IntArray {
            val bits = IntArray(bytes.size * 8)
            for (i in bytes.indices) {
                val b = bytes[i].toInt() and 0xFF
                for (j in 0 until 8) bits[i * 8 + j] = (b ushr (7 - j)) and 1
            }
            return bits
        }

        internal fun bitsToBytes(bits: IntArray, byteCount: Int): ByteArray {
            val out = ByteArray(byteCount)
            for (i in 0 until byteCount) {
                var v = 0
                for (j in 0 until 8) {
                    val idx = i * 8 + j
                    v = (v shl 1) or if (idx < bits.size) bits[idx] and 1 else 0
                }
                out[i] = v.toByte()
            }
            return out
        }
    }
}

/**
 * Accumulates decoded blocks across an initial digital-SSTV transmission and
 * any retransmissions, exposing the still-missing block list (for a
 * Block-Sequence-Report retransmit request) and the finished payload once the
 * whole image is present and its CRC checks out.
 */
internal class DigitalSstvReceiver {
    private var meta: DigitalSstvContainer.Meta? = null
    private val blocks = HashMap<Int, ByteArray>()

    /** Merge the good blocks of one decoded transmission. */
    fun accept(decoded: DigitalSstvCodec.Decoded) {
        val current = meta
        // A different payload CRC means a new image — drop stale accumulation.
        if (current != null && current.payloadCrc32 != decoded.meta.payloadCrc32) blocks.clear()
        meta = decoded.meta
        for ((idx, data) in decoded.blocks) blocks[idx] = data
    }

    /** Block indices still needed, or empty when nothing has been received. */
    fun missing(): List<Int> {
        val m = meta ?: return emptyList()
        return DigitalSstvContainer.missingBlocks(m.blockCount, blocks.keys)
    }

    /** True once every block is present. */
    fun isComplete(): Boolean = meta != null && missing().isEmpty()

    /** Encoded Block-Sequence-Report for the sender, or null when complete. */
    fun retransmitRequest(): ByteArray? {
        val m = missing()
        return if (m.isEmpty()) null else DigitalSstvContainer.encodeRetransmitRequest(m)
    }

    /** The reassembled payload once complete and CRC-valid, else null. */
    fun payload(): ByteArray? {
        val m = meta ?: return null
        return DigitalSstvContainer.assemble(m, blocks)
    }

    fun metadata(): DigitalSstvContainer.Meta? = meta
}
