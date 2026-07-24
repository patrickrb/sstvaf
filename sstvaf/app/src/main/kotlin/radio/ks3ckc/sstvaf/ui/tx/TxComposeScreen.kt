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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.gallery.ImageDirection
import radio.ks3ckc.sstvaf.sstv.SstvMode
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.BgApp
import radio.ks3ckc.sstvaf.theme.BgSurface
import radio.ks3ckc.sstvaf.theme.BgSurface3
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.StatusWarn
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary
import radio.ks3ckc.sstvaf.ui.components.TopBar

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

    var composition by remember {
        mutableStateOf(
            defaultTxComposition(
                GeneralVariables.myCallsign,
                initialTxMode(GeneralVariables.sstvTxMode),
            ),
        )
    }
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // Release the previous source's pixels when a new photo replaces it and
    // when the screen leaves composition. Sources are downsampled (~2x the
    // mode frame at most) but on pre-O devices their pixels live in native
    // memory the GC can't see, so dispose deterministically. Safe ordering:
    // onDispose for the old key runs after recomposition, by which point the
    // preview has already been re-rendered from the new source.
    DisposableEffect(sourceBitmap) {
        val owned = sourceBitmap
        onDispose { owned?.recycle() }
    }
    var showConfirmSheet by remember { mutableStateOf(false) }
    // Index into composition.overlays being edited, or -1 for a new overlay;
    // null = editor closed.
    var editingOverlay by remember { mutableStateOf<Int?>(null) }

    val isTransmitting by mainViewModel.sstvTransmitter.isTransmitting.observeAsState(false)
    val txProgress by mainViewModel.sstvTransmitter.txProgress.observeAsState(0f)
    val isTuning by mainViewModel.tuneOperator.mutableIsTuning.observeAsState(false)

    // Load a picked/captured image into the source bitmap and reset the crop.
    // Shared by the photo picker and the camera capture below.
    val applyImageUri: (Uri) -> Unit = { uri ->
        val bitmap = loadSourceBitmap(
            context.contentResolver, uri,
            composition.mode.width, composition.mode.height,
        )
        if (bitmap != null) {
            sourceBitmap = bitmap
            composition = composition.copy(
                sourceUri = uri.toString(), zoom = 1f, panX = 0f, panY = 0f,
            )
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgApp),
    ) {
        TopBar(title = stringResource(R.string.tx_title))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))

            TxPreviewFrame(
                preview = preview,
                mode = composition.mode,
                gesturesEnabled = !isTransmitting,
                onPickImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onTakePhoto = launchCamera,
                onGesture = { panDx, panDy, zoomFactor, previewW, previewH ->
                    val src = sourceBitmap ?: return@TxPreviewFrame
                    composition = applyPanZoomGesture(
                        composition, src.width, src.height,
                        previewW, previewH, panDx, panDy, zoomFactor,
                    )
                },
            )

            Spacer(Modifier.height(12.dp))

            ModeChipRow(
                selected = composition.mode,
                enabled = !isTransmitting,
                onSelect = { mode ->
                    composition = composition.copy(mode = mode)
                    GeneralVariables.sstvTxMode = mode.name
                    mainViewModel.databaseOpr.writeConfig("sstvTxMode", mode.name, null)
                },
            )

            Spacer(Modifier.height(12.dp))

            OverlayList(
                overlays = composition.overlays,
                enabled = !isTransmitting,
                onEdit = { index -> editingOverlay = index },
                onAdd = { editingOverlay = -1 },
                onRemove = { index -> composition = composition.withOverlayRemoved(index) },
            )

            Spacer(Modifier.height(16.dp))

            if (isTransmitting) {
                TxProgressPanel(
                    progress = txProgress,
                    mode = composition.mode,
                    onCancel = { mainViewModel.sstvTransmitter.cancel() },
                )
            } else {
                TransmitButton(
                    gate = gate,
                    onClick = { showConfirmSheet = true },
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (editingOverlay != null) {
        val index = editingOverlay ?: -1
        OverlayEditorSheet(
            initial = composition.overlays.getOrNull(index),
            onDismiss = { editingOverlay = null },
            onSave = { overlay ->
                composition = if (index in composition.overlays.indices) {
                    composition.withOverlayReplaced(index, overlay)
                } else {
                    composition.withOverlayAdded(overlay)
                }
                editingOverlay = null
            },
        )
    }

    TxConfirmSheet(
        visible = showConfirmSheet,
        mode = composition.mode,
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

/** The mode-aspect preview: image + pinch/drag when set, pick prompt when not. */
@Composable
private fun TxPreviewFrame(
    preview: Bitmap?,
    mode: SstvMode,
    gesturesEnabled: Boolean,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onGesture: (panDx: Float, panDy: Float, zoomFactor: Float, previewW: Float, previewH: Float) -> Unit,
) {
    val aspect = mode.width.toFloat() / mode.height.toFloat()
    val config = LocalConfiguration.current
    // Landscape gets a height cap so the frame doesn't stretch to full width
    // and push the controls off-screen; portrait fills the width as before.
    val maxHeightDp = previewMaxHeightDp(config.screenWidthDp, config.screenHeightDp, aspect)
    val sizeModifier = if (maxHeightDp == null) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.height(maxHeightDp.dp)
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      Box(
        modifier = sizeModifier
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface)
            .border(1.dp, BgSurface3, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (preview == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.tx_empty_title),
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.tx_empty_body),
                    color = TextMuted,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onPickImage,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    ) {
                        Text(stringResource(R.string.tx_pick_button), color = Color.Black)
                    }
                    OutlinedButton(onClick = onTakePhoto) {
                        Text(stringResource(R.string.tx_camera_button), color = TextPrimary)
                    }
                }
            }
        } else {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = stringResource(R.string.tx_preview_description),
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(gesturesEnabled) {
                        if (gesturesEnabled) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                onGesture(pan.x, pan.y, zoom, size.width.toFloat(), size.height.toFloat())
                            }
                        }
                    },
            )
            // Re-pick affordances in the corner: library (CHANGE) and camera.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                CornerAffordance(
                    text = stringResource(R.string.tx_camera_photo),
                    enabled = gesturesEnabled,
                    onClick = onTakePhoto,
                )
                CornerAffordance(
                    text = stringResource(R.string.tx_change_photo),
                    enabled = gesturesEnabled,
                    onClick = onPickImage,
                )
            }
        }
      }
    }
}

/** A small dark-pill tap target over the preview corner (CHANGE / CAMERA). */
@Composable
private fun CornerAffordance(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = 11.sp,
        fontFamily = GeistMonoFamily,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            // Role.Button so TalkBack announces this pill as a button rather
            // than plain text (CHANGE / CAMERA are actionable, not labels).
            .clickable(enabled = enabled, role = Role.Button) { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Horizontal mode selector, labels like "Scottie 1 · 320×256 · 111 s". */
@Composable
private fun ModeChipRow(
    selected: SstvMode,
    enabled: Boolean,
    onSelect: (SstvMode) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(SstvMode.entries) { mode ->
            val active = mode == selected
            Text(
                text = modeChipLabel(mode),
                color = if (active) Color.Black else TextPrimary,
                fontSize = 12.sp,
                fontFamily = GeistMonoFamily,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (active) Accent else BgSurface)
                    .clickable(enabled = enabled && !active) { onSelect(mode) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** The overlay rows + add button. */
@Composable
private fun OverlayList(
    overlays: List<TextOverlay>,
    enabled: Boolean,
    onEdit: (Int) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        overlays.forEachIndexed { index, overlay ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BgSurface)
                    .clickable(enabled = enabled) { onEdit(index) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color(overlay.colorArgb))
                        .border(1.dp, BgSurface3, CircleShape),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = overlay.text,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = overlay.position.name.replace('_', ' '),
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontFamily = GeistMonoFamily,
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = stringResource(R.string.tx_overlay_remove),
                    color = StatusWarn,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable(enabled = enabled) { onRemove(index) },
                )
            }
        }
        OutlinedButton(
            onClick = onAdd,
            enabled = enabled,
            modifier = Modifier.padding(top = 6.dp),
        ) {
            Text(stringResource(R.string.tx_overlay_add), fontSize = 12.sp)
        }
    }
}

/** TRANSMIT button with the gate reason underneath when blocked. */
@Composable
private fun TransmitButton(gate: TxGate, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            enabled = gate == TxGate.READY,
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.tx_transmit_button),
                color = Color.Black,
                fontWeight = FontWeight.Bold,
            )
        }
        val reason = when (gate) {
            TxGate.READY, TxGate.TRANSMITTING -> null
            TxGate.NO_IMAGE -> stringResource(R.string.tx_gate_no_image)
            TxGate.TUNE_ACTIVE -> stringResource(R.string.tx_gate_tune_active)
        }
        if (reason != null) {
            Spacer(Modifier.height(4.dp))
            Text(text = reason, color = TextMuted, fontSize = 12.sp)
        }
    }
}

/** Progress bar + elapsed/total + cancel, shown while the rig is keyed. */
@Composable
private fun TxProgressPanel(progress: Float, mode: SstvMode, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            color = StatusWarn,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = txElapsedLabel(progress, mode.txDurationSeconds),
            color = TextPrimary,
            fontSize = 13.sp,
            fontFamily = GeistMonoFamily,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onCancel) {
            Text(stringResource(R.string.tx_cancel_button), color = StatusWarn)
        }
    }
}

