package radio.ks3ckc.sstvaf.ui.wefax

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.BgApp
import radio.ks3ckc.sstvaf.theme.BgSurface
import radio.ks3ckc.sstvaf.theme.BgSurface3
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.StatusConfirmed
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary
import radio.ks3ckc.sstvaf.ui.components.TopBar
import radio.ks3ckc.sstvaf.wefax.WefaxPreset
import radio.ks3ckc.sstvaf.wefax.WefaxRxState
import radio.ks3ckc.sstvaf.wefax.wefaxGrayToArgb

/**
 * The WEFAX (HF radiofax) receive screen, reached from the overflow sheet.
 *
 * A fax transmission is a continuous line stream with no length header, so
 * unlike the SSTV tab this surface is operator-driven: pick the line
 * rate/IOC (120/576 covers nearly every station on the air), press start,
 * and stop when the chart is done — the strip is then saved to the Gallery.
 * The live preview shows the newest [WEFAX_PREVIEW_ROWS] lines emerging the
 * way paper leaves a fax printer; decision logic lives in
 * WefaxScreenLogic.kt (unit tested), this file is the thin Compose wrapper.
 */
@Composable
fun WefaxScreen(mainViewModel: MainViewModel, onBack: () -> Unit) {
    val listener = mainViewModel.wefaxSignalListener
    val rxState by listener.rxState.observeAsState(WefaxRxState.Idle)
    val lastSaved by mainViewModel.wefaxAutoSaveController.lastSaved.observeAsState(null)

    var receiving by remember { mutableStateOf(listener.isReceiving()) }
    var preset by remember { mutableStateOf(WefaxPreset.DEFAULT) }

    // Live preview: a fixed-height rolling window over the strip's newest
    // rows. The buffer and row bookkeeping live across recompositions; the
    // ImageBitmap is only re-snapshotted when rows actually arrive.
    var previewWidth by remember { mutableIntStateOf(0) }
    var populated by remember { mutableIntStateOf(0) }
    var appliedRows by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    val previewBuffer = remember { mutableStateOf(IntArray(0)) }

    // React to each engine state publication (rowsReady grows -> new Decoding
    // value -> effect re-runs), same shape as RxScreen: cumulative appliedRows
    // bookkeeping means a skipped intermediate publication loses nothing.
    LaunchedEffect(rxState) {
        when (val s = rxState) {
            is WefaxRxState.Decoding -> {
                if (previewWidth != s.width) {
                    previewWidth = s.width
                    previewBuffer.value = IntArray(s.width * WEFAX_PREVIEW_ROWS)
                    populated = 0
                    appliedRows = 0
                    preview = null
                }
                if (s.rowsReady > appliedRows && s.width > 0) {
                    val n = s.rowsReady - appliedRows
                    val gray = ByteArray(n * s.width)
                    val copied = listener.readNewRows(appliedRows, n, gray)
                    if (copied > 0) {
                        populated = wefaxRollPreview(
                            previewBuffer.value, s.width, WEFAX_PREVIEW_ROWS, populated,
                            wefaxGrayToArgb(gray, copied * s.width), copied,
                        )
                        appliedRows += copied
                        preview = Bitmap.createBitmap(
                            previewBuffer.value, 0, s.width, s.width, populated,
                            Bitmap.Config.ARGB_8888,
                        ).asImageBitmap()
                    }
                }
            }

            is WefaxRxState.Idle -> {
                if (appliedRows != 0) {
                    populated = 0
                    appliedRows = 0
                    preview = null
                }
            }

            else -> Unit // Listening keeps the previous strip; Stopped too.
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgApp),
    ) {
        TopBar(
            title = stringResource(R.string.wefax_title),
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                WefaxPresetChip(
                    preset = preset,
                    enabled = wefaxPresetSelectable(receiving),
                    onSelect = { preset = it },
                )
                Button(
                    onClick = {
                        if (receiving) {
                            listener.stopReceiving()
                            receiving = false
                        } else {
                            mainViewModel.hamRecorder?.let { recorder ->
                                listener.startReceiving(recorder, preset)
                                receiving = listener.isReceiving()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (receiving) BgSurface3 else Accent,
                    ),
                ) {
                    Text(
                        text = stringResource(
                            if (receiving) R.string.wefax_stop else R.string.wefax_start,
                        ),
                        color = TextPrimary,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(previewAspect(previewWidth, populated))
                    .clip(RoundedCornerShape(8.dp))
                    .background(BgSurface),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = preview
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = stringResource(R.string.wefax_canvas_description),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                        // Fax lines are the data; smoothing them away hides
                        // exactly what the operator is tuning by.
                        filterQuality = FilterQuality.None,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.wefax_canvas_empty),
                        color = TextMuted,
                        fontSize = 12.sp,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(wefaxStatusLabelRes(rxState)),
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                wefaxRowsCount(rxState)?.let { rows ->
                    Text(
                        text = stringResource(R.string.wefax_rows_format, rows),
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontFamily = GeistMonoFamily,
                    )
                }
            }

            lastSaved?.let { saved ->
                Text(
                    text = stringResource(R.string.wefax_saved, saved.fileName),
                    color = StatusConfirmed,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/** Preview box aspect; a safe 4:3 placeholder before any rows exist. */
private fun previewAspect(width: Int, rows: Int): Float =
    if (width > 0 && rows > 0) width.toFloat() / rows else 4f / 3f

/** The LPM/IOC selector chip: "120/576" etc., disabled while receiving. */
@Composable
private fun WefaxPresetChip(
    preset: WefaxPreset,
    enabled: Boolean,
    onSelect: (WefaxPreset) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(BgSurface)
                .clickable(enabled = enabled, role = Role.Button) { menuOpen = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.wefax_preset_label),
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = preset.label,
                color = if (enabled) TextPrimary else TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistMonoFamily,
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            for (option in WefaxPreset.entries) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            color = if (option == preset) Accent else TextPrimary,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
