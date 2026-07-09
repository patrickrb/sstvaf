package radio.ks3ckc.sstvaf.ui.logbook

import com.k1af.ft8af.log.QSLRecord
import com.k1af.ft8af.maidenhead.MaidenheadGrid
import radio.ks3ckc.sstvaf.sstv.SstvMode
import java.util.Locale

/**
 * Pure decision logic for the manual SSTV QSO entry sheet, extracted per
 * project testing policy ([ManualQsoSheet] stays a thin Compose wrapper; this
 * file carries the unit tests — see ManualQsoLogicTest).
 */

/** The raw text-field values of the manual entry sheet, exactly as typed. */
internal data class ManualQsoInput(
    val callsign: String,
    val grid: String = "",
    val rsvSent: String = DEFAULT_RSV,
    val rsvReceived: String = DEFAULT_RSV,
    val freqMhz: String,
    val mode: SstvMode,
    val comment: String = "",
)

/** Fields that can fail validation (grid/comment are optional). */
internal enum class ManualQsoField { CALLSIGN, GRID, RSV_SENT, RSV_RECEIVED, FREQUENCY }

/** The SSTV-convention default exchange: readability 5, strength 9, video 5. */
internal const val DEFAULT_RSV = "595"

/** Uppercased, trimmed callsign — what actually gets stored. */
internal fun normalizeCallsign(raw: String): String = raw.trim().uppercase(Locale.US)

/**
 * SSTV RSV report: three digits — readability 1–5, strength 1–9, video 1–9
 * (no zeros anywhere, same as classic RST).
 */
internal fun isValidRsv(raw: String): Boolean =
    raw.trim().matches(Regex("^[1-5][1-9][1-9]$"))

/**
 * Parse a dial frequency typed in MHz (e.g. "14.230") into Hz, or null when
 * unparsable or out of any plausible amateur range (>0 to 10 GHz).
 */
internal fun parseMhzToHz(raw: String): Long? {
    val mhz = raw.trim().toDoubleOrNull() ?: return null
    if (mhz <= 0.0 || mhz > 10_000.0) return null
    return Math.round(mhz * 1_000_000.0)
}

/**
 * The MHz text the frequency field is prefilled with, from the current dial
 * frequency in Hz. Whole-kHz dials (the normal case) render with three
 * decimals ("14.230"); anything finer keeps full Hz resolution.
 */
internal fun defaultFreqMhzText(freqHz: Long): String =
    if (freqHz % 1_000L == 0L) {
        String.format(Locale.US, "%.3f", freqHz / 1_000_000.0)
    } else {
        String.format(Locale.US, "%.6f", freqHz / 1_000_000.0)
    }

/**
 * Validate the whole form. Empty result means the input can be saved.
 * Grid is optional but must be a real Maidenhead locator when present;
 * comment is free-form and never fails.
 */
internal fun validateManualQso(input: ManualQsoInput): Set<ManualQsoField> {
    val errors = mutableSetOf<ManualQsoField>()
    if (normalizeCallsign(input.callsign).isEmpty()) errors += ManualQsoField.CALLSIGN
    val grid = input.grid.trim()
    if (grid.isNotEmpty() && !MaidenheadGrid.checkMaidenhead(grid)) errors += ManualQsoField.GRID
    if (!isValidRsv(input.rsvSent)) errors += ManualQsoField.RSV_SENT
    if (!isValidRsv(input.rsvReceived)) errors += ManualQsoField.RSV_RECEIVED
    if (parseMhzToHz(input.freqMhz) == null) errors += ManualQsoField.FREQUENCY
    return errors
}

/**
 * Build the [QSLRecord] a validated form saves, using the same live-QSO
 * constructor the FT8 engine used (so date/time/band/freq formatting stays
 * identical to every other row in QSLTable). Mode is always "SSTV" with the
 * SSTV mode's display name in ADIF SUBMODE. The QSO is logged at [nowMillis]
 * (entry time, UTC) with zero duration.
 *
 * Must only be called when [validateManualQso] returned no errors.
 */
internal fun buildManualQsoRecord(
    input: ManualQsoInput,
    nowMillis: Long,
    myCallsign: String,
    myGrid: String,
): QSLRecord {
    val freqHz = requireNotNull(parseMhzToHz(input.freqMhz)) { "invalid frequency: ${input.freqMhz}" }
    val record = QSLRecord(
        nowMillis, nowMillis,
        myCallsign, myGrid.trim(),
        normalizeCallsign(input.callsign), input.grid.trim(),
        input.rsvSent.trim().toInt(), input.rsvReceived.trim().toInt(),
        "SSTV", freqHz, 0,
    )
    record.setSubmode(input.mode.displayName)
    val comment = input.comment.trim()
    if (comment.isNotEmpty()) {
        record.setComment(comment)
    }
    return record
}
