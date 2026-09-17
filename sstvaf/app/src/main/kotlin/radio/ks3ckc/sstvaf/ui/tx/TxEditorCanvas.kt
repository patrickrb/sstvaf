package radio.ks3ckc.sstvaf.ui.tx

import android.graphics.Bitmap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.BgApp
import radio.ks3ckc.sstvaf.theme.BgSurface
import radio.ks3ckc.sstvaf.theme.BgSurface3
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.Signal
import radio.ks3ckc.sstvaf.theme.StatusBad
import radio.ks3ckc.sstvaf.theme.StatusConfirmed
import radio.ks3ckc.sstvaf.theme.TextPrimary
import radio.ks3ckc.sstvaf.ui.components.SstvAfIcons

/**
 * The transmit composer's canvas: the picture at the SSTV mode's native aspect
 * ratio, with the operator's overlays and strokes on it, and the gestures that
 * edit them.
 *
 * It renders the *flattened composite* rather than stacking Compose layers over
 * the photo. That is deliberate: the composite is what actually goes on the air
 * (see `renderComposite`), so previewing it directly means what the operator
 * approves is byte-for-byte what the receiving station decodes. A layered
 * preview can always drift from the flattened output, and the operator would
 * never find out until someone told them their callsign was missing.
 *
 * The only things drawn on top are editing affordances - the selection outline,
 * the delete button, the Change pill - which are not part of the picture.
 */
@Composable
internal fun TxEditorCanvas(
    preview: Bitmap?,
    composition: TxComposition,
    tool: TxTool,
    selectedOverlayId: String?,
    editable: Boolean,
    transmitting: Boolean,
    transmitProgress: Float,
    done: Boolean,
    onEditAgain: () -> Unit,
    onNewPicture: () -> Unit,
    onPanBy: (dxFraction: Float, dyFraction: Float) -> Unit,
    onZoomBy: (scale: Float) -> Unit,
    onOverlayTouched: (String) -> Unit,
    onOverlayMovedTo: (id: String, xPercent: Float, yPercent: Float) -> Unit,
    onDeselect: () -> Unit,
    onStrokeStart: (xPercent: Float, yPercent: Float) -> Unit,
    onStrokeExtend: (xPercent: Float, yPercent: Float) -> Unit,
    onDeleteSelected: () -> Unit,
    onChangePhoto: () -> Unit,
    onClearImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val aspect = composition.mode.width.toFloat() / composition.mode.height

    // The ring breathes for the whole transmission. The rig being keyed is the
    // one state an operator must never be unsure about, and a static border is
    // easy to stop seeing.
    val ringWidth by animateBreathingRing(active = transmitting)

    // On a landscape or desktop-shaped window, filling the width and deriving
    // the height from the aspect makes the canvas taller than the viewport and
    // pushes the tool rail, the tool panel and Transmit off the bottom. This
    // screen does not scroll, so those controls become unreachable - the same
    // shape of regression as the TX pick-image button on tablets (issue #20).
    // Landscape therefore sizes by height and centres, as the pre-redesign
    // preview frame did; [previewMaxHeightDp] returns null in portrait, where
    // filling the width is correct.
    val configuration = LocalConfiguration.current
    val capDp = previewMaxHeightDp(
        screenWidthDp = configuration.screenWidthDp,
        screenHeightDp = configuration.screenHeightDp,
        aspect = aspect,
    )
    val frameModifier = if (capDp != null) {
        Modifier
            .heightIn(max = capDp.dp)
            .aspectRatio(aspect)
    } else {
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
    }

    // The overlay list has to be read live inside the gesture loop rather than
    // captured: see the note on [canvasGestures].
    val overlaysNow by rememberUpdatedState(composition.overlays)

    BoxWithConstraints(
        modifier = modifier
            .then(frameModifier)
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface)
            .then(
                if (transmitting) {
                    Modifier.border(
                        width = ringWidth.dp,
                        color = Signal.copy(alpha = 0.35f + 0.55f * (ringWidth - 2f)),
                        shape = RoundedCornerShape(12.dp),
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }

        if (preview != null) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = stringResource(R.string.tx_preview_description),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                // The composite is mode-native (320 px wide for most modes) and
                // is being shown several times that size; smoothing it would
                // misrepresent how crisp the transmitted picture actually is.
                filterQuality = FilterQuality.None,
            )
        }

        // One gesture surface for the whole canvas. Which gesture it performs is
        // decided by the active tool, matching the design: Crop pans the
        // picture, Draw draws, and anything else treats a tap on empty canvas as
        // "deselect". Overlays intercept their own drags below.
        // No gesture surface while the rig is keyed or on the sent scrim: the
        // picture is already being encoded, so an edit could not reach the air
        // and would only make the preview disagree with what went out.
        if (editable && !transmitting && !done) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Keyed on the tool and the frame size only. Keying it on
                    // the overlay list restarted the gesture coroutine on the
                    // first move of an overlay drag, cancelling the drag the
                    // operator was halfway through.
                    .pointerInput(tool, widthPx, heightPx) {
                        canvasGestures(
                            tool = tool,
                            overlays = { overlaysNow },
                            widthPx = widthPx,
                            heightPx = heightPx,
                            onPanBy = onPanBy,
                            onZoomBy = onZoomBy,
                            onOverlayTouched = onOverlayTouched,
                            onOverlayMovedTo = onOverlayMovedTo,
                            onDeselect = onDeselect,
                            onStrokeStart = onStrokeStart,
                            onStrokeExtend = onStrokeExtend,
                        )
                    },
            )
        }

        // Selection outline for the chosen overlay, drawn over the composite so
        // it is never mistaken for part of the picture.
        val selected = composition.overlays.firstOrNull { it.id == selectedOverlayId }
        if (selected != null && editable) {
            SelectionOutline(
                overlay = selected,
                widthPx = widthPx,
                heightPx = heightPx,
            )
            DeleteButton(
                onClick = onDeleteSelected,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
        }

        if (preview != null && editable) {
            // Change reopens the photo picker; New drops back to the four-way
            // empty state. Without New there is no route back to the CQ card,
            // grid card or Last sent once a picture is loaded - the operator
            // would have to restart the app to reach them.
            Row(
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CanvasPill(
                    label = stringResource(R.string.tx_change_photo_pill),
                    onClick = onChangePhoto,
                )
                CanvasPill(
                    label = stringResource(R.string.tx_new_picture_short),
                    onClick = onClearImage,
                )
            }
        }

        if (transmitting) {
            TransmitLine(progress = transmitProgress)
        }

        if (done) {
            SentScrim(onEditAgain = onEditAgain, onNewPicture = onNewPicture)
        }
    }
}

/**
 * The amber line sweeping down the canvas as the picture goes out, with
 * everything below it dimmed.
 *
 * It mirrors the receive canvas's decode reveal, deliberately: SSTV sends a
 * picture one scan line at a time, so "how far down the picture are we" is the
 * same question on both sides, and an operator who has watched a decode
 * already knows how to read this. The dim below the line is what is still to
 * come.
 */
@Composable
private fun TransmitLine(progress: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val fraction = progress.coerceIn(0f, 1f)
        val y = size.height * fraction
        // Dim the part not yet sent.
        drawRect(
            color = BgApp.copy(alpha = 0.55f),
            topLeft = Offset(0f, y),
            size = Size(size.width, (size.height - y).coerceAtLeast(0f)),
        )
        // The line itself, with a glow above it.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Signal.copy(alpha = 0.5f)),
                startY = y - 12.dp.toPx(),
                endY = y,
            ),
            topLeft = Offset(0f, (y - 12.dp.toPx()).coerceAtLeast(0f)),
            size = Size(size.width, 12.dp.toPx().coerceAtMost(y.coerceAtLeast(0f))),
        )
        drawRect(
            color = Signal,
            topLeft = Offset(0f, y - 1.dp.toPx()),
            size = Size(size.width, 2.dp.toPx()),
        )
    }
}

/**
 * The "sent" scrim: a green check, confirmation that it reached the gallery,
 * and the two things an operator does next.
 *
 * Two choices rather than one dismiss, because after a transmission the
 * operator either sends the same card again to the next station — the common
 * case in a run — or starts something new. Making them pick means neither
 * path needs a second thought about whether the previous edits survived.
 */
@Composable
private fun SentScrim(onEditAgain: () -> Unit, onNewPicture: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgApp.copy(alpha = 0.7f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(StatusConfirmed.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            SstvAfIcons.Check(size = 22.dp, color = StatusConfirmed, strokeWidth = 2.2f)
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.tx_sent_saved),
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScrimPill(
                label = stringResource(R.string.tx_sent_edit_again),
                background = BgSurface3,
                textColor = TextPrimary,
                onClick = onEditAgain,
            )
            ScrimPill(
                label = stringResource(R.string.tx_sent_new_picture),
                background = Accent,
                textColor = BgApp,
                onClick = onNewPicture,
            )
        }
    }
}

@Composable
private fun ScrimPill(
    label: String,
    background: Color,
    textColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * The breathing ring's animated border width, in dp: 2 to 3 and back, about
 * once every 1.6 s. Returns a constant when idle so no animation runs and no
 * frames are spent on a canvas nobody is watching for RF.
 */
@Composable
private fun animateBreathingRing(active: Boolean): State<Float> {
    if (!active) return remember { mutableFloatStateOf(0f) }
    val transition = rememberInfiniteTransition(label = "tx-ring")
    return transition.animateFloat(
        initialValue = 2f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tx-ring-width",
    )
}

/**
 * The canvas's pointer handling.
 *
 * Extracted from the composable so the hit-testing and coordinate conversion it
 * relies on ([overlayHitTest], [toFramePercent]) can be unit-tested; the gesture
 * loop itself is thin.
 *
 * [overlays] is a provider rather than a list because this coroutine outlives
 * the composition that started it. The gesture loop must hit-test against the
 * overlays as they are *now*, and the callbacks it invokes must likewise read
 * the live composition: a callback closing over a stale one turns every
 * incremental edit into a no-op, because each event recomputes from the same
 * starting value and overwrites the previous step rather than building on it.
 */
private suspend fun PointerInputScope.canvasGestures(
    tool: TxTool,
    overlays: () -> List<TextOverlay>,
    widthPx: Float,
    heightPx: Float,
    onPanBy: (Float, Float) -> Unit,
    onZoomBy: (Float) -> Unit,
    onOverlayTouched: (String) -> Unit,
    onOverlayMovedTo: (String, Float, Float) -> Unit,
    onDeselect: () -> Unit,
    onStrokeStart: (Float, Float) -> Unit,
    onStrokeExtend: (Float, Float) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val startPercent = toFramePercent(down.position.x, down.position.y, widthPx, heightPx)

        // An overlay under the finger always wins, whatever the tool: the
        // operator is reaching for the thing they can see.
        val hit = overlayHitTest(overlays(), startPercent.first, startPercent.second)
        var dragging: String? = null
        if (hit != null) {
            dragging = hit.id
            onOverlayTouched(hit.id)
        } else {
            when (tool) {
                TxTool.DRAW -> onStrokeStart(startPercent.first, startPercent.second)
                TxTool.CROP -> Unit
                else -> onDeselect()
            }
        }

        var previous: PointerInputChange = down
        var previousSpan: Float? = null
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }

            // Two fingers on the crop tool is a pinch. Handled here rather than
            // through detectTransformGestures because the same surface also has
            // to serve overlay drags and freehand strokes, which that detector
            // would swallow.
            if (tool == TxTool.CROP && dragging == null && pressed.size >= 2) {
                val span = pinchSpan(
                    pressed[0].position.x, pressed[0].position.y,
                    pressed[1].position.x, pressed[1].position.y,
                )
                val last = previousSpan
                if (last != null && last > 0f && span > 0f) {
                    onZoomBy(span / last)
                }
                previousSpan = span
                pressed.forEach { it.consume() }
                continue
            }
            previousSpan = null

            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val percent = toFramePercent(change.position.x, change.position.y, widthPx, heightPx)
            when {
                dragging != null -> onOverlayMovedTo(dragging, percent.first, percent.second)
                tool == TxTool.DRAW -> onStrokeExtend(percent.first, percent.second)
                tool == TxTool.CROP -> {
                    val dx = change.position.x - previous.position.x
                    val dy = change.position.y - previous.position.y
                    if (widthPx > 0f && heightPx > 0f) {
                        onPanBy(dx / widthPx, dy / heightPx)
                    }
                }
            }
            previous = change
            change.consume()
        }
    }
}

/** A dashed accent outline around the selected overlay. */
@Composable
private fun SelectionOutline(
    overlay: TextOverlay,
    widthPx: Float,
    heightPx: Float,
) {
    val density = LocalDensity.current
    Canvas(modifier = Modifier.fillMaxSize()) {
        val box = overlaySelectionBox(overlay, widthPx, heightPx)
        val dash = with(density) { 4.dp.toPx() }
        drawRoundRect(
            color = Accent,
            topLeft = Offset(box.left, box.top),
            size = Size(box.width, box.height),
            style = Stroke(
                width = with(density) { 1.5.dp.toPx() },
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(dash, dash),
                ),
            ),
        )
    }
}

/** The ✕ that removes the selected overlay. */
@Composable
private fun DeleteButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.tx_overlay_delete_description)
    Box(
        modifier = modifier
            // 48dp of touch target around a 28dp visual: this is a destructive
            // control sitting on top of the picture.
            .size(48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            // onClickLabel names the *action*, not the node, so an icon-only
            // control still reaches TalkBack unnamed without this.
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(BgApp.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            SstvAfIcons.Close(
                size = 14.dp,
                color = StatusBad,
                strokeWidth = 2.2f,
            )
        }
    }
}

/** A small pill over the canvas, for the picture-management actions. */
@Composable
private fun CanvasPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(BgApp.copy(alpha = 0.7f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            color = TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = GeistMonoFamily,
        )
    }
}
