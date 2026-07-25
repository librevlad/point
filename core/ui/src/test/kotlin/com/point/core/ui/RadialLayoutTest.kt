package com.point.core.ui

import com.point.core.model.Bubble
import com.point.core.model.BubbleTier
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

/**
 * #115 slice 2 — the object's space: bubbles place THEMSELVES around the object.
 * The semantics live in pure geometry: likely actions on the near ring, everything
 * heavier further out, AI always on the far ring and in the right hemisphere
 * (the action leaves your hand), instant actions to the left (they stay in it).
 */
class RadialLayoutTest {

    private fun bubble(id: String, tier: BubbleTier) = Bubble(
        icon = id,
        title = id,
        capabilityId = CapabilityId(id),
        expectedNextState = ObjectState(ObjectKind.IMAGE),
        tier = tier,
    )

    @Test
    fun `likely non-AI actions sit on the near ring`() {
        val placed = radialPlacement(
            listOf(
                bubble("scan", BubbleTier.SMART),
                bubble("copy", BubbleTier.INSTANT),
            ),
            likelyCount = 2,
        )
        assertTrue(placed.all { it.ring == SpaceRing.NEAR })
    }

    @Test
    fun `AI is always far - even when ranked likely`() {
        val placed = radialPlacement(
            listOf(bubble("ai", BubbleTier.AI), bubble("scan", BubbleTier.SMART)),
            likelyCount = 2,
        )
        assertEquals(SpaceRing.FAR, placed.first { it.bubble.capabilityId.value == "ai" }.ring)
        assertEquals(SpaceRing.NEAR, placed.first { it.bubble.capabilityId.value == "scan" }.ring)
    }

    @Test
    fun `folded non-AI actions take the middle ring`() {
        val placed = radialPlacement(
            listOf(
                bubble("a", BubbleTier.SMART), bubble("b", BubbleTier.INSTANT),
                bubble("c", BubbleTier.INSTANT), bubble("d", BubbleTier.SMART),
            ),
            likelyCount = 3,
        )
        assertEquals(SpaceRing.MID, placed.first { it.bubble.capabilityId.value == "d" }.ring)
    }

    @Test
    fun `AI bubbles lean into the right hemisphere`() {
        val placed = radialPlacement(
            listOf(
                bubble("ai1", BubbleTier.AI), bubble("ai2", BubbleTier.AI),
                bubble("scan", BubbleTier.SMART),
            ),
            likelyCount = 3,
        )
        placed.filter { it.bubble.tier == BubbleTier.AI }.forEach {
            assertTrue("angle ${it.angleRad} must face right", cos(it.angleRad) > 0.0)
        }
    }

    @Test
    fun `mid-ring instant actions lean left, smart lean right`() {
        val placed = radialPlacement(
            listOf(
                bubble("top1", BubbleTier.SMART), bubble("top2", BubbleTier.SMART),
                bubble("top3", BubbleTier.SMART),
                bubble("in", BubbleTier.INSTANT), bubble("sm", BubbleTier.SMART),
            ),
            likelyCount = 3,
        )
        val instant = placed.first { it.bubble.capabilityId.value == "in" }
        val smart = placed.first { it.bubble.capabilityId.value == "sm" }
        assertTrue(cos(instant.angleRad) < 0.0)
        assertTrue(cos(smart.angleRad) > 0.0)
    }

    @Test
    fun `angles on a ring never collide`() {
        val placed = radialPlacement(
            (1..6).map { bubble("b$it", BubbleTier.SMART) },
            likelyCount = 3,
        )
        placed.groupBy { it.ring }.forEach { (_, ringMates) ->
            val angles = ringMates.map { it.angleRad }
            assertEquals(angles.size, angles.distinct().size)
            angles.forEach { assertTrue(it >= 0.0 && it < 2 * PI + 1e-6) }
        }
    }
}
