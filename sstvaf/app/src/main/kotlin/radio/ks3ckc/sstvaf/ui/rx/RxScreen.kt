package radio.ks3ckc.sstvaf.ui.rx

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.sstv.LastDecodedImage
import radio.ks3ckc.sstvaf.sstv.SstvRxState
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.BgApp
import radio.ks3ckc.sstvaf.theme.BgSurface
import radio.ks3ckc.sstvaf.theme.BgSurface3
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.Signal
import radio.ks3ckc.sstvaf.theme.StatusConfirmed
import radio.ks3ckc.sstvaf.theme.StatusWarn
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary
import radio.ks3ckc.sstvaf.ui.components.EmptyStateWaves
import radio.ks3ckc.sstvaf.ui.components.Toggle
import radio.ks3ckc.sstvaf.ui.components.TopBar

/**
 * The SSTV receive tab: watches [SstvRxState] and shows the image forming
 * line-by-line. All decision/formatting logic lives in RxScreenLogic.kt (unit
 * tested); this file is the thin Compose wrapper.
 *
 * Live image flow: each [SstvRxState.Decoding] publication (rowsReady grows)
 * pulls the new rows out of the engine ([MainViewModel.sstvSignalListener]'s
 * `readNewRows`) into the [RxImageAssembler]'s bitmap and swaps an immutable
 * snapshot into Compose state. Terminal states render the [LastDecodedImage]
 * snapshot instead (the live session has already been reset by then).
 */
@Composable
fun RxScreen(
    mainViewModel: MainViewModel,
    onViewInGallery: () -> Unit = {},
) {
    val listener = mainViewModel.sstvSignalListener
    val rxState by listener.rxState.observeAsState(SstvRxState.Idle)

    var rxEnabled by remember { mutableStateOf(listener.isEnabled()) }

    // Dial frequency label — recomposes when the user retunes.
    @Suppress("UNUSED_VARIABLE")
    val bandIndex by GeneralVariables.mutableBandChange.observeAsState(GeneralVariables.bandListIndex)
    val frequencyLabel = formatDialFrequency(GeneralVariables.band)

    val assembler = remember { RxImageAssembler() }
    var appliedRows by remember { mutableIntStateOf(0) }
    var liveImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var liveAspect by remember { mutableStateOf(4f / 3f) }

    // React to each engine state publication (rowsReady grows -> new Decoding
    // value -> effect re-runs). Row bookkeeping is cumulative (appliedRows), so
    // a skipped intermediate state loses nothing: the next run reads every row
    // from appliedRows up to the latest rowsReady.
    LaunchedEffect(rxState) {
        when (val s = rxState) {
            is SstvRxState.Decoding -> {
                if (assembler.ensureSize(s.mode.width, s.mode.height)) {
                    appliedRows = 0
                    liveAspect = s.mode.width.toFloat() / s.mode.height
                    liveImage = assembler.snapshotBitmap()?.asImageBitmap()
                }
                if (s.rowsReady > appliedRows) {
                    val n = s.rowsReady - appliedRows
                    val buf = IntArray(n * s.mode.width)
                    val copied = listener.readNewRows(appliedRows, n, buf)
                    if (copied > 0) {
                        assembler.applyRows(appliedRows, copied, buf)
                        appliedRows += copied
                        liveImage = assembler.snapshotBitmap()?.asImageBitmap()
                    }
                }
            }

            is SstvRxState.Complete, is SstvRxState.Aborted -> {
                // Render the finished/partial frame from the snapshot holder;
                // the live decoder session was reset when the terminal state
                // published.
                val frame = LastDecodedImage.frame
                if (frame != null && appliedRows != -1) {
                    liveImage = Bitmap.createBitmap(
                        frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888,
                    ).asImageBitmap()
                    liveAspect = frame.width.toFloat() / frame.height
                    assembler.reset()
                    appliedRows = -1 // sentinel: terminal frame already rendered
                }
            }

            else -> {
                // Hunting/leader: clear any leftover canvas from the previous image.
                if (appliedRows != 0) {
                    assembler.reset()
                    appliedRows = 0
                    liveImage = null
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgApp),
    ) {
        TopBar(title = stringResource(R.string.rx_title)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = frequencyLabel,
                    color = Signal,
                    fontFamily = GeistMonoFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.rx_toggle_label),
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Toggle(
                    checked = rxEnabled,
                    onCheckedChange = { on ->
                        rxEnabled = on
                        listener.setEnabled(on)
                    },
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when (rxRenderKind(rxState)) {
                RxRenderKind.LISTENING -> RxEmptyState(rxEnabled, frequencyLabel)

                RxRenderKind.SIGNAL_DETECTED -> RxSignalDetected()

                RxRenderKind.DECODING -> {
                    val s = rxState as SstvRxState.Decoding
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RxImageView(liveImage, liveAspect, modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.height(12.dp))
                        RxStatusStrip(
                            modeName = s.mode.displayName,
                            rowsReady = s.rowsReady,
                            totalRows = s.totalRows,
                            quality = s.quality,
                            slantPpm = s.slantPpm,
                            etaLabel = rxEtaLabel(s.mode, s.rowsReady, s.totalRows),
                        )
                    }
                }

                RxRenderKind.COMPLETE -> {
                    val s = rxState as SstvRxState.Complete
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RxImageView(liveImage, liveAspect, modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = s.mode.displayName,
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            SavedChip()
                            Spacer(modifier = Modifier.width(10.dp))
                            // Jumps to the Gallery tab, where the just-saved
                            // image is the newest cell.
                            Text(
                                text = stringResource(R.string.rx_view_in_gallery),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable(onClick = onViewInGallery)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                color = Accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                RxRenderKind.ABORTED -> {
                    val s = rxState as SstvRxState.Aborted
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (showsPartialImage(s) && liveImage != null) {
                            RxImageView(
                                liveImage, liveAspect,
                                modifier = Modifier.weight(1f).alpha(0.45f),
                            )
                        } else {
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                EmptyStateWaves()
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.rx_signal_lost),
                            color = StatusWarn,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

/** Empty/hunting state: waves + "Listening for SSTV…" (or "off") + frequency. */
@Composable
private fun RxEmptyState(rxEnabled: Boolean, frequencyLabel: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyStateWaves()
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(if (rxEnabled) R.string.rx_listening else R.string.rx_disabled),
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = frequencyLabel,
            color = TextMuted,
            fontFamily = GeistMonoFamily,
            fontSize = 12.sp,
        )
    }
}

/** Leader/VIS heard: a transmission may be starting. */
@Composable
private fun RxSignalDetected() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyStateWaves(accent = Accent)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.rx_signal_detected),
            color = Accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * The (forming or finished) image: nearest-neighbor upscale, aspect preserved,
 * letterboxed inside the available space.
 */
@Composable
private fun RxImageView(image: ImageBitmap?, aspect: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .clip(RoundedCornerShape(6.dp))
                    .background(BgSurface),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
        }
    }
}

/** Status strip under the forming image: mode, progress, ETA, quality, slant. */
@Composable
private fun RxStatusStrip(
    modeName: String,
    rowsReady: Int,
    totalRows: Int,
    quality: Float,
    slantPpm: Float,
    etaLabel: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(BgSurface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = modeName,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = stringResource(R.string.rx_progress_format, rxProgressPercent(rowsReady, totalRows)),
            color = Accent,
            fontFamily = GeistMonoFamily,
            fontSize = 12.sp,
        )

        Spacer(modifier = Modifier.width(6.dp))

        // Estimated time until the picture completes (replaces the raw row
        // count, which duplicated the percentage). Row bookkeeping still drives
        // both readouts; this one is the operator-facing countdown.
        Text(
            text = etaLabel,
            color = TextMuted,
            fontFamily = GeistMonoFamily,
            fontSize = 10.sp,
        )

        Spacer(modifier = Modifier.weight(1f))

        // Quality meter: label + small filled bar.
        Text(
            text = stringResource(R.string.rx_quality_label),
            color = TextMuted,
            fontSize = 9.sp,
        )
        Spacer(modifier = Modifier.width(4.dp))
        QualityMeter(fraction = rxQualityFraction(quality))

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = stringResource(R.string.rx_slant_format, formatSlantPpm(slantPpm)),
            color = TextMuted,
            fontFamily = GeistMonoFamily,
            fontSize = 9.sp,
        )
    }
}

/** Tiny horizontal bar, filled [fraction] of its width. */
@Composable
private fun QualityMeter(fraction: Float) {
    Box(
        modifier = Modifier
            .width(46.dp)
            .height(5.dp)
            .clip(RoundedCornerShape(2.5.dp))
            .background(BgSurface3),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(5.dp)
                .clip(RoundedCornerShape(2.5.dp))
                .background(Accent),
        )
    }
}

/** "Saved" confirmation chip shown once the completed image was auto-saved. */
@Composable
private fun SavedChip() {
    Text(
        text = stringResource(R.string.rx_saved_chip),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(BgSurface3)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = StatusConfirmed,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
    )
}
