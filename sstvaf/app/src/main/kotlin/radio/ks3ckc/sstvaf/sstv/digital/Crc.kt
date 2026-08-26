package radio.ks3ckc.sstvaf.sstv.digital

/**
 * Checksums for the digital-SSTV container: a per-block CRC-16 for cheap
 * "did this block decode intact" tests, and a CRC-32 over the whole
 * reassembled payload as a final integrity gate.
 *
 * [crc16] is CRC-16/CCITT-FALSE (poly `0x1021`, init `0xFFFF`, no reflection):
 * `crc16("123456789") == 0x29B1`.
 * [crc32] is the standard zlib/PKZIP CRC-32 (reflected poly `0xEDB88320`,
 * init/xorout `0xFFFFFFFF`): `crc32("123456789") == 0xCBF43926`.
 */
internal object Crc {

    fun crc16(data: ByteArray, from: Int = 0, len: Int = data.size - from): Int {
        var crc = 0xFFFF
        for (i in from until from + len) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }

    fun crc32(data: ByteArray, from: Int = 0, len: Int = data.size - from): Long {
        var crc = 0xFFFFFFFFL
        for (i in from until from + len) {
            crc = crc xor ((data[i].toLong() and 0xFF))
            repeat(8) {
                crc = if (crc and 1L != 0L) (crc ushr 1) xor 0xEDB88320L else crc ushr 1
            }
        }
        return (crc xor 0xFFFFFFFFL) and 0xFFFFFFFFL
    }
}
