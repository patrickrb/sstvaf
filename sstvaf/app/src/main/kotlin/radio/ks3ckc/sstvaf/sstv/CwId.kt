package radio.ks3ckc.sstvaf.sstv

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Settings for the optional CW (Morse) station-ID tail appended after an SSTV
 * image transmission (issue #14).
 *
 * [text] is normally the operator's callsign; [wpm] is the keying speed in
 * words-per-minute (PARIS timing), capped at [CwId.MAX_WPM].
 */
data class CwIdSettings(
    val enabled: Boolean,
    val text: String,
    val wpm: Int,
)

/**
 * Pure CW/Morse tone generator for the station-ID tail.
 *
 * Everything here is deterministic and side-effect free so it can be unit
 * tested directly (no Android, no native lib). [SstvTransmitter] uses
 * [appendTo] to glue a keyed CW callsign onto the tail of the encoded SSTV
 * waveform — appended to the *tail*, never the head, so the leading SSTV
 * calibration/VIS the receiver needs is untouched (see CLAUDE.md "Never clip
 * the leading audio").
 */
object CwId {

    /** One alternating keying segment: tone on/off for [units] dit-lengths. */
    internal data class Segment(val keyed: Boolean, val units: Int)

    /** Hard cap on keying speed (issue #14: "maximum and default of 20WPM"). */
    const val MAX_WPM = 20

    /** Default keying speed. */
    const val DEFAULT_WPM = 20

    /** CW sidetone frequency, comfortably inside the SSB/FM audio passband. */
    const val TONE_HZ = 800.0

    /** Peak amplitude of the keyed tone (matched roughly to the image level). */
    const val AMPLITUDE = 0.9f

    /** Silence between the image tail and the CW ID, in milliseconds. */
    const val LEAD_GAP_MS = 700

    /** Raised-cosine key-edge ramp (each edge), in milliseconds — anti-click. */
    private const val RAMP_MS = 5.0

    /**
     * International Morse code for the characters we key: A–Z and 0–9. Callsigns
     * also use '/' (portable/mobile suffixes), so it is included. Anything not
     * in the table is skipped without disturbing spacing.
     */
    internal val MORSE: Map<Char, String> = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".",
        'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---",
        'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---",
        'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-",
        'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--",
        'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--",
        '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...",
        '8' to "---..", '9' to "----.",
        '/' to "-..-.",
    )

    /** Dot ("dit") length in milliseconds at [wpm], PARIS timing (1200/WPM). */
    internal fun ditMs(wpm: Int): Double = 1200.0 / wpm.coerceAtLeast(1)

    /**
     * The keying timeline for [text] as alternating tone-on / tone-off segments
     * measured in dit units, using PARIS spacing: dot = 1, dash = 3,
     * intra-character gap = 1, inter-character gap = 3, word gap = 7. Leading
     * and trailing gaps are omitted, unknown characters are skipped, and the
     * result is empty for blank/uncodeable input.
     */
    internal fun keyingUnits(text: String): List<Segment> {
        // Locale-independent upper-casing: the Morse table is ASCII only, so a
        // device locale (e.g. Turkish, where 'i' upper-cases to 'İ') must not
        // change which characters key. International Morse is never localized.
        val cleaned = text.trim().uppercase(java.util.Locale.ROOT)
        if (cleaned.isEmpty()) return emptyList()
        val out = mutableListOf<Segment>()
        var prevWasChar = false
        var wordBreakPending = false
        for (ch in cleaned) {
            if (ch == ' ') {
                if (prevWasChar) wordBreakPending = true
                continue
            }
            val code = MORSE[ch] ?: continue
            if (prevWasChar) {
                out += Segment(keyed = false, units = if (wordBreakPending) 7 else 3)
            }
            wordBreakPending = false
            code.forEachIndexed { i, symbol ->
                if (i > 0) out += Segment(keyed = false, units = 1) // intra-char gap
                out += Segment(keyed = true, units = if (symbol == '-') 3 else 1)
            }
            prevWasChar = true
        }
        return out
    }

    /**
     * Render [text] as a keyed CW tone at [wpm] into a mono float buffer at
     * [sampleRate]. Returns an empty array when nothing is keyable. Each keyed
     * element gets a raised-cosine edge to suppress key clicks.
     */
    fun encode(
        text: String,
        sampleRate: Int,
        wpm: Int,
        toneHz: Double = TONE_HZ,
        amplitude: Float = AMPLITUDE,
    ): FloatArray {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        val segments = keyingUnits(text)
        if (segments.isEmpty()) return FloatArray(0)

        val samplesPerUnit = ditMs(wpm.coerceAtMost(MAX_WPM)) * sampleRate / 1000.0
        // Cumulative sample boundaries keep segment lengths drift-free over a
        // long callsign (rounding once against the running total, not per-item).
        var acc = 0.0
        val out = ArrayList<Float>(
            (samplesPerUnit * segments.sumOf { it.units }).toInt() + 1,
        )
        val step = 2.0 * PI * toneHz / sampleRate
        for (seg in segments) {
            val start = acc
            acc += seg.units * samplesPerUnit
            val len = Math.round(acc) - Math.round(start)
            if (len <= 0L) continue
            val n = len.toInt()
            if (!seg.keyed) {
                repeat(n) { out += 0f }
                continue
            }
            val ramp = minOf((RAMP_MS * sampleRate / 1000.0).toInt(), n / 2)
            for (i in 0 until n) {
                val env = when {
                    ramp > 0 && i < ramp -> 0.5 * (1.0 - cos(PI * i / ramp))
                    ramp > 0 && i >= n - ramp -> 0.5 * (1.0 - cos(PI * (n - 1 - i) / ramp))
                    else -> 1.0
                }
                out += (amplitude * env * sin(step * i)).toFloat()
            }
        }
        return out.toFloatArray()
    }

    /**
     * The standalone CW-ID buffer for [settings]: a [LEAD_GAP_MS] lead-in
     * silence followed by the keyed callsign, or an empty array when the ID is
     * disabled or the text is not keyable. Kept separate from the image so it
     * can be played as its own buffer — notably it is still sent after a
     * user-cancelled image transmission (the operator must still identify).
     */
    fun tail(settings: CwIdSettings, sampleRate: Int): FloatArray {
        if (!settings.enabled) return FloatArray(0)
        val cw = encode(settings.text, sampleRate, settings.wpm)
        if (cw.isEmpty()) return FloatArray(0)
        val gap = (LEAD_GAP_MS.toLong() * sampleRate / 1000L).toInt()
        val out = FloatArray(gap + cw.size)
        // [gap] leading samples are already zero-initialised (silence).
        cw.copyInto(out, gap)
        return out
    }

    /**
     * Return [imageAudio] with a CW ID [tail] appended when [settings] is
     * enabled and its text is keyable; otherwise return [imageAudio] unchanged.
     */
    fun appendTo(imageAudio: FloatArray, settings: CwIdSettings, sampleRate: Int): FloatArray {
        val tail = tail(settings, sampleRate)
        if (tail.isEmpty()) return imageAudio
        val out = FloatArray(imageAudio.size + tail.size)
        imageAudio.copyInto(out, 0)
        tail.copyInto(out, imageAudio.size)
        return out
    }
}
