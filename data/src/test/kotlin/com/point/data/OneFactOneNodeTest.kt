package com.point.data

import com.point.core.flow.Entity
import com.point.core.flow.EntityType
import com.point.core.flow.META_ENTITY_ADDRESS
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.alternativesOf
import com.point.core.flow.moreOf
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор текста различает прочтения одного факта и разные сущности (#1122, #1109).
 *
 * Раньше он различал их по буквальному совпадению строки: адрес, прочитанный второй раз с
 * прилипшим отчеством получателя, становился вторым местом, и человеку показывали два адреса
 * там, где на бумаге один.
 */
class OneFactOneNodeTest {

    private val source = PointObject(
        id = "накладная",
        mime = "text/plain",
        uri = ScratchRef("/dev/null"),
        state = ObjectState(ObjectKind.TEXT),
    )

    private fun found(vararg entities: Entity) = entityDelta(source, entities.toList())

    @Test
    fun `один адрес, прочитанный дважды, остаётся одним знанием со спором`() {
        val delta = found(
            Entity(EntityType.ADDRESS, "М. ПАВЛОГРАД, ВУЛ. КОДАЦЬКА, 39."),
            Entity(EntityType.ADDRESS, "ЕВГЕНІИВНА М. ПАВЛОГРАД, ВУЛ. КОДАЦЬКА, 39"),
        )

        assertEquals(
            "второе прочтение не должно становиться вторым местом",
            emptyList<String>(),
            moreOf(delta.metadata, META_ENTITY_ADDRESS),
        )
        assertEquals(1, delta.objects.count { it.state.kind == com.point.core.flow.KIND_ADDRESS })

        val kept = alternativesOf(delta.metadata, META_ENTITY_ADDRESS)
        assertTrue("проигравшее прочтение затёрто молча-$kept", kept.isNotEmpty())
    }

    @Test
    fun `два разных телефона остаются двумя знаниями`() {
        val delta = found(
            Entity(EntityType.PHONE, "+380671234567"),
            Entity(EntityType.PHONE, "+380509876543"),
        )

        val phone = META_ENTITY_PREFIX + "phone"
        assertEquals(listOf("+380509876543"), moreOf(delta.metadata, phone))
        assertEquals(
            "два телефона — не спор одного",
            emptyList<String>(),
            alternativesOf(delta.metadata, phone),
        )
        assertEquals(2, delta.objects.count { it.state.kind == com.point.core.flow.KIND_PHONE })
    }

    @Test
    fun `тот же день с временем и без даёт один узел`() {
        val delta = found(
            Entity(EntityType.DATE_TIME, "16.04.2026"),
            Entity(EntityType.DATE_TIME, "16.04.2026 09:02:50"),
        )

        val dates = delta.objects.filter { it.state.kind == com.point.core.flow.KIND_DATE }
        assertEquals(1, dates.size)
        assertEquals("16.04.2026 09:02:50", dates.single().uri.value)
    }

    @Test
    fun `повторное прочтение того же значения не заводит второй узел`() {
        val delta = found(
            Entity(EntityType.ADDRESS, "вул. Кодацька, 39"),
            Entity(EntityType.ADDRESS, "вул. Кодацька, 39"),
        )

        assertEquals(1, delta.objects.count { it.state.kind == com.point.core.flow.KIND_ADDRESS })
    }
}
