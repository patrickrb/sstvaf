package radio.ks3ckc.sstvaf.ui.tx

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.BgApp
import radio.ks3ckc.sstvaf.theme.BgSurface
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.StatusBad
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
    onPanBy: (dxFraction: Float, dyFraction: Float) -> Unit,
    onOverlayTouched: (String) -> Unit,
    onOverlayMovedTo: (id: String, xPercent: Float, yPercent: Float) -> Unit,
    onDeselect: () -> Unit,
    onStrokeStart: (xPercent: Float, yPercent: Float) -> Unit,
    onStrokeExtend: (xPercent: Float, yPercent: Float) -> Unit,
    onDeleteSelected: () -> Unit,
    onChangePhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val aspect = composition.mode.width.toFloat() / composition.mode.height
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface),
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
        if (editable) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(tool, composition.overlays, selectedOverlayId) {
                        canvasGestures(
                            tool = tool,
                            overlays = composition.overlays,
                            widthPx = widthPx,
                            heightPx = heightPx,
                            onPanBy = onPanBy,
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
            ChangePill(
                onClick = onChangePhoto,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            )
        }
    }
}

/**
 * The canvas's pointer handling.
 *
 * Extracted from the composable so the hit-testing and coordinate conversion it
 * relies on ([overlayHitTest], [toFramePercent]) can be unit-tested; the gesture
 * loop itself is thin.
 */
private suspend fun PointerInputScope.canvasGestures(
    tool: TxTool,
    overlays: List<TextOverlay>,
    widthPx: Float,
    heightPx: Float,
    onPanBy: (Float, Float) -> Unit,
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
        val hit = overlayHitTest(overlays, startPercent.first, startPercent.second)
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
        while (true) {
            val event = awaitPointerEvent()
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
            .size(28.dp)
            .clip(CircleShape)
            .background(BgApp.copy(alpha = 0.85f))
            .clickable(onClickLabel = description, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SstvAfIcons.Close(
            size = 14.dp,
            color = StatusBad,
            strokeWidth = 2.2f,
        )
    }
}

/** The "Change" pill that reopens the photo picker. */
@Composable
private fun ChangePill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.tx_change_photo_pill),
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(BgApp.copy(alpha = 0.7f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = TextPrimary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = GeistMonoFamily,
    )
}
