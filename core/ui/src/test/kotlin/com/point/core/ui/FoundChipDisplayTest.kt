package com.point.core.ui

import com.point.core.flow.KIND_PHONE
import com.point.core.flow.META_ALT_SUFFIX
import com.point.core.flow.META_AT_REGION
import com.point.core.flow.META_SOURCE_SUFFIX
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.ValueRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Чип найденного объекта показывает текущий факт, а не прежний `uri`:
 * после исправления человеком старое значение — история, а не заголовок.
 */
class FoundChipDisplayTest {

    private fun node(metadata: Map<String, String>) = PointObject(
        id = "img:phone",
        mime = "text/plain",
        uri = ValueRef("111"),
        state = ObjectState(KIND_PHONE),
        metadata = metadata,
    )

    @Test
    fun `телефон, подписанный человеком, вторым узлом не показывается`() {

        // #653: «в идеале я хочу 3 подписанных контакта» — человек с номером внутри
        // заменяет сырой узел номера, а неподписанный номер остаётся виден.
        val person = PointObject(
            id = "doc:person:андріященко",
            mime = "text/plain",
            uri = ValueRef("АНДРІЯЩЕНКО Артур"),
            state = ObjectState(com.point.core.flow.KIND_PERSON),
            metadata = mapOf(
                "graph.role.contact" to "АНДРІЯЩЕНКО Артур",
                "entity.phone" to "+380 66 526 2706",
            ),
        )
        val claimedPhone = node(mapOf("entity.phone" to "+380665262706"))
        val loosePhone = PointObject(
            id = "doc:phone:2",
            mime = "text/plain",
            uri = ValueRef("+380 93 242 37 59"),
            state = ObjectState(KIND_PHONE),
            metadata = mapOf("entity.phone" to "+380 93 242 37 59"),
        )

        val visible = visibleFoundChips(listOf(person, claimedPhone, loosePhone), emptySet())

        assertEquals(listOf("doc:person:андріященко", "doc:phone:2"), visible.map { it.id })
    }

    @Test
    fun `после правки человеком заголовок — новое значение, а не старое uri`() {
        val corrected = node(
            mapOf(
                "entity.phone" to "112",
                "entity.phone" + META_ALT_SUFFIX to "111",
                "entity.phone" + META_SOURCE_SUFFIX to Provenance.HUMAN.wire,
            ),
        )

        assertEquals("112", foundHeadline(corrected))
        assertEquals("прежнее значение — как «или»", "111", otherReading(corrected))
    }

    @Test
    fun `обычный узел показывает своё значение`() {
        val plain = node(mapOf("entity.phone" to "111"))

        assertEquals("111", foundHeadline(plain))
        assertNull(otherReading(plain))
    }

    @Test
    fun `локализация и аннотации заголовком не становятся`() {
        val located = node(
            mapOf(
                META_AT_REGION to "0 0 10 10",
                "entity.phone" to "111",
            ),
        )

        assertEquals("111", foundHeadline(located))
    }

    @Test
    fun `узел без фактов остаётся на uri`() {
        assertEquals("111", foundHeadline(node(emptyMap())))
    }

    @Test
    fun `строка неудач склеивает причины и молчит на пустом`() {
        val failed = listOf(
            com.point.core.flow.FailedInvestigation(com.point.core.model.CapabilityId("qr"), "QR", "изображение не открылось"),
            com.point.core.flow.FailedInvestigation(com.point.core.model.CapabilityId("ocr"), null, "изображение не открылось"),
            com.point.core.flow.FailedInvestigation(com.point.core.model.CapabilityId("period"), null, "документ не читается"),
        )

        assertEquals(
            "Не удалось посмотреть: изображение не открылось; документ не читается",
            failedNote(failed),
        )
        assertNull(failedNote(emptyList()))
    }

    // ---- Этап 9 G1: дедуп chips против readiness-строк идёт по факту, не по uri ----

    @Test
    fun `исправленный узел не дублируется со строкой, показывающей то же значение`() {
        val corrected = node(
            mapOf(
                "entity.phone" to "112",
                "entity.phone" + META_ALT_SUFFIX to "111",
                "entity.phone" + META_SOURCE_SUFFIX to Provenance.HUMAN.wire,
            ),
        )

        assertEquals(emptyList<PointObject>(), visibleFoundChips(listOf(corrected), setOf("112")))
    }

    @Test
    fun `совпадение старого uri со строкой больше никого не прячет`() {

        val corrected = node(mapOf("entity.phone" to "112"))

        assertEquals(listOf(corrected), visibleFoundChips(listOf(corrected), setOf("111")))
    }

    @Test
    fun `уникальный узел остаётся видимым`() {
        val plain = node(mapOf("entity.phone" to "111"))

        assertEquals(listOf(plain), visibleFoundChips(listOf(plain), setOf("+380000000000")))
    }
}
