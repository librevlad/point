package com.point.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
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
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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
        // Two visible ORBITS around the object (the art-review verdict: the eye must see
        // the gravity, not infer it): a captioned inner orbit for the likely few, one
        // shared outer orbit for everything else. Ellipses — the phone is tall, not round.
        val rx = maxWidth / 2 - 62.dp
        val ry = maxHeight / 2 - 110.dp
        // Inner orbit: a wide, shallow ellipse whose upper arc passes just over the
        // object — the likely three sit exactly on it, captions above their heads.
        val innerRx = rx * 0.84f
        val innerRy = ry * 0.40f
        val outerRx = rx
        val outerRy = ry * 0.66f

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

        // The orbits themselves — faint, so the structure reads at a glance.
        val orbitColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
        val threadColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            for ((ellipseRx, ellipseRy) in listOf(innerRx to innerRy, outerRx to outerRy)) {
                drawOval(
                    color = orbitColor,
                    topLeft = Offset(c.x - ellipseRx.toPx(), c.y - ellipseRy.toPx()),
                    size = Size(ellipseRx.toPx() * 2, ellipseRy.toPx() * 2),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }

        placed.forEachIndexed { index, spot ->
            val cosA = cos(spot.angleRad).toFloat()
            val sinA = sin(spot.angleRad).toFloat()
            val inner = spot.ring == SpaceRing.NEAR
            val x = (if (inner) innerRx else outerRx) * cosA
            // The near three ride the UPPER arc of the inner orbit — never its lower
            // half, where the object lives.
            val y = (if (inner) -innerRy * abs(sinA) else outerRy * sinA)
            // Depth is size, presence and COLOUR: the inner orbit is big, named and fully
            // lit; the outer one shrinks, fades and greys towards the theme — a bubble
            // regains its own colour only when the carried object closes in (the magnet).
            val (bubbleSize, depthAlpha) = when (spot.ring) {
                SpaceRing.NEAR -> 64.dp to 1f
                SpaceRing.MID -> 46.dp to 0.82f
                SpaceRing.FAR -> 38.dp to 0.60f
            }
            key(spot.bubble.capabilityId.value) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .offset(x = x, y = y)
                        .graphicsLayer { alpha = depthAlpha },
                ) {
                    val magnet = spot.bubble.capabilityId == dragTarget
                    BubbleItem(
                        bubble = spot.bubble,
                        index = index,
                        size = bubbleSize,
                        labelMaxLines = 1,
                        showLabel = inner,
                        labelAbove = true,
                        tint = if (inner || magnet) {
                            null // own colour — the privilege of the close and the chosen
                        } else {
                            lerp(bubbleColor(spot.bubble.icon), dimmed, 0.55f)
                        },
                        objectCenter = objectCenter.value,
                        magnet = magnet,
                        onCenter = { bubbleCenters[spot.bubble.capabilityId] = it },
                        onClick = { onBubble(spot.bubble) },
                    )
                }
            }
        }

        // The thread of connection — OVER the bubbles (a line under them is invisible),
        // drawn while the carried object is inside some bubble's magnet radius.
        Canvas(Modifier.fillMaxSize()) {
            val target = dragTarget?.let { bubbleCenters[it] }
            if (target != null && objectCenter.value.isSpecified) {
                drawLine(
                    color = threadColor,
                    start = objectCenter.value + dragOffset.value,
                    end = target,
                    strokeWidth = 3.dp.toPx(),
                )
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
