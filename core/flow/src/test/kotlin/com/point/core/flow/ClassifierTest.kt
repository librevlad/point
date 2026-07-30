package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract that makes a model's answer checkable (#222, шаг 6).
 *
 * Today an LLM answer is prose, and a confident wrong one is indistinguishable from a right one.
 * Here the answer is a reference: `P7` is either among the elements or it is not, and code — not
 * a person reading the screen — decides. Most of these tests are about what gets thrown away.
 */
class ClassifierTest {

    private val elements = layoutOf(
        """
        Нова Пошта
        ТОВ «Агротрейд»
        Відділення №9, вул. Хрещатик, 1
        20 4514 9154 9395
        """.trimIndent(),
    )

    private fun roleOf(list: List<Classified>, key: String) =
        list.firstOrNull { it.role.key == key }?.element?.text

    // --- The layout ---

    @Test
    fun `each line becomes an addressable element`() {
        assertEquals(listOf("P1", "P2", "P3", "P4"), elements.map { it.id })
        assertEquals("Нова Пошта", elements.first().text)
    }

    @Test
    fun `blank lines get no id, so the numbering means something`() {
        assertEquals(listOf("P1", "P2"), layoutOf("a\n\n   \nb").map { it.id })
    }

    @Test
    fun `a huge document is capped, not swallowed whole`() {
        val many = (1..500).joinToString("\n") { "строка $it" }

        assertEquals(MAX_LAYOUT_ELEMENTS, layoutOf(many).size)
    }

    // --- The prompt: what the model is and is not shown ---

    @Test
    fun `the prompt carries the elements and the roles`() {
        val prompt = classifierPrompt(elements)

        assertTrue(prompt.contains("P2: ТОВ «Агротрейд»"))
        CLASSIFIER_ROLES.forEach { assertTrue("роль ${it.key}", prompt.contains(it.key)) }
    }

    @Test
    fun `the prompt names no kind and no relation — the graph has no route in`() {
        // The guarantee is structural: classifierPrompt takes elements and roles, and there is no
        // parameter through which a PointObject could arrive. This asserts the vocabulary too.
        val prompt = classifierPrompt(elements)

        assertFalse(prompt.contains("Organization"))
        assertFalse(prompt.contains(KIND_ORGANIZATION.name))
        assertFalse(prompt.contains("PointObject"))
        assertFalse(prompt.contains("issued_by"))
    }

    // --- The answer: everything that is not a real reference is dropped ---

    @Test
    fun `a good answer becomes roles pointing at real elements`() {
        val found = parseClassification("carrier=P1\nsender=P2", elements)

        assertEquals("Нова Пошта", roleOf(found, "carrier"))
        assertEquals("ТОВ «Агротрейд»", roleOf(found, "sender"))
    }

    @Test
    fun `an id that does not exist is dropped, however confident the model sounded`() {
        assertTrue(parseClassification("sender=P99", elements).isEmpty())
    }

    @Test
    fun `the element's text instead of its id is dropped — that is prose in an id's hat`() {
        assertTrue(parseClassification("sender=ТОВ «Агротрейд»", elements).isEmpty())
    }

    @Test
    fun `an invented role is dropped`() {
        assertTrue(parseClassification("грузополучатель=P2", elements).isEmpty())
    }

    @Test
    fun `explaining itself instead of answering yields nothing`() {
        val chatty = "Конечно! Отправителем является ТОВ «Агротрейд», а перевозчиком Нова Пошта."

        assertTrue(parseClassification(chatty, elements).isEmpty())
    }

    @Test
    fun `«ничего не нашёл» is an answer, not a parse failure`() {
        assertTrue(parseClassification(CLASSIFIER_NOTHING, elements).isEmpty())
    }

    @Test
    fun `a bad guess does not use up its role — the valid line after it still counts`() {
        // Otherwise a model that guesses once and then answers properly loses the good answer.
        val found = parseClassification("sender=P42\nsender=P2", elements)

        assertEquals("ТОВ «Агротрейд»", roleOf(found, "sender"))
    }

    @Test
    fun `repeating a role does not double it — the first valid reading wins`() {
        val found = parseClassification("sender=P2\nsender=P1", elements)

        assertEquals(1, found.size)
        assertEquals("ТОВ «Агротрейд»", roleOf(found, "sender"))
    }

    @Test
    fun `one element may fill two roles — a carrier can also issue the document`() {
        val found = parseClassification("carrier=P1\nissuer=P1", elements)

        assertEquals(2, found.size)
        assertTrue(found.all { it.element.id == "P1" })
    }

    @Test
    fun `good and garbage in one answer keeps the good`() {
        val mixed = "sender=P2\nдумаю что это накладная\nreceiver=нет данных\ncarrier=p1"

        val found = parseClassification(mixed, elements)

        assertEquals("ТОВ «Агротрейд»", roleOf(found, "sender"))
        assertEquals("Нова Пошта", roleOf(found, "carrier")) // lowercase id still resolves
        assertEquals(null, roleOf(found, "receiver"))
    }

    // --- The role table: the part a human owns ---

    @Test
    fun `every role names a kind and a relation, so code never has to guess`() {
        CLASSIFIER_ROLES.forEach { role ->
            assertTrue("ключ роли должен быть ascii-словом", role.key.matches(Regex("[a-z_]+")))
            assertTrue("${role.key} должна иметь вопрос", role.question.isNotBlank())
            assertTrue("${role.key} должна давать извлечённый вид", role.kind in EXTRACTED_KINDS)
        }
    }

    @Test
    fun `role keys are unique`() {
        assertEquals(CLASSIFIER_ROLES.size, CLASSIFIER_ROLES.mapTo(mutableSetOf()) { it.key }.size)
    }
}
