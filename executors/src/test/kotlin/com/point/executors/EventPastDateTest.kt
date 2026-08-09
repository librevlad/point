package com.point.executors

import com.point.core.flow.GraphState
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #651, слова владельца: «дата в прошлом не может создавать событие».
 * Дата остаётся знанием — но дверь «Создать событие» для прошлого не предлагается.
 */
class EventPastDateTest {

    private fun graph(date: String?) = GraphState(
        PointObject(
            "o", "text/plain", ScratchRef("/tmp/т.txt"),
            ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_DATE)),
            metadata = date?.let { mapOf("entity.date" to it) } ?: emptyMap(),
        ),
    )

    private val capability = EventCapability { LocalDate.of(2026, 8, 9) }

    @Test
    fun `будущая дата открывает дверь события`() {
        assertTrue(capability.accepts(graph("15.08.2026")))
    }

    @Test
    fun `дата в прошлом события не создаёт`() {
        assertFalse(capability.accepts(graph("01.12.2020")))
    }

    @Test
    fun `признак даты без значения — двери нет, пока дата не прочитана`() {
        assertFalse(capability.accepts(graph(null)))
    }

    @Test
    fun `встреча остаётся встречей и без разобранной даты`() {
        val meeting = GraphState(
            PointObject(
                "m", "text/plain", ScratchRef("/tmp/м.txt"),
                ObjectState(ObjectKind.TEXT, setOf(Feature.IS_MEETING)),
            ),
        )

        assertTrue(capability.accepts(meeting))
    }
}
