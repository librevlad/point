package com.point.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.PointObject
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** sin of the MID sector's half-width — normalises the angle into a full column spread. */
private val SECTOR_SIN = sin(PI / 3).toFloat()

/**
 * #115 slice 2 — the object's SPACE, not a screen: the dimmed field holds the object in
 * the middle and its possibilities place themselves around it ([radialPlacement] — likely
 * near, instant left, real work right, AI far right). The object is dragged straight
 * (no scroll here to yield to); the nearest bubble magnetises and release connects.
 * A tap anywhere empty (or on nothing) dissolves the space back to the first screen.
 */
@Composable
fun ObjectSpace(
    obj: PointObject,
    bubbles: List<Bubble>,
    onBubble: (Bubble) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    previewBitmap: ImageBitmap? = null,
    pinned: CapabilityId? = null,
) {
    val surface = MaterialTheme.colorScheme.surface
    val dimmed = MaterialTheme.colorScheme.surfaceVariant
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // «Телефон слегка затемняется»: light stays with the object, the edges of
            // the space sink into shade — depth without a single decoration.
            .background(
                Brush.radialGradient(
                    0.0f to surface,
                    0.55f to surface,
                    1.0f to dimmed,
                ),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        val placed = remember(bubbles) { radialPlacement(bubbles, likelyCount(bubbles.size)) }
        // The horizontal axis is the scarce one on a phone, the vertical one generous.
        // NEAR/FAR project on their ellipse arcs; the MID sectors flatten into side
        // COLUMNS (x pinned to the edge, herringbone-staggered, the angle only spreads
        // them vertically) — the one projection that never collides on a narrow screen.
        val rx = maxWidth / 2 - 78.dp
        val ry = maxHeight / 2 - 110.dp

        val objectCenter = remember { mutableStateOf(Offset.Unspecified) }
        val bubbleCenters = remember { mutableStateMapOf<CapabilityId, Offset>() }
        var dragTarget by remember { mutableStateOf<CapabilityId?>(null) }
        var dragging by remember { mutableStateOf(false) }
        val dragOffset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
        val scope = rememberCoroutineScope()
        val motion = rememberMotionEnabled()
        val haptics = LocalHapticFeedback.current
        LaunchedEffect(placed) {
            bubbleCenters.keys.retainAll(placed.map { it.bubble.capabilityId }.toSet())
        }

        var columnSlot = 0
        placed.forEachIndexed { index, spot ->
            val cosA = cos(spot.angleRad).toFloat()
            val sinA = sin(spot.angleRad).toFloat()
            val x: Dp
            val y: Dp
            when (spot.ring) {
                SpaceRing.NEAR -> {
                    // A level row above the object: same height for all three, so their
                    // captions sit on one line; ×1.16 opens the fan wide enough that
                    // three Russian captions never touch.
                    x = rx * 1.16f * cosA
                    y = -ry * 0.56f
                }
                SpaceRing.MID -> {
                    val stagger = 1f - 0.15f * (columnSlot++ % 2)
                    x = rx * (if (cosA >= 0f) stagger else -stagger)
                    // Short columns beside the object — well below the near row.
                    y = ry * 0.36f * (sinA / SECTOR_SIN)
                }
                SpaceRing.FAR -> {
                    x = rx * cosA
                    y = ry * sinA
                }
            }
            // Depth is size and presence, not captions: the near ring is big, named and
            // fully lit; the further out, the smaller and quieter — an icon is enough
            // until the carried object approaches and the magnet names it.
            val (bubbleSize, depthAlpha) = when (spot.ring) {
                SpaceRing.NEAR -> 62.dp to 1f
                SpaceRing.MID -> 46.dp to 0.78f
                SpaceRing.FAR -> 40.dp to 0.62f
            }
            key(spot.bubble.capabilityId.value) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .offset(x = x, y = y)
                        .graphicsLayer { alpha = depthAlpha },
                ) {
                    BubbleItem(
                        bubble = spot.bubble,
                        index = index,
                        size = bubbleSize,
                        labelMaxLines = 1,
                        showLabel = spot.ring == SpaceRing.NEAR,
                        objectCenter = objectCenter.value,
                        pinned = spot.bubble.capabilityId == pinned,
                        magnet = spot.bubble.capabilityId == dragTarget,
                        onCenter = { bubbleCenters[spot.bubble.capabilityId] = it },
                        onClick = { onBubble(spot.bubble) },
                    )
                }
            }
        }

        // Carried between two fingers, the object shrinks well out of the way — the
        // magnet ring on the target must stay visible under it.
        val heldScale by animateFloatAsState(if (dragging) 0.55f else 1f, label = "space-held")
        Box(
            Modifier
                .align(Alignment.Center)
                .onGloballyPositioned { objectCenter.value = it.boundsInRoot().center }
                .graphicsLayer {
                    translationX = dragOffset.value.x
                    translationY = dragOffset.value.y
                    scaleX = heldScale
                    scaleY = heldScale
                }
                .pointerInput(placed.map { it.bubble.capabilityId }) {
                    val radius = MAGNET_RADIUS_DP.dp.toPx()
                    // The space does not scroll, so the object is carried by a PLAIN drag —
                    // the gesture the first screen cannot afford (#115 slice 1).
                    detectDragGestures(
                        onDragStart = {
                            dragging = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            scope.launch { dragOffset.snapTo(dragOffset.value + amount) }
                            val carried = objectCenter.value + dragOffset.value
                            val target = magnetTarget(carried, bubbleCenters, radius)
                            if (target != dragTarget) {
                                dragTarget = target
                                if (target != null) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        },
                        onDragEnd = {
                            dragging = false
                            val hit = placed.firstOrNull { it.bubble.capabilityId == dragTarget }
                            dragTarget = null
                            if (hit != null) onBubble(hit.bubble)
                            scope.launch {
                                if (motion && hit == null) {
                                    dragOffset.animateTo(Offset.Zero, spring(dampingRatio = 0.62f))
                                } else {
                                    dragOffset.snapTo(Offset.Zero)
                                }
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            dragTarget = null
                            scope.launch {
                                if (motion) dragOffset.animateTo(Offset.Zero, spring(dampingRatio = 0.62f))
                                else dragOffset.snapTo(Offset.Zero)
                            }
                        },
                    )
                },
        ) {
            // No caption in the space — the object is in the hand, names are list-speak.
            ObjectHeader(obj, preview = previewBitmap, understood = true, titled = false)
        }

        Text(
            text = "Потяните объект к действию · тап мимо — назад",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-28).dp),
        )
    }
}
