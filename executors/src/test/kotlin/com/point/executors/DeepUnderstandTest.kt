package com.point.executors

import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The structured LLM fallback (#64, the last slice): a strict line contract — no prose
 * matching (see prefer-strict-format-over-output-matching). The parser is pure and JVM-tested.
 */
class DeepUnderstandTest {

    @Test
    fun `parses the strict KEY=VALUE contract into entity facts`() {
        val answer = """
            PHONE=+380671234567
            DATE=завтра в 18:00
            ADDRESS=ул. Крещатик 12
        """.trimIndent()
        val facts = parseUnderstanding(answer)
        assertEquals("+380671234567", facts[META_ENTITY_PREFIX + "phone"])
        assertEquals("завтра в 18:00", facts[META_ENTITY_PREFIX + "date"])
        assertEquals("ул. Крещатик 12", facts[META_ENTITY_PREFIX + "address"])
    }

    @Test
    fun `ignores unknown keys, prose and NONE`() {
        val answer = """
            Вот что я нашёл:
            PHONE=123
            MOOD=happy
            NONE
        """.trimIndent()
        val facts = parseUnderstanding(answer)
        assertEquals(mapOf(META_ENTITY_PREFIX + "phone" to "123"), facts)
    }

    @Test
    fun `first value per key wins, blanks are dropped`() {
        val answer = "URL=https://a.example\nURL=https://b.example\nEMAIL="
        val facts = parseUnderstanding(answer)
        assertEquals("https://a.example", facts[META_ENTITY_PREFIX + "url"])
        assertNull(facts[META_ENTITY_PREFIX + "email"])
    }

    @Test
    fun `deep understand accepts text and OCR'd images, not raw photos`() {
        val cap = DeepUnderstandCapability()
        assertTrue(cap.accepts(ObjectState(ObjectKind.TEXT)))
        assertTrue(cap.accepts(ObjectState(ObjectKind.IMAGE, setOf(com.point.core.model.Feature.HAS_TEXT))))
        assertEquals(false, cap.accepts(ObjectState(ObjectKind.IMAGE)))
        assertTrue(cap.meta.network)
    }
}
