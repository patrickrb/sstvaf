package radio.ks3ckc.sstvaf.ui.components

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.database.ControlMode
import com.k1af.ft8af.database.OperationBand
import com.k1af.ft8af.rigs.BaseRigOperation
import radio.ks3ckc.sstvaf.theme.*

/**
 * Applies a band selection (by index into [OperationBand.bandList]) to the app: updates
 * GeneralVariables, persists the new bandFreq in config, refreshes QSL callsigns, and
 * pushes the new frequency to the rig when CAT/RTS/DTR control is active.
 *
 * Shared between the Settings band picker and the header's Frequency sheet.
 */
fun selectBandIndex(mainViewModel: MainViewModel, context: Context, index: Int) {
    GeneralVariables.bandListIndex = index
    GeneralVariables.band = OperationBand.getBandFreq(index)
    // An explicit operator choice: this is the dial the app asserts from now on, and the
    // one the reassert heartbeat re-sends — protected from being overwritten by rig
    // echoes until the rig confirms it. See RigDialTarget.
    GeneralVariables.operatorChoseDial(GeneralVariables.band)
    val newWaveLength = BaseRigOperation.getMeterFromFreq(GeneralVariables.band)
    mainViewModel.databaseOpr.writeConfig(
        "bandFreq", GeneralVariables.band.toString(), null,
    )
    mainViewModel.databaseOpr.getAllQSLCallsigns()
    // Notify observers (the header's frequency chip, Settings band picker) so the
    // UI updates without waiting for a rig onFreqChanged round-trip.
    GeneralVariables.mutableBandChange.postValue(index)

    // Per-band output level (issue #355): when enabled and this band has a
    // saved level that differs from the current one, restore it and tell the
    // operator where the change came from. Bands with no saved value keep the
    // current (global) level.
    val restoredLevel = radio.ks3ckc.sstvaf.restoredOutputLevelForBand(newWaveLength)
    if (restoredLevel != null) {
        GeneralVariables.volumePercent = restoredLevel / 100f
        GeneralVariables.mutableVolumePercent.postValue(restoredLevel / 100f)
        mainViewModel.databaseOpr.writeConfig("volumeValue", restoredLevel.toString(), null)
        mainViewModel.baseRig?.connector?.setRFVolume(restoredLevel)
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.per_band_volume_restored, restoredLevel, newWaveLength),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    val cm = GeneralVariables.controlMode
    val connected = mainViewModel.isRigConnected()
    android.util.Log.d(
        "FrequencyPicker",
        "bandSelect: index=$index, band=${GeneralVariables.band}, " +
            "controlMode=$cm, rigConnected=$connected",
    )
    try {
        val dir = context.getExternalFilesDir(null)
        if (dir != null) {
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            java.io.File(dir, "debug.log").appendText(
                "$ts bandSelect: index=$index, band=${GeneralVariables.band}, " +
                    "controlMode=$cm, rigConnected=$connected\n",
            )
        }
    } catch (_: Exception) {
    }

    if (cm == ControlMode.CAT || cm == ControlMode.RTS || cm == ControlMode.DTR) {
        mainViewModel.setOperationBand()
    }
}

internal fun formatMhz(freqHz: Long): String {
    val mhz = freqHz / 1_000_000.0
    return String.format(java.util.Locale.US, "%.3f", mhz)
}

// ---------------------------------------------------------------------------
// The SSTV calling frequencies
// ---------------------------------------------------------------------------

/**
 * One row of the Frequency sheet: a band, the dial everyone calls SSTV on
 * there, and a note on when that band is worth sitting on.
 */
internal data class SstvCallingFrequency(
    val band: String,
    val freqHz: Long,
    @StringRes val noteRes: Int,
)

/**
 * The five SSTV calling frequencies, low band to high.
 *
 * Five rows, not the whole band plan. The old sheet offered a tile grid of every
 * entry in `bands.txt` plus an expandable alternates list — a general-purpose
 * band picker on a screen where the operator has exactly one question: "where is
 * SSTV right now?". These five are where the activity is, and each carries the
 * propagation note that answers "is it worth listening?". The full band list
 * still lives in Radio & audio for the operator who needs 30m or 6m.
 *
 * Every one of these dials is already a marked primary entry in `bands.txt`, so
 * [callingFrequencyBandIndex] resolves each to a real band index without
 * inventing one.
 */
internal val SSTV_CALLING_FREQUENCIES: List<SstvCallingFrequency> = listOf(
    SstvCallingFrequency("80m", 3_845_000L, R.string.freq_note_night),
    SstvCallingFrequency("40m", 7_171_000L, R.string.freq_note_evenings),
    SstvCallingFrequency("20m", 14_230_000L, R.string.freq_note_daytime_busiest),
    SstvCallingFrequency("15m", 21_340_000L, R.string.freq_note_daytime),
    SstvCallingFrequency("10m", 28_680_000L, R.string.freq_note_when_open),
)

/**
 * The index into [OperationBand.bandList] carrying [freqHz], or -1 when no entry
 * has that exact dial.
 *
 * Takes the frequencies as a plain list so the lookup is unit-testable without
 * loading `bands.txt` off the asset manager. Deliberately NOT
 * [OperationBand.getIndexByFreq]: that helper *appends* a synthetic band when it
 * finds no match, which is the right behaviour for tuning to an arbitrary dial
 * but wrong for a highlight check that runs on every recomposition — it would
 * grow the band list forever. Callers that need the append-on-miss behaviour
 * fall back to it explicitly.
 */
internal fun callingFrequencyBandIndex(freqHz: Long, bandFreqsHz: List<Long>): Int =
    bandFreqsHz.indexOfFirst { it == freqHz }

/**
 * Whether a calling-frequency row is the one currently dialled in. Compares the
 * dial itself rather than band-list indices, so an operator who reached 14.230
 * by any route — this sheet, the Settings picker, or the rig's own VFO knob
 * reporting back over CAT — sees the row highlighted.
 */
internal fun isCallingFrequencySelected(row: SstvCallingFrequency, currentFreqHz: Long): Boolean =
    row.freqHz == currentFreqHz

/**
 * The calling-frequency rows to show, with the operator's disabled bands
 * removed.
 *
 * `excludedBands` is defined as the set of wave lengths hidden from band
 * pickers (see [GeneralVariables.isBandExcluded]), and Radio & audio's own
 * picker honours it. This sheet is now the app's primary band picker, so an
 * operator who switched 80m off there should not be offered 80m here.
 *
 * Excluding *every* calling frequency falls back to the full list rather than
 * rendering an empty sheet: the header chip's only job is to let the operator
 * retune, and a picker with no rows in it is a dead end with no way out. The
 * setting hides bands; it is not a licence to remove the tuning control.
 */
internal fun visibleCallingFrequencies(
    rows: List<SstvCallingFrequency>,
    excludedBands: Set<String>,
): List<SstvCallingFrequency> {
    val kept = rows.filterNot { excludedBands.contains(it.band) }
    return if (kept.isEmpty()) rows else kept
}

// ---------------------------------------------------------------------------
// TX level / TUNE helpers (moved here when the TX strip was deleted)
// ---------------------------------------------------------------------------

/**
 * Clamp a volume value after a +/- step to the 0–100 range.
 * Extracted so it can be unit-tested without Compose.
 */
internal fun clampVolume(current: Int, delta: Int): Int =
    (current + delta).coerceIn(0, 100)

/**
 * Label for the TUNE button: the plain label when idle, "label countdown" while
 * the carrier is up (e.g. "TUNE 7s") so the operator sees the safety timeout
 * running. Extracted so it can be unit-tested without Compose.
 */
internal fun tuneChipLabel(label: String, isTuning: Boolean, remainingSec: Int): String =
    if (isTuning) "$label ${remainingSec.coerceAtLeast(0)}s" else label

// ---------------------------------------------------------------------------
// The sheet
// ---------------------------------------------------------------------------

/**
 * The Frequency sheet, opened from the header's dial chip: the five SSTV
 * calling frequencies, the TX level, and TUNE.
 *
 * TUNE lives here, two taps deep, on purpose. It used to be a chip on the TX
 * strip — permanently on screen on every tab, one stray thumb from keying a
 * steady carrier in the middle of somebody else's QSO. Behind a sheet it is
 * still two taps from the operator who wants it while setting up an antenna,
 * and unreachable by accident. It also disables Transmit while it runs, because
 * the tune carrier and an SSTV transmission both want the same rig.
 */
@Composable
fun FrequencyPickerSheet(
    visible: Boolean,
    currentFreqHz: Long,
    catStatusLabel: String,
    catDotColor: Color,
    showTxLevel: Boolean,
    txLevel: Int,
    isTuning: Boolean,
    tuneRemainingSec: Int,
    tuneMaxSeconds: Int,
    onDismiss: () -> Unit,
    onSelectBandIndex: (Int) -> Unit,
    onTxLevelChange: (Int) -> Unit,
    onTxLevelChangeFinished: () -> Unit,
    onToggleTune: () -> Unit,
) {
    SstvAfBottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Title + live rig/CAT status ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.freq_sheet_title),
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(catDotColor),
                    )
                    Text(
                        text = catStatusLabel,
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }

            // ---- The five calling frequencies ----
            // Re-read on each open rather than observing: excludedBands is a
            // plain set written by the Settings screen, and the only way to
            // change it is to leave this sheet.
            val rows = remember(visible) {
                visibleCallingFrequencies(SSTV_CALLING_FREQUENCIES, GeneralVariables.excludedBands)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (row in rows) {
                    CallingFrequencyRow(
                        row = row,
                        selected = isCallingFrequencySelected(row, currentFreqHz),
                        onClick = {
                            val exact = callingFrequencyBandIndex(
                                row.freqHz,
                                OperationBand.bandList.map { it.band },
                            )
                            // A dial missing from bands.txt (a hand-edited asset)
                            // still has to be tunable, so fall back to the helper
                            // that appends an entry for it.
                            onSelectBandIndex(
                                if (exact >= 0) exact else OperationBand.getIndexByFreq(row.freqHz),
                            )
                        },
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Border),
            )

            // ---- TX level ----
            // Gated on the operator's "Show TX volume slider" setting. The TX
            // strip that setting used to hide is gone, so without this the
            // toggle in Radio & audio persists a preference that changes
            // nothing. Hiding it here leaves the Settings slider as the way in,
            // which is what an operator who turned it off is asking for.
            if (showTxLevel) Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.freq_tx_level),
                    modifier = Modifier.width(74.dp),
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                IntSlider(
                    value = txLevel,
                    onValueChange = { onTxLevelChange(it.coerceIn(0, 100)) },
                    onValueChangeFinished = onTxLevelChangeFinished,
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                )
                Text(
                    text = stringResource(R.string.settings_percent_format, txLevel),
                    modifier = Modifier.width(36.dp),
                    color = TextFaint,
                    fontSize = 11.sp,
                    fontFamily = GeistMonoFamily,
                )
            }

            // ---- TUNE ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val tuneDescription = stringResource(R.string.tune_content_description)
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isTuning) StatusBad else BgSurface3)
                        .clickable(
                            onClickLabel = tuneDescription,
                            role = Role.Button,
                            onClick = onToggleTune,
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tuneChipLabel(
                            stringResource(R.string.tune_button), isTuning, tuneRemainingSec,
                        ),
                        color = if (isTuning) Color.White else TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistMonoFamily,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Text(
                    // The timeout is an operator setting (Transmission → Tune), so
                    // the copy reads the live value instead of hardcoding "10 s"
                    // and going stale the moment somebody changes it.
                    text = stringResource(R.string.freq_tune_explain, tuneMaxSeconds),
                    modifier = Modifier.weight(1f),
                    color = TextFaint,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

/** One calling-frequency row: band, dial, and when to sit on it. */
@Composable
private fun CallingFrequencyRow(
    row: SstvCallingFrequency,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Accent.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = row.band,
            modifier = Modifier.width(38.dp),
            color = TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = formatMhz(row.freqHz),
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistMonoFamily,
            )
            Text(
                text = stringResource(R.string.freq_unit_mhz),
                color = TextFaint,
                fontSize = 12.sp,
            )
        }
        Text(
            text = stringResource(row.noteRes),
            color = TextFaint,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}
