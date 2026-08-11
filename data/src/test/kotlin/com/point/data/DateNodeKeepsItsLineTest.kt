package com.point.data

import com.point.core.flow.Entity
import com.point.core.flow.EntityType
import com.point.core.flow.KIND_DATE
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_LINE_SUFFIX
import com.point.core.flow.plausibleEntities
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дни объекта — дни, а подпись при них не становится ни значением, ни вторым днём (#782).
 *
 * Кадр прогона: в разделе «Изображение» шесть строк «Дата» показывали фразу с номером акта,
 * интервал одной строкой, обрывок «4.» и те же дни в чистом виде рядом.
 */
class DateNodeKeepsItsLineTest {

    private val dateKey = META_ENTITY_PREFIX + "date"

    private val inTheAct = "зазначених в Акті від 03.01.2026 № 432/69"

    private val validity = "Дійсний з 05.06.2025 0:00:00 по 04.06.2027 23:59:59"

    private val page = PointObject(
        id = "page",
        mime = "image/jpeg",
        uri = ScratchRef("/tmp/page.jpg"),
        state = ObjectState(ObjectKind.IMAGE),
    )

    /** Так знание приходит с движка: размеченные куски текста, уже прочитанные правилом. */
    private fun seen(vararg raw: String) = plausibleEntities(
        raw.map { Entity(EntityType.DATE_TIME, it) },
    )

    private fun days(vararg raw: String) = entityDelta(page, seen(*raw))
        .objects.filter { it.state.kind == KIND_DATE }

    @Test
    fun `шесть строк с кадра становятся четырьмя днями`() {
        val found = days(inTheAct, validity, "4.", "03.01.2026", "29.04.2026", "05.06.2025 0:00:00")

        assertEquals(
            listOf("03.01.2026", "05.06.2025 0:00:00", "04.06.2027 23:59:59", "29.04.2026"),
            found.map { it.metadata.getValue(dateKey) },
        )
    }

    @Test
    fun `ни один день не является фразой`() {
        val found = days(inTheAct, validity, "4.")

        found.forEach { day ->
            val value = day.metadata.getValue(dateKey)
            assertTrue("значением стала фраза: $value", value.none(Char::isLetter))
        }
    }

    @Test
    fun `строка документа остаётся при своём дне подписью`() {
        val day = days(inTheAct).single()

        assertEquals("03.01.2026", day.metadata.getValue(dateKey))
        assertEquals(inTheAct, day.metadata[dateKey + META_LINE_SUFFIX])
    }

    /** Подпись — контекст значения, а не второе значение: в «ещё» она не попадает. */
    @Test
    fun `подпись не превращается во второй день`() {
        val facts = entityDelta(page, seen(inTheAct, "03.01.2026")).metadata

        assertEquals("03.01.2026", facts[dateKey])
        assertNull("фраза встала «ещё»-значением", facts[dateKey + com.point.core.flow.META_MORE_SUFFIX])
    }

    @Test
    fun `чистому дню подпись не выдумывается`() {
        val day = days("29.04.2026").single()

        assertNull(day.metadata[dateKey + META_LINE_SUFFIX])
    }
}
