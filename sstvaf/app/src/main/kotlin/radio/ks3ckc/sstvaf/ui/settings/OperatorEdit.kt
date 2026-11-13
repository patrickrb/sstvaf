package radio.ks3ckc.sstvaf.ui.settings

/**
 * Normalisation for the operator identity fields.
 *
 * These four values end up in every log entry and on every transmitted card,
 * so they are normalised once, here, on the way into storage rather than at
 * each of the places that read them. Extracted from the composable so the
 * normalisation is testable and so the sheet and any future importer cannot
 * disagree about what a valid callsign looks like.
 */

/** The longest power value worth storing, in watts. */
internal const val MAX_POWER_WATTS = 9999

/**
 * A callsign as it should be stored: trimmed and upper-cased.
 *
 * Callsigns are case-insensitive on the air but compared as strings in the log
 * and in duplicate checks, so a lower-case entry would read as a different
 * station from the same one typed in capitals.
 */
internal fun normalizeCallsign(raw: String): String = raw.trim().uppercase()

/**
 * A Maidenhead locator in its conventional mixed case: field letters upper,
 * square digits as typed, subsquare letters lower.
 *
 * The convention is cosmetic for the grid maths but not for the log: several
 * ADIF consumers compare locator strings literally, so "FN42" and "fn42" can
 * count as different grids.
 */
internal fun normalizeGrid(raw: String): String = buildString {
    raw.trim().forEachIndexed { index, c ->
        append(if (index < 2) c.uppercaseChar() else c.lowercaseChar())
    }
}

/** An antenna description as stored: trimmed, otherwise free-form. */
internal fun normalizeAntenna(raw: String): String = raw.trim()

/**
 * A power entry parsed to watts, or 0 when there is nothing usable.
 *
 * Zero is the "not set" value the operator card already renders as a dash, so
 * a blank or nonsense entry clears the field rather than failing the save and
 * losing the other three values with it.
 */
internal fun parsePowerWatts(raw: String): Int {
    val digits = raw.trim().filter { it.isDigit() }
    if (digits.isEmpty()) return 0
    return digits.toIntOrNull()?.coerceIn(0, MAX_POWER_WATTS) ?: 0
}

/**
 * The `grid · antenna · power` line under the callsign on the operator card.
 *
 * Unset values are dropped rather than shown as dashes: three dashes in a row
 * is noise, and an operator who has not filled these in does not need to be
 * told three times.
 */
internal fun operatorDetailLine(grid: String, antenna: String, powerWatts: Int): String {
    val parts = mutableListOf<String>()
    grid.trim().takeIf { it.isNotEmpty() }?.let { parts.add(it.uppercase()) }
    antenna.trim().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    if (powerWatts > 0) parts.add("${powerWatts}W")
    return parts.joinToString(" · ")
}
