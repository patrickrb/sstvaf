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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.database.OperationBand
import com.k1af.ft8af.rigs.BaseRigOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import radio.ks3ckc.sstvaf.gallery.ImageDirection
import radio.ks3ckc.sstvaf.gallery.SavedImage
import radio.ks3ckc.sstvaf.sstv.LastDecodedImage
import radio.ks3ckc.sstvaf.sstv.SstvRxState
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.BgApp
import radio.ks3ckc.sstvaf.theme.BgSurface
import radio.ks3ckc.sstvaf.theme.BgSurface3
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.StatusConfirmed
import radio.ks3ckc.sstvaf.theme.StatusWarn
import radio.ks3ckc.sstvaf.theme.TextFaint
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary
import radio.ks3ckc.sstvaf.ui.components.EmptyStateWaves
import radio.ks3ckc.sstvaf.ui.components.Toggle

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

    // Dial frequency label — recomposes when the user retunes. The band name is
    // resolved the same three-tier way as the app-shell TX strip pill (list
    // index → exact-frequency match → rig helper) so both readouts agree.
    val bandIndex by GeneralVariables.mutableBandChange.observeAsState(GeneralVariables.bandListIndex)
    val freq = GeneralVariables.band
    val bandName = OperationBand.bandList.getOrNull(bandIndex)?.waveLength
        ?: OperationBand.bandList.firstOrNull { it.band == freq }?.waveLength
        ?: BaseRigOperation.getMeterFromFreq(freq)
    val frequencyLabel = formatDialFrequencyWithBand(freq, bandName)

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

    // Today's received images for the strip at the bottom. Reloaded whenever a
    // decode completes (rxState turning Complete is the signal) rather than on
    // a timer, so a picture that just landed is there when the operator looks.
    val store = mainViewModel.receivedImageStore
    var recent by remember { mutableStateOf<List<SavedImage>>(emptyList()) }
    val completedCount = (rxState as? SstvRxState.Complete)?.let { 1 } ?: 0
    LaunchedEffect(completedCount, rxState is SstvRxState.Complete) {
        recent = withContext(Dispatchers.IO) {
            store.list()
                .filter { it.direction == ImageDirection.RX }
                .take(RX_RECENT_LIMIT)
        }
    }

    val statusKind = rxStatusKind(rxState)
    val completedMode = (rxState as? SstvRxState.Complete)?.mode?.displayName

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgApp)
            .padding(horizontal = 16.dp)
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The receive switch. The design has no such control — it assumes
        // receive simply runs — but the app has always let an operator stop the
        // decoder, and silently removing the off switch would be a behaviour
        // change hiding inside a visual one. It sits above the canvas, small,
        // rather than in the shell's header, which has no business owning it.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
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

        RxCanvas(
            image = liveImage,
            aspect = liveAspect,
            revealFraction = rxRevealFraction(rxState),
            showsImage = rxShowsCanvasImage(rxState),
            listening = rxRenderKind(rxState) != RxRenderKind.DECODING,
            receiveEnabled = rxEnabled,
            frequencyLabel = frequencyLabel,
            completedModeName = completedMode,
        )

        RxStatusCard(
            statusLabel = rxStatusLabel(rxState),
            rightLabel = rxStatusRightLabel(rxState)
                ?: stringResource(R.string.rx_status_auto_detect).takeIf {
                    statusKind == RxStatusKind.LISTENING
                },
            progress = rxProgressFraction(rxState),
            rowsLabel = rxRowsLabel(rxState),
            qualityLabel = stringResource(R.string.rx_quality_value, rxQualityLabel(rxState)),
            slantLabel = rxSlantLabel(rxState),
            kind = statusKind,
        )

        RxRecentStrip(
            images = recent,
            imageFile = { store.imageFile(it) },
            onViewAll = onViewInGallery,
            onOpen = { onViewInGallery() },
        )
    }
}

/**
 * "Received today": a horizontal row of up to [RX_RECENT_LIMIT] thumbnails with
 * an "All" link into the Gallery.
 *
 * A glance, not a browser. It answers the question an operator has when they
 * pick the phone back up — "did anything come in?" — and hands off to the
 * Gallery for anything more. Tapping a thumbnail goes to the Gallery too rather
 * than opening a viewer here: one place that shows a saved picture properly is
 * better than two that disagree.
 */
@Composable
private fun RxRecentStrip(
    images: List<SavedImage>,
    imageFile: (SavedImage) -> java.io.File,
    onViewAll: () -> Unit,
    onOpen: (SavedImage) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.rx_received_today),
                color = TextFaint,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.06.sp,
            )
            if (images.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.rx_received_all),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(role = Role.Button, onClick = onViewAll)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (images.isEmpty()) {
            Text(
                text = stringResource(R.string.rx_received_none),
                modifier = Modifier.padding(horizontal = 2.dp),
                color = TextFaint,
                fontSize = 11.sp,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (entry in images) {
                    Column(
                        modifier = Modifier
                            .width(84.dp)
                            .clickable(role = Role.Button) { onOpen(entry) },
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        AsyncImage(
                            model = imageFile(entry),
                            contentDescription = entry.fileName,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(4f / 3f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(BgSurface),
                            contentScale = ContentScale.Crop,
                        )
                        Text(
                            text = rxRecentCaption(entry),
                            color = TextMuted,
                            fontSize = 10.sp,
                            fontFamily = GeistMonoFamily,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The status card's label, resolved. A thin wrapper over [rxStatusLabelRes] so
 * the decoding case gets its mode-name argument and the others do not.
 */
@Composable
private fun rxStatusLabel(state: SstvRxState): String {
    val res = rxStatusLabelRes(state)
    return if (rxStatusLabelTakesMode(state)) {
        val mode = (state as? SstvRxState.Decoding)?.mode?.displayName.orEmpty()
        stringResource(res, mode)
    } else {
        stringResource(res)
    }
}
