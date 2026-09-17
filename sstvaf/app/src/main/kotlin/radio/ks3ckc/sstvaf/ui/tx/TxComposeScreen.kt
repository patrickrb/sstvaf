package radio.ks3ckc.sstvaf.ui.tx

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.rigs.BaseRigOperation
import com.k1af.ft8af.transmit.PttController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import radio.ks3ckc.sstvaf.gallery.ImageDirection
import radio.ks3ckc.sstvaf.gallery.SavedImage
import radio.ks3ckc.sstvaf.sstv.CwId
import radio.ks3ckc.sstvaf.sstv.CwIdSettings
import radio.ks3ckc.sstvaf.sstv.SstvMode
import radio.ks3ckc.sstvaf.sstv.VoxPreTone
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.BgApp
import radio.ks3ckc.sstvaf.theme.BgSurface
import radio.ks3ckc.sstvaf.theme.BgSurface3
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.StatusWarn
import radio.ks3ckc.sstvaf.theme.TextFaint
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary
import radio.ks3ckc.sstvaf.ui.components.SstvAfIcons
import java.util.Locale
import radio.ks3ckc.sstvaf.sstv.TxOutcome
import radio.ks3ckc.sstvaf.sstv.TxImageWindow

/**
 * The TX composer tab: pick a photo, crop it into the selected SSTV mode's
 * frame (pinch/drag), stamp text overlays, then hand the rendered composite
 * to [MainViewModel.sstvTransmitter].
 *
 * Per project policy this file is a thin wrapper: every decision lives in
 * TxScreenLogic.kt / TxImageComposer.kt / TxComposition.kt, which carry the
 * unit tests.
 */
@Composable
fun TxComposeScreen(mainViewModel: MainViewModel) {
    val context = LocalContext.current

    // The composition and picked photo live in MainViewModel's TxComposerState,
    // NOT in remember{}: the tab shell fully disposes this screen on every tab
    // switch, and the operator's photo/crop/overlay setup must survive until
    // they replace or clear it themselves. The remember{} below runs once per
    // tab visit and (re-)seeds defaults only while the composition is untouched
    // — preserving the old pick-up-a-new-callsign behavior.
    val composerState = mainViewModel.txComposerState
    remember {
        composerState.refreshDefaults {
            defaultTxComposition(
                GeneralVariables.myCallsign,
                initialTxMode(GeneralVariables.sstvTxMode),
                GeneralVariables.getMyMaidenheadGrid() ?: "",
            )
        }
    }
    val composition = composerState.composition
        ?: return // unreachable: refreshDefaults above always seeds
    val sourceBitmap = composerState.sourceBitmap
    var showConfirmSheet by remember { mutableStateOf(false) }
    var showModeSheet by remember { mutableStateOf(false) }

    // The editor's own state: which tool is open, which overlay is selected,
    // and the pending brush settings. Held in remember{} rather than the
    // view-model-scoped composer state because these are not part of the
    // picture - the composition is what gets transmitted, this is just where
    // the operator's hands are (see TxEditorDraft).
    // The transmission just finished and the operator has not chosen what next.
    // Tracked here rather than derived from the transmitter, which goes back to
    // idle the instant the audio stops - there would be no state left to show
    // the confirmation from.
    // The outcome being shown over the canvas, or null for none. Replaces the
    // old boolean: a failure and a completed send must not look the same.
    var outcome by remember { mutableStateOf<TxOutcome?>(null) }
    var tool by remember { mutableStateOf(TxTool.CROP) }
    var selectedOverlayId by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf(TxEditorDraft()) }

    val isTransmitting by mainViewModel.sstvTransmitter.isTransmitting.observeAsState(false)
    val txProgress by mainViewModel.sstvTransmitter.txProgress.observeAsState(0f)
    val isTuning by mainViewModel.tuneOperator.mutableIsTuning.observeAsState(false)

    // A finished transmission raises its confirmation from the transmitter's
    // durable result, not from a screen-local edge on isTransmitting. The edge
    // was invisible if the operator was on another tab when the transmission
    // ended (this screen is not composed then), and it could not tell a
    // completed image from a failure - so a failed transmission showed the
    // green "Sent" scrim. The sequence number is kept in the composer state,
    // which outlives the tab, so the result is shown exactly once.
    val txResult by mainViewModel.sstvTransmitter.lastResult.observeAsState()
    val imageWindow by mainViewModel.sstvTransmitter.imageWindow
        .observeAsState(TxImageWindow.WHOLE)
    LaunchedEffect(txResult, isTransmitting) {
        val result = txResult
        if (!isTransmitting && result != null &&
            result.sequence > composerState.lastSeenTxSequence
        ) {
            composerState.lastSeenTxSequence = result.sequence
            outcome = result.outcome
        }
    }

    // Keyed, or showing an outcome: either way the editor is read-only.
    val controlsEnabled = editorControlsEnabled(
        transmitting = isTransmitting,
        showingOutcome = outcome != null,
    )

    // Load a picked/captured image into the composer state (resets the crop,
    // recycles the photo it replaces). Shared by the photo picker and the
    // camera capture below.
    val applyImageUri: (Uri) -> Unit = { uri ->
        val bitmap = loadSourceBitmap(
            context.contentResolver, uri,
            composition.mode.width, composition.mode.height,
        )
        if (bitmap != null) {
            composerState.setImage(bitmap, uri.toString())
        }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) applyImageUri(uri) }

    // The URI the camera app is writing the in-flight capture into; read back
    // by the TakePicture callback (the contract only reports success/failure,
    // not the target). Cleared once consumed.
var pendingCaptureUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        pendingCaptureUri?.let { uri -> if (success) applyImageUri(uri) }
        pendingCaptureUri = null
    }
    val launchCamera: () -> Unit = {
        val file = cameraCaptureFile(context.cacheDir, System.currentTimeMillis())
        val uri = cameraCaptureUri(context, file)
        pendingCaptureUri = uri
        takePicture.launch(uri)
    }

    // The live preview composite, re-rendered on every edit. The bitmaps are
    // mode-sized (tiny), so this is cheap enough for the UI thread.
    val preview = remember(composition, sourceBitmap) {
        sourceBitmap?.let { src ->
            renderComposite(src, composition, composition.mode.width, composition.mode.height)
        }
    }

    val gate = transmitGate(
        hasImage = preview != null,
        isTransmitting = isTransmitting,
        tuneActive = isTuning,
    )

    // Airtime the optional CW station-ID tail adds after the image (issue #14),
    // so the confirm sheet and progress readout report the true on-air duration
    // rather than the image-only mode length. Read from GeneralVariables (the
    // same source SstvTransmitter uses); 0 when the ID is off or unkeyable.
    val cwTailSeconds = CwId.tailDurationSeconds(
        CwIdSettings(
            enabled = GeneralVariables.cwIdEnabled,
            text = GeneralVariables.myCallsign,
            wpm = GeneralVariables.cwIdWpm,
        ),
        GeneralVariables.audioSampleRate,
    )

    // Airtime the VOX pre-tone prepends in VOX control mode (sacrificial
    // leader for VOX/auto-PTT-cable attack time — see VoxPreTone); 0 whenever
    // a rig is keyed explicitly, mirroring SstvTransmitter's gating.
    val voxPreToneSeconds =
        if (PttController.controlsPtt(GeneralVariables.controlMode)) {
            0.0
        } else {
            VoxPreTone.durationSeconds(
                GeneralVariables.voxPreToneMs,
                GeneralVariables.audioSampleRate,
            )
        }

    // Selecting an overlay pulls its settings into the draft, so the panel's
    // controls show that overlay's colour/size/style and changing one edits it.
    val selectedOverlay = composition.overlays.firstOrNull { it.id == selectedOverlayId }

    // "Last sent" - the most recent transmitted picture, for the empty state.
    val store = mainViewModel.receivedImageStore
    var lastSent by remember { mutableStateOf<SavedImage?>(null) }
    LaunchedEffect(preview == null) {
        if (preview == null) {
            lastSent = withContext(Dispatchers.IO) {
                store.list().firstOrNull { it.direction == ImageDirection.TX }
            }
        }
    }

    // Locale.ROOT, not the default locale: a callsign is a protocol
    // identifier, and on a Turkish-locale device the default uppercase() turns
    // an ASCII "i" into a dotted capital I, which is not the station that is
    // transmitting. The composition helpers already normalise this way.
    val callsign = GeneralVariables.myCallsign.orEmpty().trim().uppercase(Locale.ROOT)
    val grid = GeneralVariables.getMyMaidenheadGrid().orEmpty()

    /** Load a text-only card: a generated gradient plus its starting overlays. */
    val loadCard: (TxCardKind) -> Unit = { kind ->
        val bitmap = buildCardBitmap(kind, composition.mode.width, composition.mode.height)
        composerState.setImage(bitmap, "card:" + kind.name)
        composerState.composition = composerState.composition?.copy(
            overlays = when (kind) {
                TxCardKind.CQ -> cqCardOverlays(callsign)
                TxCardKind.GRID -> gridCardOverlays(callsign, grid)
            },
            paths = emptyList(),
            adjustments = ImageAdjustments(),
            frame = ImageFrame.NONE,
        )
        // Straight to Text: a card is text, so that is the next thing the
        // operator will want to change. The draft adopts the card's own style so
        // the next overlay they add matches the ones already on it - otherwise
        // typing onto a card of outlined text produces a barred line that looks
        // like it belongs to a different picture.
        tool = TxTool.TEXT
        selectedOverlayId = null
        draft = when (kind) {
            TxCardKind.CQ -> draft.copy(
                colorArgb = OVERLAY_COLOR_CYAN,
                sizeFraction = OVERLAY_SIZE_LARGE,
                style = OverlayStyle.OUTLINE,
            )
            TxCardKind.GRID -> draft.copy(
                colorArgb = OVERLAY_COLOR_WHITE,
                sizeFraction = OVERLAY_SIZE_MEDIUM,
                style = OverlayStyle.BAR,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgApp)
            .padding(horizontal = 16.dp)
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Title comes from the app shell's header ([AppHeader]).

        if (preview == null) {
            TxEmptyState(
                callsign = callsign.ifEmpty { stringResource(R.string.op_no_call) },
                grid = grid,
                lastSent = lastSent,
                lastSentFile = lastSent?.let { store.imageFile(it) },
                lastSentCaption = lastSent?.let {
                    lastSentCaption(it, stringResource(R.string.tx_last_sent_note))
                }.orEmpty(),
                onChoosePhoto = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onTakePhoto = launchCamera,
                onCqCard = { loadCard(TxCardKind.CQ) },
                onGridCard = { loadCard(TxCardKind.GRID) },
                onLastSent = {
                    val entry = lastSent
                    if (entry != null) {
                        val bitmap = loadSavedBitmap(store.imageFile(entry))
                        if (bitmap != null) {
                            composerState.setImage(bitmap, store.imageFile(entry).toString())
                            tool = TxTool.TEXT
                            selectedOverlayId = null
                        }
                    }
                },
            )
            Box(modifier = Modifier.weight(1f))
        } else {
            TxEditorCanvas(
                preview = preview,
                composition = composition,
                tool = tool,
                selectedOverlayId = selectedOverlayId,
                editable = controlsEnabled,
                transmitting = isTransmitting,
                transmitProgress = txProgress,
                outcome = outcome,
                imageWindow = imageWindow,
                onEditAgain = {
                    // Everything survives: the composition was never cleared,
                    // so this is just dismissing the confirmation.
                    outcome = null
                },
                onNewPicture = {
                    outcome = null
                    composerState.clearImage()
                    selectedOverlayId = null
                    tool = TxTool.CROP
                },
                // Every gesture callback below reads composerState.composition
                // rather than the `composition` captured by this composition
                // pass. The canvas gesture coroutine is not restarted for these
                // edits, so it keeps calling the callback instances it was
                // launched with; computing from a captured value made each event
                // start again from the same state and overwrite the last step
                // instead of accumulating - a whole pan gesture collapsed to its
                // final event, and a freehand stroke to a single dot.
                onPanBy = { dxFraction, dyFraction ->
                    composerState.composition?.let { live ->
                        composerState.composition = live.copy(
                            panX = panStep(live.panX, dxFraction, live.zoom),
                            panY = panStep(live.panY, dyFraction, live.zoom),
                        )
                    }
                },
                onZoomBy = { scale ->
                    composerState.composition?.let { live ->
                        composerState.composition = live.withClampedView(zoom = live.zoom * scale)
                    }
                },
                onOverlayTouched = { id ->
                    selectedOverlayId = id
                    composerState.composition?.overlays
                        ?.firstOrNull { it.id == id }
                        ?.let { draft = draft.matching(it) }
                    // The callsign stamp belongs to the Callsign tool; anything
                    // else to Text. Switching tool on touch means the controls
                    // for the thing just grabbed are already on screen.
                    tool = if (id == CALLSIGN_OVERLAY_ID) TxTool.CALLSIGN else TxTool.TEXT
                },
                onOverlayMovedTo = { id, x, y ->
                    composerState.composition =
                        composerState.composition?.withOverlayMoved(id, x, y)
                },
                onDeselect = { selectedOverlayId = null },
                onStrokeStart = { x, y ->
                    composerState.composition = composerState.composition?.withStrokeStarted(
                        draft.colorArgb, draft.strokeWidth, PathPoint(x, y),
                    )
                },
                onStrokeExtend = { x, y ->
                    composerState.composition =
                        composerState.composition?.withStrokeExtended(PathPoint(x, y))
                },
                onDeleteSelected = {
                    selectedOverlayId?.let {
                        composerState.composition =
                            composerState.composition?.withOverlayRemoved(it)
                    }
                    selectedOverlayId = null
                },
                onChangePhoto = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onClearImage = {
                    // Back to the four-way empty state, so the CQ card, grid
                    // card and Last sent are reachable again.
                    composerState.clearImage()
                    selectedOverlayId = null
                    tool = TxTool.CROP
                },
            )

            TxToolRail(
                active = tool,
                enabled = controlsEnabled,
                onSelect = { picked ->
                    tool = picked
                    // Leaving the text tools drops the selection: a dashed
                    // outline with no controls to act on it is just clutter.
                    if (picked != TxTool.TEXT && picked != TxTool.CALLSIGN) {
                        selectedOverlayId = null
                    }
                },
            )

            TxToolPanel(
                enabled = controlsEnabled,
                tool = tool,
                composition = composition,
                draft = draft,
                selectedOverlay = selectedOverlay,
                callsign = callsign.ifEmpty { stringResource(R.string.op_no_call) },
                callsignSet = callsign.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onZoomChange = { composerState.composition = composition.withClampedView(zoom = it) },
                onResetCrop = {
                    composerState.composition = composition.copy(zoom = 1f, panX = 0f, panY = 0f)
                },
                onFillFrame = {
                    composerState.composition = composition.withClampedView(zoom = FILL_FRAME_ZOOM)
                },
                onDraftTextChange = { text ->
                    draft = draft.copy(text = text)
                    // Typing with an overlay selected edits it live, so the
                    // operator sees the text land on the picture as they type.
                    val id = selectedOverlayId
                    if (id != null) {
                        composerState.composition =
                            composition.withOverlayPatched(id) { it.copy(text = text) }
                    }
                },
                onCommitText = {
                    if (selectedOverlayId != null) {
                        // Committing with a selection just ends the edit.
                        selectedOverlayId = null
                        draft = draft.cleared()
                    } else if (draft.text.isNotBlank()) {
                        val id = composition.nextOverlayId()
                        composerState.composition = composition.withOverlayStamped(
                            TextOverlay(
                                id = id,
                                text = draft.text.trim(),
                                xPercent = 50f,
                                yPercent = 50f,
                                colorArgb = draft.colorArgb,
                                sizeFraction = draft.sizeFraction,
                                style = draft.style,
                            ),
                        )
                        selectedOverlayId = id
                    }
                },
                onColorPick = { color ->
                    draft = draft.copy(colorArgb = color)
                    selectedOverlayId?.let { id ->
                        composerState.composition =
                            composition.withOverlayPatched(id) { it.copy(colorArgb = color) }
                    }
                },
                onSizePick = { size ->
                    draft = draft.copy(sizeFraction = size)
                    selectedOverlayId?.let { id ->
                        composerState.composition =
                            composition.withOverlayPatched(id) { it.copy(sizeFraction = size) }
                    }
                },
                onStylePick = { style ->
                    draft = draft.copy(style = style)
                    selectedOverlayId?.let { id ->
                        composerState.composition =
                            composition.withOverlayPatched(id) { it.copy(style = style) }
                    }
                },
                onStampCallsign = { preset ->
                    composerState.composition = composition.withOverlayStamped(
                        TextOverlay(
                            id = CALLSIGN_OVERLAY_ID,
                            text = callsign,
                            xPercent = preset.xPercent,
                            yPercent = preset.yPercent,
                            colorArgb = draft.colorArgb,
                            sizeFraction = draft.sizeFraction,
                            // The preset decides the style: a centre stamp is a
                            // full-width bar, a corner stamp needs a halo.
                            style = preset.style,
                        ),
                    )
                    draft = draft.copy(style = preset.style)
                    selectedOverlayId = CALLSIGN_OVERLAY_ID
                },
                onFramePick = { composerState.composition = composition.withFrame(it) },
                onAdjustmentsChange = { composerState.composition = composition.withAdjustments(it) },
                onResetAdjustments = {
                    composerState.composition = composition.withAdjustmentsReset()
                },
                onStrokeWidthPick = { draft = draft.copy(strokeWidth = it) },
                onUndoStroke = { composerState.composition = composition.withStrokeUndone() },
                onClearStrokes = { composerState.composition = composition.withStrokesCleared() },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The mode card steps aside while keyed: the amber panel needs the
            // width, and the mode is fixed for the duration of a transmission
            // anyway - it is encoded into the audio already playing.
            if (!isTransmitting && outcome == null) {
                ModeCard(
                    mode = composition.mode,
                    enabled = true,
                    onClick = { showModeSheet = true },
                    modifier = Modifier.width(118.dp),
                )
            }
            if (isTransmitting) {
                TxTransmitPanel(
                    mode = composition.mode,
                    progress = txProgress,
                    totalSeconds = totalTxDurationSeconds(
                        composition.mode, cwTailSeconds, voxPreToneSeconds,
                    ),
                    onCancel = { mainViewModel.sstvTransmitter.cancel() },
                    modifier = Modifier.weight(1f),
                )
            } else if (outcome == null) {
                TransmitButton(
                    gate = gate,
                    durationLabel = modeDurationLabel(composition.mode),
                    onClick = { showConfirmSheet = true },
                    modifier = Modifier.weight(1f),
                )
            } else {
                // The scrim owns the next action while an outcome is up. A live
                // Transmit button underneath it would key the rig with the
                // success overlay still on screen.
                Box(modifier = Modifier.weight(1f))
            }
        }
    }

    ModeSheet(
        visible = showModeSheet,
        selected = composition.mode,
        onDismiss = { showModeSheet = false },
        onSelect = { mode ->
            showModeSheet = false
            composerState.composition = composition.copy(mode = mode)
            // Persist so the composer opens on the operator's last choice
            // instead of resetting to Scottie 1 every launch.
            GeneralVariables.sstvTxMode = mode.name
            mainViewModel.databaseOpr.writeConfig("sstvTxMode", mode.name, null)
        },
    )

    TxConfirmSheet(
        visible = showConfirmSheet,
        mode = composition.mode,
        preview = preview,
        txLevelPercent = (GeneralVariables.volumePercent * 100).toInt(),
        bandLabel = BaseRigOperation.getMeterFromFreq(GeneralVariables.band).orEmpty(),
        cwTailSeconds = cwTailSeconds,
        voxPreToneSeconds = voxPreToneSeconds,
        onDismiss = { showConfirmSheet = false },
        onConfirm = {
            showConfirmSheet = false
            val composite = preview ?: return@TxConfirmSheet
            val pixels = IntArray(composite.width * composite.height)
            composite.getPixels(pixels, 0, composite.width, 0, 0, composite.width, composite.height)
            performTransmit(
                pixels = pixels,
                width = composite.width,
                height = composite.height,
                mode = composition.mode,
                freqHz = GeneralVariables.band,
                utcMillis = System.currentTimeMillis(),
                starter = { p, w, h, m -> mainViewModel.sstvTransmitter.transmit(p, w, h, m) },
                saver = { p, w, h, m, utc, freq ->
                    mainViewModel.receivedImageStore.save(
                        p, w, h, m, utc, freq,
                        ImageDirection.TX, complete = true, quality = 1f,
                    )
                },
                log = { GeneralVariables.fileLog(it) },
            )
        },
    )
}

// ---------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------

/**
 * The Transmit button: accent, 52dp tall, with the mode's duration beside the
 * label.
 *
 * The duration is on the button because that is the moment it matters. Tapping
 * this commits the frequency for that long, and an operator about to send a
 * four-minute Scottie DX should see the number before they tap, not after.
 *
 * Disabled with no picture or while TUNE holds the rig, with the reason stated
 * underneath rather than left for the operator to work out.
 */
@Composable
private fun TransmitButton(
    gate: TxGate,
    durationLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = gate == TxGate.READY
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (enabled) Accent else BgSurface3)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            SstvAfIcons.Transmit(
                size = 18.dp,
                color = if (enabled) BgApp else TextFaint,
                strokeWidth = 2f,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.tx_transmit_action),
                color = if (enabled) BgApp else TextFaint,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = durationLabel,
                color = (if (enabled) BgApp else TextFaint).copy(alpha = 0.75f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = GeistMonoFamily,
            )
        }
        val reason = when (gate) {
            TxGate.READY, TxGate.TRANSMITTING -> null
            TxGate.NO_IMAGE -> stringResource(R.string.tx_gate_no_image)
            TxGate.TUNE_ACTIVE -> stringResource(R.string.tx_gate_tune_active)
        }
        if (reason != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = reason,
                modifier = Modifier.fillMaxWidth(),
                color = TextMuted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}


