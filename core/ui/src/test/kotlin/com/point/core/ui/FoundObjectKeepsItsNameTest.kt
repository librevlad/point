package com.point.core.ui

import com.point.core.flow.KIND_PERSON
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_GRAPH_ROLE_PREFIX
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.model.ValueRef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Вошёл в найденное — оно осталось собой (#769).
 *
 * Живая охота 11.08.2026 на почтовой наклейке: в списке карточка подписана «Лумброван
 * Олександ р Миколайович», а внутри объект назывался просто «Человек». Вход терял имя —
 * главное, что о человеке известно.
 */
class FoundObjectKeepsItsNameTest {

    private fun person(name: String) = PointObject(
        id = "src:party:$name",
        mime = "text/plain",
        uri = ValueRef(name),
        state = ObjectState(KIND_PERSON, setOf(Feature.HAS_PHONE)),
        metadata = mapOf(
            META_GRAPH_ROLE_PREFIX + "sender" to name,
            META_ENTITY_PREFIX + "phone" to "067 636 05 60",
        ),
    )

    @Test
    fun `человек назван своим именем, а вид уходит подписью`() {
        val name = "Думброван Олександр Миколайович"

        val verdict = objectVerdict(person(name))

        assertEquals(name, verdict.headline)
        assertEquals(kindLabel(KIND_PERSON), verdict.subline)
    }

    @Test
    fun `телефон человека виден внутри него`() {
        val facts = understoodFacts(person("Думброван Олександр Миколайович"))

        // Показывается по-человечески (#932), но без выдуманной страны (#1029): номер записан
        // без кода страны и годится не одной стране — значит на экране он стоит так, как
        // прочитан в документе.
        assertEquals(listOf("067 636 05 60"), facts.filter { it.key == "phone" }.map { it.value })
    }

    @Test
    fun `файл своё название не меняет — правило только про найденные значения`() {
        val image = PointObject(
            id = "src",
            mime = "image/jpeg",
            uri = ScratchRef("/scratch/np-label.jpg"),
            state = ObjectState(ObjectKind.IMAGE),
            metadata = mapOf(META_ENTITY_PREFIX + "phone" to "067 636 05 60"),
        )

        assertEquals(kindLabel(ObjectKind.IMAGE), objectVerdict(image).headline)
    }
}
