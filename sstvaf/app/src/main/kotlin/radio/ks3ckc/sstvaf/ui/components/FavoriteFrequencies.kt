package radio.ks3ckc.sstvaf.ui.components

import java.util.Locale

/**
 * Operator-defined favourite frequencies for the Frequency sheet.
 *
 * The calling-frequency table ([SSTV_CALLING_FREQUENCIES]) is where SSTV
 * activity lives by convention, but plenty of operating happens off it: 11m
 * groups each sit on their own dial, nets pick a frequency and keep it, and a
 * club may have a house frequency for picture exchanges. The favourites list
 * lets the operator save those dials and retune the rig to one with a tap,
 * exactly like a calling-frequency row.
 *
 * Persistence follows the `excludedBands` / `perBandOutputLevels` convention:
 * one config row under [FAVORITE_FREQUENCIES_KEY] holding the whole list as a
 * string (see [serializeFavoriteFrequencies]). Everything in this file is pure
 * Kotlin so the parsing, validation and list edits are unit-testable without
 * Compose or an Android runtime.
 */

/** Config key for the serialized favourites list. */
const val FAVORITE_FREQUENCIES_KEY = "favoriteFrequencies"

/** Separates one favourite from the next in the serialized form. */
private const val ENTRY_SEPARATOR = '|'

/** Separates the dial from its label inside one serialized favourite. */
private const val FIELD_SEPARATOR = ':'

/** Longest label kept; anything beyond this is cut so a row still fits on one line. */
internal const val FAVORITE_LABEL_MAX_LENGTH = 24

/** Lowest dial a favourite may carry: 100 kHz, below every allocation a rig can reach. */
internal const val FAVORITE_MIN_HZ = 100_000L

/** Highest dial a favourite may carry: 3 GHz, above every allocation a rig can reach. */
internal const val FAVORITE_MAX_HZ = 3_000_000_000L

/**
 * Unit suffixes [parseFrequencyInputHz] strips, longest first so "mhz" is not
 * mistaken for a bare "hz".
 */
private val FREQUENCY_UNIT_SUFFIXES: List<Pair<String, Double>> = listOf(
    "mhz" to 1_000_000.0,
    "khz" to 1_000.0,
    "hz" to 1.0,
)

/**
 * One saved dial. [label] is the operator's own short name for it ("11m group",
 * "Tuesday net") and may be empty, in which case the row shows only the dial.
 */
data class FavoriteFrequency(
    val freqHz: Long,
    val label: String = "",
)

/**
 * Clean a user-typed label for storage: trimmed, flattened to one line, with
 * the serialization separator removed so it can never split the list, and
 * cut to [FAVORITE_LABEL_MAX_LENGTH].
 */
internal fun sanitizeFavoriteLabel(raw: String?): String {
    if (raw == null) return ""
    return raw
        .replace(ENTRY_SEPARATOR, ' ')
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
        .take(FAVORITE_LABEL_MAX_LENGTH)
        .trim()
}

/**
 * Parse the operator's typed frequency into Hz, or null when it is not a usable
 * dial.
 *
 * The field is a plain text box, so this accepts what people actually type:
 * - `27.700`, `27,700` (a decimal comma, as half of Europe types it) → MHz
 * - `27700` → kHz, because nobody means 27.7 kHz
 * - `27700000` → Hz
 * - any of the above with a trailing `MHz`, `kHz` or `Hz`, which then wins
 *   over the magnitude guess.
 *
 * Without a unit the magnitude decides: under 1 000 is MHz, under 1 000 000 is
 * kHz, otherwise Hz. The result must land inside [FAVORITE_MIN_HZ]..
 * [FAVORITE_MAX_HZ]; a typo like `2770000000` is rejected rather than saved
 * as a dial no rig has.
 */
internal fun parseFrequencyInputHz(text: String?): Long? {
    if (text == null) return null
    var s = text.trim().lowercase(Locale.US).replace(" ", "").replace("_", "")
    if (s.isEmpty()) return null

    val unit = FREQUENCY_UNIT_SUFFIXES.firstOrNull { s.endsWith(it.first) }
    if (unit != null) s = s.removeSuffix(unit.first)
    val unitScale: Double? = unit?.second
    // A decimal comma is a decimal point when there is no point already;
    // "27,700" is 27.7 MHz, not 27 700 of anything.
    if (!s.contains('.')) s = s.replace(',', '.')
    // Only digits and at most one point may remain: "27.7.0", "abc" and a
    // thousands-grouped "27,700.5" are rejected rather than half-parsed.
    if (s.isEmpty() || s.count { it == '.' } > 1 || s.any { it != '.' && !it.isDigit() }) return null
    val value = s.toDoubleOrNull() ?: return null
    if (value <= 0.0) return null

    val scale = unitScale ?: when {
        value < 1_000.0 -> 1_000_000.0
        value < 1_000_000.0 -> 1_000.0
        else -> 1.0
    }
    val hz = Math.round(value * scale)
    return if (hz in FAVORITE_MIN_HZ..FAVORITE_MAX_HZ) hz else null
}

/**
 * Parse the config string back into favourites, dial order, one per distinct
 * dial. Malformed entries (no dial, a dial that is not a number, a dial outside
 * the accepted range) are dropped rather than failing the whole list, so one
 * bad row in a hand-edited backup cannot wipe every favourite. Null or blank
 * input is simply no favourites.
 */
internal fun parseFavoriteFrequencies(serialized: String?): List<FavoriteFrequency> {
    if (serialized.isNullOrBlank()) return emptyList()
    val byFreq = LinkedHashMap<Long, FavoriteFrequency>()
    for (entry in serialized.split(ENTRY_SEPARATOR)) {
        val sep = entry.indexOf(FIELD_SEPARATOR)
        val freqText = (if (sep >= 0) entry.substring(0, sep) else entry).trim()
        val label = if (sep >= 0) entry.substring(sep + 1) else ""
        val hz = freqText.toLongOrNull() ?: continue
        if (hz !in FAVORITE_MIN_HZ..FAVORITE_MAX_HZ) continue
        byFreq[hz] = FavoriteFrequency(hz, sanitizeFavoriteLabel(label))
    }
    return byFreq.values.sortedBy { it.freqHz }
}

/**
 * Serialize favourites to the config string: `freqHz:label` entries joined by
 * `|`, e.g. `27700000:11m group|14233000:`. The label follows the first colon,
 * so a label may itself contain colons.
 */
internal fun serializeFavoriteFrequencies(favorites: List<FavoriteFrequency>): String =
    favorites.joinToString(ENTRY_SEPARATOR.toString()) {
        "${it.freqHz}$FIELD_SEPARATOR${sanitizeFavoriteLabel(it.label)}"
    }

/**
 * The list with [favorite] added, kept in dial order. Saving a dial that is
 * already a favourite replaces its label instead of producing a duplicate
 * row: two rows for one frequency would both highlight when it is dialled in,
 * and there is nothing to choose between them.
 */
internal fun addFavoriteFrequency(
    favorites: List<FavoriteFrequency>,
    favorite: FavoriteFrequency,
): List<FavoriteFrequency> {
    val cleaned = favorite.copy(label = sanitizeFavoriteLabel(favorite.label))
    return (favorites.filterNot { it.freqHz == cleaned.freqHz } + cleaned).sortedBy { it.freqHz }
}

/** The list without the favourite on [freqHz]; unchanged when there is none. */
internal fun removeFavoriteFrequency(
    favorites: List<FavoriteFrequency>,
    freqHz: Long,
): List<FavoriteFrequency> = favorites.filterNot { it.freqHz == freqHz }

/** Whether a favourite row is the one currently dialled in. Same rule as the calling rows. */
internal fun isFavoriteSelected(favorite: FavoriteFrequency, currentFreqHz: Long): Boolean =
    favorite.freqHz == currentFreqHz

/**
 * The dial as the add dialog pre-fills it: MHz with three decimals, so an
 * operator saving "where I am now" only has to type a name.
 */
internal fun favoriteInputText(freqHz: Long): String = formatMhz(freqHz)
