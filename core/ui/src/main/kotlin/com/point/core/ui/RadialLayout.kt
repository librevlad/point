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
    // The likely few fan out ABOVE the object — the only place a narrow screen always
    // has clear of both the object and the side columns.
    near.forEachIndexed { i, b ->
        placed += PlacedBubble(b, sectorAngle(-PI / 2, near.size, i, PI / 4), SpaceRing.NEAR)
    }
    midInstant.forEachIndexed { i, b ->
        placed += PlacedBubble(b, sectorAngle(PI, midInstant.size, i, PI / 3), SpaceRing.MID)
    }
    midRest.forEachIndexed { i, b ->
        placed += PlacedBubble(b, sectorAngle(0.0, midRest.size, i, PI / 3), SpaceRing.MID)
    }
    // AI sinks to the lower-right arc — starting below the right column's tail,
    // wide enough that several cloud actions string out instead of piling up.
    ai.forEachIndexed { i, b ->
        placed += PlacedBubble(b, sectorAngle(PI * 0.34, ai.size, i, PI * 0.15), SpaceRing.FAR)
    }
    return placed
}

/** [count] angles spread across ±[halfRad] around [centerRad]; one lands dead centre. */
private fun sectorAngle(centerRad: Double, count: Int, index: Int, halfRad: Double): Double =
    if (count == 1) norm(centerRad)
    else norm(centerRad - halfRad + index * (2 * halfRad / (count - 1)))

private fun norm(angle: Double): Double = ((angle % (2 * PI)) + 2 * PI) % (2 * PI)
