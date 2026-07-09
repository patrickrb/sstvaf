package radio.ks3ckc.sstvaf.sstv.digital

/**
 * Digital-SSTV robustness modes, the digital analogue of the analog
 * [radio.ks3ckc.sstvaf.sstv.SstvMode] picker entries. Each mode is a COFDM
 * configuration trading throughput against HF resilience — a deeper guard
 * interval and time-interleave survive multipath and fading at the cost of a
 * longer transmission, mirroring the DRM robustness modes EasyPal exposes.
 *
 * These are distinct from the native analog modes (they do not cross the JNI
 * mode-id boundary), so they live in their own enum; [DigitalSstvModeTest]
 * pins every field.
 */
enum class DigitalSstvMode(
    val displayName: String,
    val shortCode: String,
    internal val ofdm: OfdmParams,
    internal val interleaveRows: Int,
) {
    /** Longest guard interval and interleave; best on poor HF paths. */
    ROBUST("Digital Robust", "DGR", OfdmParams(nfft = 256, ncp = 64, kLo = 16, kHi = 112, pilotEvery = 6), 32),

    /** Balanced default. */
    STANDARD("Digital Standard", "DGS", OfdmParams(nfft = 256, ncp = 32, kLo = 16, kHi = 112, pilotEvery = 8), 16),

    /** Shortest guard/interleave; highest throughput on clean channels. */
    FAST("Digital Fast", "DGF", OfdmParams(nfft = 256, ncp = 16, kLo = 16, kHi = 120, pilotEvery = 8), 8),
    ;

    companion object {
        @JvmStatic
        fun fromShortCode(code: String): DigitalSstvMode? = entries.firstOrNull { it.shortCode == code }
    }
}
