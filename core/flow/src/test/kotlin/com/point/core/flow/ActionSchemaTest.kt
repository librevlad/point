package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Полнота считается по действию (#260, design v3 §6): у действия есть критические поля,
 * готовность бинарна, и «не хватает только X» называет только то, без чего действие не
 * работает. «Заполнено 6 из 9» — красивая ложь, и её здесь никто не считает.
 */
class ActionSchemaTest {

    private val track = ActionSchema(
        id = "t",
        label = "Отследить отправление",
        fields = listOf(
            FieldSpec("entity.track", "трек-номер", critical = true),
            FieldSpec("graph.role.carrier", "перевозчик"),
            FieldSpec("entity.date", "дата"),
        ),
    )

    @Test
    fun `критическое поле есть — действие готово, побочные не мешают`() {
        val r = track.readiness(mapOf("entity.track" to "20 4514 9154 9395"))

        assertTrue(r is Readiness.Ready)
        assertEquals("20 4514 9154 9395", (r as Readiness.Ready).present.single().value)
    }

    @Test
    fun `критического нет — не готово, и не хватает называется только критическое`() {
        // Перевозчик и дата прочитаны, трека нет: «не хватает только трек-номер», а не
        // список всего незаполненного — иначе это та же форма из девяти полей.
        val r = track.readiness(mapOf("graph.role.carrier" to "Нова Пошта", "entity.date" to "29.07"))

        assertTrue(r is Readiness.Missing)
        assertEquals(listOf("трек-номер"), (r as Readiness.Missing).missing.map { it.label })
        assertEquals(2, r.present.size)
    }

    @Test
    fun `пустое значение — не значение`() {
        assertTrue(track.readiness(mapOf("entity.track" to "  ")) is Readiness.Missing)
    }

    @Test
    fun `спорное чтение видно на поле — готовность не прячет спор`() {
        val facts = mapOf(
            "entity.track" to "20 4514 9154 9395",
            "entity.track.alt" to altValue(listOf("20 4514 9154 9395", "20 4514 9154 9396")),
        )

        val r = track.readiness(facts) as Readiness.Ready

        assertEquals(listOf("20 4514 9154 9396"), r.present.single().alternatives)
    }

    @Test
    fun `дайджест показывает только действия, к которым документ имеет отношение`() {
        // На скрине посылки нет телефона — «Сохранить контакт» не показывается вовсе,
        // даже как «не готово»: пустой опросник с минусами тем же самым опросником и остался бы.
        val rows = actionReadiness(mapOf(META_ENTITY_TRACK to "20 4514 9154 9395"))

        assertEquals(listOf("track-parcel"), rows.map { it.schema.id })
        assertTrue(rows.single().readiness is Readiness.Ready)
    }

    @Test
    fun `неготовое действие видно, когда прочитано хоть одно его поле`() {
        val rows = actionReadiness(mapOf(META_GRAPH_ROLE_PREFIX + "carrier" to "Нова Пошта"))

        val parcel = rows.single { it.schema.id == "track-parcel" }
        assertTrue(parcel.readiness is Readiness.Missing)
    }

    @Test
    fun `пустой документ не получает ни одной строки готовности`() {
        assertTrue(actionReadiness(emptyMap()).isEmpty())
    }

    @Test
    fun `контакт готов по телефону — почта и адрес весят почти ноль`() {
        val rows = actionReadiness(mapOf(META_ENTITY_PREFIX + "phone" to "+380671234567"))

        val contact = rows.single { it.schema.id == "save-contact" }
        assertTrue(contact.readiness is Readiness.Ready)
    }

    @Test
    fun `в каждой схеме есть критическое поле — схема без критического всегда готова и лжёт`() {
        ACTION_SCHEMAS.forEach { schema ->
            assertTrue("${schema.id} должна иметь критическое поле", schema.fields.any { it.critical })
        }
    }

    @Test
    fun `ключи полей — ключи метаданных, а не собственный мир значений`() {
        val known = listOf(META_ENTITY_PREFIX, META_GRAPH_ROLE_PREFIX)
        ACTION_SCHEMAS.flatMap { it.fields }.forEach { field ->
            assertTrue(
                "${field.key} должен быть фактом объекта",
                known.any { field.key.startsWith(it) },
            )
        }
    }
}
