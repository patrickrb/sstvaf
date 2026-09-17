package radio.ks3ckc.sstvaf.ui.tx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.BgApp
import radio.ks3ckc.sstvaf.theme.BgSurface
import radio.ks3ckc.sstvaf.theme.BgSurface2
import radio.ks3ckc.sstvaf.theme.BgSurface3
import radio.ks3ckc.sstvaf.theme.Border
import radio.ks3ckc.sstvaf.theme.BorderStrong
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.TextFaint
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary
import radio.ks3ckc.sstvaf.ui.components.IntSlider
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.selection.selectable
import androidx.annotation.StringRes

/**
 * The panel under the tool rail: the controls for whichever tool is active.
 *
 * One card, contents swapped per tool, canvas always visible above it. No
 * nested sheets and no forms — the previous composer opened a modal to edit an
 * overlay, which hid the picture the overlay was going onto.
 */
@Composable
internal fun TxToolPanel(
    tool: TxTool,
    composition: TxComposition,
    draft: TxEditorDraft,
    selectedOverlay: TextOverlay?,
    callsign: String,
    callsignSet: Boolean,
    onZoomChange: (Float) -> Unit,
    onResetCrop: () -> Unit,
    onFillFrame: () -> Unit,
    onDraftTextChange: (String) -> Unit,
    onCommitText: () -> Unit,
    onColorPick: (Int) -> Unit,
    onSizePick: (Float) -> Unit,
    onStylePick: (OverlayStyle) -> Unit,
    onStampCallsign: (CallsignPreset) -> Unit,
    onFramePick: (ImageFrame) -> Unit,
    onAdjustmentsChange: (ImageAdjustments) -> Unit,
    onResetAdjustments: () -> Unit,
    onStrokeWidthPick: (Float) -> Unit,
    onUndoStroke: () -> Unit,
    onClearStrokes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (tool) {
            TxTool.CROP -> CropPanel(
                zoom = composition.zoom,
                onZoomChange = onZoomChange,
                onReset = onResetCrop,
                onFill = onFillFrame,
            )
            TxTool.TEXT -> TextPanel(
                draft = draft,
                selectedOverlay = selectedOverlay,
                overlayCount = composition.overlays.size,
                onDraftTextChange = onDraftTextChange,
                onCommit = onCommitText,
                onColorPick = onColorPick,
                onSizePick = onSizePick,
                onStylePick = onStylePick,
            )
            TxTool.CALLSIGN -> CallsignPanel(
                callsign = callsign,
                callsignSet = callsignSet,
                current = composition.overlays.firstOrNull { it.id == CALLSIGN_OVERLAY_ID },
                draft = draft,
                onStamp = onStampCallsign,
                onColorPick = onColorPick,
                onSizePick = onSizePick,
            )
            TxTool.FRAME -> FramePanel(
                selected = composition.frame,
                onPick = onFramePick,
            )
            TxTool.ADJUST -> AdjustPanel(
                adjustments = composition.adjustments,
                onChange = onAdjustmentsChange,
                onReset = onResetAdjustments,
            )
            TxTool.DRAW -> DrawPanel(
                draft = draft,
                hasStrokes = composition.paths.isNotEmpty(),
                onColorPick = onColorPick,
                onWidthPick = onStrokeWidthPick,
                onUndo = onUndoStroke,
                onClear = onClearStrokes,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Crop
// ---------------------------------------------------------------------------

@Composable
private fun CropPanel(
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    onReset: () -> Unit,
    onFill: () -> Unit,
) {
    Hint(stringResource(R.string.tx_crop_hint))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Mono(stringResource(R.string.tx_zoom_min))
        IntSlider(
            value = zoomToSlider(zoom),
            onValueChange = { onZoomChange(sliderToZoom(it)) },
            valueRange = ZOOM_SLIDER_MIN.toFloat()..ZOOM_SLIDER_MAX.toFloat(),
            modifier = Modifier.weight(1f),
            thumbColor = Accent,
            activeTrackColor = Accent,
        )
        Mono(stringResource(R.string.tx_zoom_max))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(stringResource(R.string.tx_crop_reset), onClick = onReset)
        Pill(stringResource(R.string.tx_crop_fill), onClick = onFill)
    }
}

// ---------------------------------------------------------------------------
// Text
// ---------------------------------------------------------------------------

@Composable
private fun TextPanel(
    draft: TxEditorDraft,
    selectedOverlay: TextOverlay?,
    overlayCount: Int,
    onDraftTextChange: (String) -> Unit,
    onCommit: () -> Unit,
    onColorPick: (Int) -> Unit,
    onSizePick: (Float) -> Unit,
    onStylePick: (OverlayStyle) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoField(
            value = draft.text,
            placeholder = stringResource(R.string.tx_text_placeholder),
            onValueChange = onDraftTextChange,
            onDone = onCommit,
            modifier = Modifier.weight(1f),
        )
        // "Add" becomes "Done" while an overlay is selected: with a selection
        // the field live-edits that overlay, so there is nothing to add.
        AccentButton(
            label = stringResource(
                if (selectedOverlay != null) R.string.tx_text_done else R.string.tx_text_add,
            ),
            enabled = selectedOverlay != null || draft.text.isNotBlank(),
            onClick = onCommit,
        )
    }
    SwatchRow(selected = draft.colorArgb, onPick = onColorPick)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SizeRow(selected = draft.sizeFraction, onPick = onSizePick)
        Box(modifier = Modifier.weight(1f))
        StyleRow(selected = draft.style, onPick = onStylePick)
    }
    Hint(
        when {
            selectedOverlay != null -> stringResource(R.string.tx_text_hint_selected)
            overlayCount > 0 -> stringResource(R.string.tx_text_hint_tap)
            else -> stringResource(R.string.tx_text_hint_bar)
        },
    )
}

// ---------------------------------------------------------------------------
// Callsign
// ---------------------------------------------------------------------------

@Composable
private fun CallsignPanel(
    callsign: String,
    callsignSet: Boolean,
    current: TextOverlay?,
    draft: TxEditorDraft,
    onStamp: (CallsignPreset) -> Unit,
    onColorPick: (Int) -> Unit,
    onSizePick: (Float) -> Unit,
) {
    if (!callsignSet) {
        // Stamping an unset callsign puts an empty overlay on the picture: it
        // draws nothing, so the operator cannot see it to move or delete it,
        // and nothing reaches the air. Say what is missing instead.
        Hint(stringResource(R.string.tx_callsign_unset))
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.tx_callsign_prompt, callsign),
            color = TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = stringResource(R.string.tx_callsign_source),
            color = TextFaint,
            fontSize = 11.sp,
            fontFamily = GeistMonoFamily,
        )
    }

    val active = current?.let { matchingCallsignPreset(it) }
    // A 3x2 grid built from two Rows rather than a LazyGrid: six fixed cells
    // need no laziness, and a nested scrollable inside the panel would fight
    // the canvas's own gestures.
    for (row in listOf(
        listOf(CallsignPreset.TOP_LEFT, CallsignPreset.TOP_CENTER, CallsignPreset.TOP_RIGHT),
        listOf(CallsignPreset.BOTTOM_LEFT, CallsignPreset.BOTTOM_CENTER, CallsignPreset.BOTTOM_RIGHT),
    )) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (preset in row) {
                PresetCell(
                    preset = preset,
                    selected = preset == active,
                    onClick = { onStamp(preset) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    SwatchRow(selected = draft.colorArgb, onPick = onColorPick)
    SizeRow(selected = draft.sizeFraction, onPick = onSizePick)
}

/**
 * One position preset, drawn as a bar inside the cell where the stamp will land.
 *
 * A picture of the outcome, not a label. "Bottom right" in words takes longer
 * to read than the bar takes to see, and the bar also shows the difference
 * between the full-width centre presets and the short corner ones.
 */
@Composable
private fun PresetCell(
    preset: CallsignPreset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(presetDescriptionRes(preset))
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Accent.copy(alpha = 0.14f) else BgSurface2)
            .border(
                1.dp,
                if (selected) Accent else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            // A graphical cell with no text descendant: onClickLabel names the
            // action but leaves the node itself unnamed, so TalkBack announces
            // an unlabelled button. The description names it and the selected
            // state says whether it is the active preset.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        val barColor = if (selected) Accent else TextFaint
        val isCentre = preset.style == OverlayStyle.BAR
        val isTop = preset.yPercent < 50f
        Box(
            modifier = Modifier
                .align(
                    when {
                        isCentre && isTop -> Alignment.TopCenter
                        isCentre -> Alignment.BottomCenter
                        isTop && preset == CallsignPreset.TOP_LEFT -> Alignment.TopStart
                        isTop -> Alignment.TopEnd
                        preset == CallsignPreset.BOTTOM_LEFT -> Alignment.BottomStart
                        else -> Alignment.BottomEnd
                    },
                )
                .padding(6.dp)
                .height(6.dp)
                .then(if (isCentre) Modifier.fillMaxWidth() else Modifier.width(18.dp))
                .clip(RoundedCornerShape(2.dp))
                .background(barColor),
        )
    }
}

// ---------------------------------------------------------------------------
// Frame
// ---------------------------------------------------------------------------

@Composable
private fun FramePanel(selected: ImageFrame, onPick: (ImageFrame) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (frame in ImageFrame.entries) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(role = Role.Button) { onPick(frame) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FramePreview(frame = frame, selected = frame == selected)
                Text(
                    text = stringResource(frameLabelRes(frame)),
                    color = if (frame == selected) Accent else TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
    Hint(stringResource(R.string.tx_frame_hint))
}

/** A 4:3 swatch showing the frame over a stand-in picture. */
@Composable
private fun FramePreview(frame: ImageFrame, selected: Boolean) {
    val inset = when (frame) {
        ImageFrame.NONE -> 0.dp
        ImageFrame.THIN -> 2.dp
        ImageFrame.BOLD -> 5.dp
        ImageFrame.AMBER -> 3.dp
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(6.dp))
            .background(
                // A stand-in gradient, not a real photo: the swatch is showing
                // the border treatment, and a recognisable picture would draw
                // the eye to itself instead.
                Brush.linearGradient(listOf(Color(0xFF3C7FB8), Color(0xFFE9C08A))),
            )
            .border(
                2.dp,
                if (selected) Accent else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .padding(2.dp),
    ) {
        if (frame != ImageFrame.NONE) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .border(inset, Color(frameColorArgb(frame))),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Adjust
// ---------------------------------------------------------------------------

@Composable
private fun AdjustPanel(
    adjustments: ImageAdjustments,
    onChange: (ImageAdjustments) -> Unit,
    onReset: () -> Unit,
) {
    AdjustRow(
        label = stringResource(R.string.tx_adjust_brightness),
        value = adjustments.brightness,
        onChange = { onChange(adjustments.copy(brightness = it)) },
    )
    AdjustRow(
        label = stringResource(R.string.tx_adjust_contrast),
        value = adjustments.contrast,
        onChange = { onChange(adjustments.copy(contrast = it)) },
    )
    AdjustRow(
        label = stringResource(R.string.tx_adjust_saturation),
        value = adjustments.saturation,
        onChange = { onChange(adjustments.copy(saturation = it)) },
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Pill(stringResource(R.string.tx_adjust_reset), onClick = onReset)
        Hint(stringResource(R.string.tx_adjust_hint))
    }
}

@Composable
private fun AdjustRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.width(74.dp),
            color = TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        IntSlider(
            value = value,
            onValueChange = onChange,
            valueRange = ADJUSTMENT_MIN.toFloat()..ADJUSTMENT_MAX.toFloat(),
            modifier = Modifier.weight(1f),
            thumbColor = Accent,
            activeTrackColor = Accent,
        )
        Text(
            // A signed offset from neutral, not the raw 50..150: "+12" says
            // what the operator changed, "112" makes them do the subtraction.
            text = adjustmentOffsetLabel(value),
            modifier = Modifier.width(36.dp),
            color = TextFaint,
            fontSize = 11.sp,
            fontFamily = GeistMonoFamily,
            textAlign = TextAlign.End,
        )
    }
}

// ---------------------------------------------------------------------------
// Draw
// ---------------------------------------------------------------------------

@Composable
private fun DrawPanel(
    draft: TxEditorDraft,
    hasStrokes: Boolean,
    onColorPick: (Int) -> Unit,
    onWidthPick: (Float) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
) {
    Hint(stringResource(R.string.tx_draw_hint))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SwatchRow(selected = draft.colorArgb, onPick = onColorPick)
        Box(modifier = Modifier.weight(1f))
        for (width in STROKE_WIDTHS) {
            val selected = width == draft.strokeWidth
            val widthDescription = stringResource(strokeWidthDescriptionRes(width))
            Box(
                modifier = Modifier
                    .size(width = 32.dp, height = 28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) Accent.copy(alpha = 0.14f) else BgSurface3)
                    // The bar inside is the only thing distinguishing these, so
                    // they need a spoken name and a selected state.
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onWidthPick(width) },
                    )
                    .semantics { contentDescription = widthDescription },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(14.dp)
                        .height(strokePreviewHeight(width))
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (selected) Accent else TextMuted),
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(stringResource(R.string.tx_draw_undo), enabled = hasStrokes, onClick = onUndo)
        Pill(stringResource(R.string.tx_draw_clear), enabled = hasStrokes, onClick = onClear)
    }
}

/** The bar height in the stroke-width buttons, showing relative weight. */
private fun strokePreviewHeight(widthFraction: Float) = when (widthFraction) {
    STROKE_THIN -> 2.dp
    STROKE_MEDIUM -> 4.dp
    else -> 7.dp
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

/** The five-colour swatch row, shared by Text, Callsign and Draw. */
@Composable
private fun SwatchRow(selected: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (swatch in OVERLAY_COLOR_SWATCHES) {
            val isSelected = swatch == selected
            val swatchDescription = stringResource(swatchDescriptionRes(swatch))
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(swatch))
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) Accent else BorderStrong,
                        shape = CircleShape,
                    )
                    // A bare coloured circle: nothing here reaches a screen
                    // reader without a name, and colour is the whole meaning.
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onPick(swatch) },
                    )
                    .semantics { contentDescription = swatchDescription },
            )
        }
    }
}

/** The S / M / L size buttons. */
@Composable
private fun SizeRow(selected: Float, onPick: (Float) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((labelRes, fraction) in listOf(
            R.string.tx_size_small to OVERLAY_SIZE_SMALL,
            R.string.tx_size_medium to OVERLAY_SIZE_MEDIUM,
            R.string.tx_size_large to OVERLAY_SIZE_LARGE,
        )) {
            val isSelected = fraction == selected
            Box(
                modifier = Modifier
                    .size(width = 32.dp, height = 28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Accent else BgSurface3)
                    .clickable(role = Role.Button) { onPick(fraction) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(labelRes),
                    color = if (isSelected) BgApp else TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistMonoFamily,
                )
            }
        }
    }
}

/** The Bar / Outline style buttons. */
@Composable
private fun StyleRow(selected: OverlayStyle, onPick: (OverlayStyle) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (style in OverlayStyle.entries) {
            val isSelected = style == selected
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Accent else BgSurface3)
                    .clickable(role = Role.Button) { onPick(style) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(overlayStyleLabelRes(style)),
                    color = if (isSelected) BgApp else TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistMonoFamily,
                )
            }
        }
    }
}

/** A rounded pill action (Reset, Fill frame, Undo, Clear). */
@Composable
private fun Pill(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(BgSurface3)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            color = if (enabled) TextPrimary else TextFaint,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** The panel's accent action button (Add / Done). */
@Composable
private fun AccentButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) Accent else BgSurface3)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) BgApp else TextFaint,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * The mono text field for overlay text.
 *
 * BasicTextField rather than a Material TextField: the design's input is a
 * 40dp-tall box with a hairline border and no floating label, and stripping a
 * Material field back to that fights its built-in decoration box the whole way.
 */
@Composable
private fun MonoField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(BgApp)
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                color = TextFaint,
                fontSize = 13.sp,
                maxLines = 1,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = GeistMonoFamily,
            ),
            cursorBrush = SolidColor(Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = { onDone() },
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A muted hint line. */
@Composable
private fun Hint(text: String) {
    Text(text = text, color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp)
}

/** A small mono label (the zoom slider's end caps). */
@Composable
private fun Mono(text: String) {
    Text(text = text, color = TextFaint, fontSize = 11.sp, fontFamily = GeistMonoFamily)
}

/**
 * The spoken name for a stroke-width button.
 *
 * These buttons are three bars of different thickness with no text, so the
 * name has to come from here or a screen reader reaches an unlabelled control.
 */
@StringRes
internal fun strokeWidthDescriptionRes(width: Float): Int = when (width) {
    STROKE_THIN -> R.string.tx_stroke_thin
    STROKE_MEDIUM -> R.string.tx_stroke_medium
    else -> R.string.tx_stroke_thick
}

/**
 * The spoken name for a colour swatch.
 *
 * Colour is the entire meaning of these controls, and it is exactly the thing
 * a screen reader cannot convey, so each one is named.
 */
@StringRes
internal fun swatchDescriptionRes(colorArgb: Int): Int = when (colorArgb) {
    OVERLAY_COLOR_WHITE -> R.string.tx_color_white
    OVERLAY_COLOR_BLACK -> R.string.tx_color_black
    OVERLAY_COLOR_CYAN -> R.string.tx_color_cyan
    OVERLAY_COLOR_AMBER -> R.string.tx_color_amber
    else -> R.string.tx_color_green
}
