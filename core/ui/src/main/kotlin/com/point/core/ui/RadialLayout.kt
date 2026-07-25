package com.point.core.ui

import com.point.core.model.Bubble
import com.point.core.model.BubbleTier
import kotlin.math.PI

/** Distance class from the object in its space (#115): near = likely, far = leaves the device. */
enum class SpaceRing { NEAR, MID, FAR }

/** A bubble with the spot it chose for itself: polar angle (screen coords, y down) + ring. */
data class PlacedBubble(val bubble: Bubble, val angleRad: Double, val ring: SpaceRing)

/**
 * #115 slice 2 — bubbles place THEMSELVES around the object. The layout encodes meaning,
 * not decoration: the likely few sit on the near ring around the object; the folded rest
 * take the middle ring — instant actions to the left, real work to the right; AI is always
 * on the far ring in the right hemisphere — the action physically leaves your hand's reach
 * before it leaves the device. Pure geometry — unit-tested, the composable only projects it.
 */
fun radialPlacement(bubbles: List<Bubble>, likelyCount: Int): List<PlacedBubble> {
    val ai = bubbles.filter { it.tier == BubbleTier.AI }
    val nonAi = bubbles.filter { it.tier != BubbleTier.AI }
    val near = nonAi.take(likelyCount)
    val mid = nonAi.drop(likelyCount)
    val midInstant = mid.filter { it.tier == BubbleTier.INSTANT }
    val midRest = mid.filter { it.tier != BubbleTier.INSTANT }

    val placed = mutableListOf<PlacedBubble>()
    // The likely few sit on the INNER orbit above the object — the only captioned ring.
    near.forEachIndexed { i, b ->
        placed += PlacedBubble(b, sectorAngle(-PI / 2, near.size, i, PI * 0.194), SpaceRing.NEAR)
    }
    // Everything else shares ONE outer orbit, in non-overlapping arcs so the eye reads
    // a single ring with meaningful sides: instant sweeps the whole left arc, real work
    // takes the upper right, AI sinks along the lower right — leaving your hand's reach.
    midInstant.forEachIndexed { i, b ->
        placed += PlacedBubble(b, sectorAngle(PI, midInstant.size, i, PI * 0.361), SpaceRing.MID)
    }
    midRest.forEachIndexed { i, b ->
        placed += PlacedBubble(b, sectorAngle(-PI * 0.194, midRest.size, i, PI * 0.194), SpaceRing.MID)
    }
    ai.forEachIndexed { i, b ->
        placed += PlacedBubble(b, sectorAngle(PI * 0.319, ai.size, i, PI * 0.125), SpaceRing.FAR)
    }
    return placed
}

/** [count] angles spread across ±[halfRad] around [centerRad]; one lands dead centre. */
private fun sectorAngle(centerRad: Double, count: Int, index: Int, halfRad: Double): Double =
    if (count == 1) norm(centerRad)
    else norm(centerRad - halfRad + index * (2 * halfRad / (count - 1)))

private fun norm(angle: Double): Double = ((angle % (2 * PI)) + 2 * PI) % (2 * PI)
