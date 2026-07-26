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
    val warmth = MaterialTheme.colorScheme.primaryContainer
    val localFamily = MaterialTheme.colorScheme.secondaryContainer
    val aiFamily = MaterialTheme.colorScheme.tertiaryContainer
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // «Телефон слегка затемняется»: the object's warmth stays in the middle of
            // the space, the edges sink into shade — depth without a single decoration.
            .background(
                Brush.radialGradient(
                    0.0f to lerp(surface, warmth, 0.90f),
                    0.52f to lerp(surface, warmth, 0.22f),
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
        val innerRx = rx * 0.76f
        val innerRy = ry * 0.44f
        val outerRx = rx
        val outerRy = ry * 0.60f

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

        // The orbits themselves — faint OPEN arcs, only where bubbles actually live: a
        // closed oval reads as a dial menu, a broken one as traces of motion in space.
        val orbitColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)
        val threadColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            fun arc(rxA: Float, ryA: Float, startDeg: Float, sweepDeg: Float) = drawArc(
                color = orbitColor,
                startAngle = startDeg,
                sweepAngle = sweepDeg,
                useCenter = false,
                topLeft = Offset(c.x - rxA, c.y - ryA),
                size = Size(rxA * 2, ryA * 2),
                style = Stroke(width = 2.dp.toPx()),
            )
            arc(innerRx.toPx(), innerRy.toPx(), 200f, 140f)      // inner: upper arc only
            arc(outerRx.toPx(), outerRy.toPx(), 120f, 120f)      // outer: instant (left)
            arc(outerRx.toPx(), outerRy.toPx(), -60f, 70f)       // outer: smart (right)
            arc(outerRx.toPx() * 1.02f, outerRy.toPx() * 1.12f, 30f, 55f) // far: AI
        }

        placed.forEachIndexed { index, spot ->
            val cosA = cos(spot.angleRad).toFloat()
            val sinA = sin(spot.angleRad).toFloat()
            val inner = spot.ring == SpaceRing.NEAR
            // AI rides its own, furthest arc — visibly past the outer orbit.
            val far = spot.ring == SpaceRing.FAR
            val ringX = if (inner) innerRx else if (far) outerRx * 1.02f else outerRx
            val ringY = if (inner) innerRy else if (far) outerRy * 1.12f else outerRy
            val x = ringX * cosA
            // The near three ride the UPPER arc of the inner orbit — never its lower
            // half, where the object lives.
            val y = if (inner) -ringY * abs(sinA) else ringY * sinA
            // Depth is size, presence and COLOUR: the inner orbit is big, named and fully
            // lit; the outer one shrinks, fades and greys towards the theme — a bubble
            // regains its own colour only when the carried object closes in (the magnet).
            val (bubbleSize, depthAlpha) = when (spot.ring) {
                SpaceRing.NEAR -> 62.dp to 1f
                SpaceRing.MID -> 44.dp to 0.74f
                SpaceRing.FAR -> 36.dp to 0.52f
            }
            // Bubbles hover NEAR their orbit, not pinned to it — a deterministic drift
            // of a few dp per bubble keeps the space alive instead of dial-like.
            val jx = if (inner) 0.dp else (((index * 37) % 13) - 6).dp
            val jy = if (inner) 0.dp else (((index * 53) % 15) - 7).dp
            key(spot.bubble.capabilityId.value) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .offset(x = x + jx, y = y + jy)
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
                        } else if (far) {
                            // AI family: violet, quiet but alive
                            lerp(bubbleColor(spot.bubble.icon), aiFamily, 0.62f)
                        } else {
                            // local family: cool, receded towards the theme
                            lerp(bubbleColor(spot.bubble.icon), localFamily, 0.62f)
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
            ObjectHeader(obj, preview = previewBitmap, understood = true, titled = false, sizeOverride = 150.dp)
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
