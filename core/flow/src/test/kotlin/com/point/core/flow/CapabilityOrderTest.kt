package com.point.core.flow

import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Порядок действий — одно правило на оба устройства (#840).
 *
 * Оно было в двух копиях: у компьютера с оговоркой «клаузула повторена», у телефона — в
 * хвосте своей политики. Норма конституции (§8) в двух местах — приглашение к расхождению.
 */
class CapabilityOrderTest {

    private class Door(
        id: String,
        priority: Int = 50,
        private val serves: Set<Intent> = setOf(Intent.PREPARE),
    ) : Capability {
        override val id = CapabilityId(id)
        override val icon = "pdf"
        override val meta = CapabilityMeta(priority = priority)
        override fun label(state: ObjectState) = id.value
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = state
        override fun intents(state: ObjectState) = serves
    }

    private val page = ObjectState(ObjectKind.PDF)

    private fun order(intent: Intent?, doors: List<Capability>) =
        doors.sortedWith(byIntentThenPriority(page, intent)).map { it.id.value }

    @Test
    fun `без намерения порядок задаёт приоритет, а при равенстве — имя`() {
        val doors = listOf(Door("я", 10), Door("а", 10), Door("первое", 1))

        assertEquals(listOf("первое", "а", "я"), order(intent = null, doors = doors))
    }

    @Test
    fun `намерение поднимает совпадающее по смыслу`() {
        val doors = listOf(
            Door("готовит", priority = 90, serves = setOf(Intent.PREPARE)),
            Door("открывает", priority = 10, serves = setOf(Intent.OPEN)),
        )

        assertEquals(listOf("готовит", "открывает"), order(Intent.PREPARE, doors))
    }

    @Test
    fun `намерение никого не убирает — это норма конституции`() {
        val doors = listOf(
            Door("готовит", serves = setOf(Intent.PREPARE)),
            Door("открывает", serves = setOf(Intent.OPEN)),
            Door("шлёт", serves = setOf(Intent.SEND)),
        )

        assertEquals(3, order(Intent.OPEN, doors).size)
        assertTrue(order(Intent.OPEN, doors).contains("шлёт"))
    }
}
