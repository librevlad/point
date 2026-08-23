package com.point.core.ui

import com.point.core.flow.KIND_PERSON
import com.point.core.flow.KIND_PHONE
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_GRAPH_ROLE_PREFIX
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Relation
import com.point.core.model.RelationType
import com.point.core.model.ValueRef
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чей это номер (#1176).
 *
 * Номер отправителя и номер получателя выглядят одинаково, и связь меняет смысл строки:
 * без имени рядом человек не знает, кому звонит. Имя берётся у самого узла стороны — второй
 * копии значения при номере не заводится.
 *
 * Обратное так же важно: связи нет — подписи нет. Догадка вместо знания хуже молчания.
 */
class FoundValueNamesItsOwnerTest {

    private val name = "Тарасенко Світлана Сергіївна"

    private val number = "067 636 05 60"

    private val phone = PointObject(
        id = "doc:phone",
        mime = "text/plain",
        uri = ValueRef(number),
        state = ObjectState(KIND_PHONE),
        metadata = mapOf(META_ENTITY_PREFIX + "phone" to number),
    )

    private val person = PointObject(
        id = "doc:party:тарасенко світлана сергіївна",
        mime = "text/plain",
        uri = ValueRef(name),
        state = ObjectState(KIND_PERSON),
        metadata = mapOf(META_GRAPH_ROLE_PREFIX + "sender" to name),
    )

    private val found = listOf(phone, person)

    @Test
    fun `номер подписан именем своего хозяина`() {
        val relations = listOf(Relation(phone.id, RelationType.BELONGS_TO, person.id))

        assertTrue(ownerOfFound(phone, relations, found)?.endsWith(name) == true)
    }

    /**
     * Строка свойств перечисляет категории, и голое имя среди них читается как ещё одна:
     * «Место · Лумброван» выглядит так, будто место называется Лумброван (#1176). Связь
     * называется словом — ролью стороны, которая уже известна из её узла.
     */
    @Test
    fun `имя хозяина не встаёт голым среди слов-категорий`() {
        val relations = listOf(Relation(phone.id, RelationType.BELONGS_TO, person.id))

        val said = ownerOfFound(phone, relations, found)!!

        assertNotEquals(name, said)
        assertTrue(said.startsWith(relationLabel(RelationType.SENDER)!!.dropLast(1)))
    }

    @Test
    fun `роль стороны не названа — подпись всё равно говорит, что это чей-то номер`() {
        val contact = person.copy(metadata = mapOf(META_GRAPH_ROLE_PREFIX + "contact" to name))
        val relations = listOf(Relation(phone.id, RelationType.BELONGS_TO, person.id))

        val said = ownerOfFound(phone, relations, listOf(phone, contact))!!

        assertTrue(said.endsWith(name))
        assertTrue(said.length > name.length)
    }

    @Test
    fun `связи нет — подписи нет`() {
        val relations = listOf(Relation(phone.id, RelationType.FOUND_IN, "doc"))

        assertNull(ownerOfFound(phone, relations, found))
    }

    @Test
    fun `сторона ещё не стала узлом — Point её не выдумывает`() {
        val relations = listOf(Relation(phone.id, RelationType.BELONGS_TO, person.id))

        assertNull(ownerOfFound(phone, relations, found = listOf(phone)))
    }
}
