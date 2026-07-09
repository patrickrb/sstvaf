package radio.ks3ckc.sstvaf.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.BgApp
import radio.ks3ckc.sstvaf.theme.TextFaint
import radio.ks3ckc.sstvaf.ui.motion.MotionTokens
import radio.ks3ckc.sstvaf.ui.motion.rememberHaptics

/**
 * Vertical navigation rail — the wide-screen counterpart to [TabBar]. Shown by
 * the shell on tablets and landscape phones (see [AdaptiveShell]) so the six
 * SSTV tabs live down the left edge and the content area keeps its full height.
 *
 * Mirrors TabBar's icon set, labels, active-accent colour and haptic tick so the
 * two navigation surfaces feel like one control that merely re-flows with size.
 * The rail scrolls if the canvas is ever too short for all six entries.
 */
@Composable
fun TabRail(
    activeTab: SstvTab,
    onTabSelected: (SstvTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()

    LaunchedEffect(activeTab) {
        haptics.tick()
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(84.dp)
            .background(
                // No explicit startX/endX: those are *pixel* coordinates, so a
                // fixed endX (84) would fade out after 84px — barely a third of
                // the 84.dp rail on a 3x-density screen. Defaulting endX to the
                // draw width keeps the fade spanning the whole rail at any DPI.
                Brush.horizontalGradient(
                    listOf(BgApp.copy(alpha = 0.95f), Color.Transparent),
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (tab in SstvTab.entries) {
            val isActive = tab == activeTab

            val color by animateColorAsState(
                targetValue = if (isActive) Accent else TextFaint,
                animationSpec = tween(MotionTokens.DurMed, easing = MotionTokens.EasingStandard),
                label = "rail-color",
            )
            val strokeWidth = if (isActive) 1.8f else 1.5f
            // Pill grows in behind the active item.
            val pillAlpha by animateColorAsState(
                targetValue = if (isActive) Accent.copy(alpha = 0.14f) else Color.Transparent,
                animationSpec = tween(MotionTokens.DurMed, easing = MotionTokens.EasingStandard),
                label = "rail-pill",
            )
            val iconPad by animateDpAsState(
                targetValue = if (isActive) 8.dp else 6.dp,
                animationSpec = tween(MotionTokens.DurMed, easing = MotionTokens.EasingStandard),
                label = "rail-pad",
            )

            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onTabSelected(tab) }
                    .padding(vertical = 6.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(pillAlpha)
                        .padding(horizontal = iconPad, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        when (tab) {
                            SstvTab.RX -> SstvAfIcons.RxImage(color = color, strokeWidth = strokeWidth)
                            SstvTab.GALLERY -> SstvAfIcons.Gallery(color = color, strokeWidth = strokeWidth)
                            SstvTab.TX -> SstvAfIcons.Transmit(color = color, strokeWidth = strokeWidth)
                            SstvTab.WATERFALL -> SstvAfIcons.Waterfall(color = color, strokeWidth = strokeWidth)
                            SstvTab.LOG -> SstvAfIcons.Book(color = color, strokeWidth = strokeWidth)
                            SstvTab.SETTINGS -> SstvAfIcons.Cog(color = color, strokeWidth = strokeWidth)
                        }
                    }
                }
                Text(
                    text = stringResource(tab.labelRes),
                    color = color,
                    fontSize = 10.5.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                    letterSpacing = 0.02.sp,
                )
            }
        }
    }
}
