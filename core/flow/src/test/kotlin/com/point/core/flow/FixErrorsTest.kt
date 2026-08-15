package com.point.core.flow

import com.point.core.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Исправить ошибки» (#666): знание уходит в модель и возвращается исправленным.
 * Примеры взяты из карточки владельца — реальные опечатки распознавания.
 */
class FixErrorsTest {

    private val known = mapOf(
        META_GRAPH_ROLE_PREFIX + "sender" to "Паринкн",
        META_ENTITY_PREFIX + "date" to "ад! 01.12.2020",
        META_ENTITY_ADDRESS to "Бритовка, ZeHTpaJIbHa, 586",
    )

    private fun facts() = fixableFacts(known)

    /** Номер значения в промпте: порядок задаёт сам механизм, тест его не выдумывает. */
    private fun numberOf(key: String) = facts().indexOfFirst { it.key == key } + 1

    @Test
    fun `исправленное становится главным, прежнее остаётся в «или»`() {
        val fixes = parseFixes("${numberOf(META_GRAPH_ROLE_PREFIX + "sender")} = Паринкін", facts())

        val patch = applyFixes(known, fixes)

        assertEquals("Паринкін", patch[META_GRAPH_ROLE_PREFIX + "sender"])
        assertEquals(
            "прежнее значение обязано остаться следом",
            altValue(listOf("Паринкн")),
            patch[META_GRAPH_ROLE_PREFIX + "sender" + META_ALT_SUFFIX],
        )
        assertEquals(Provenance.MODEL.wire, patch[META_GRAPH_ROLE_PREFIX + "sender" + META_SOURCE_SUFFIX])
    }

    @Test
    fun `подтверждённое человеком в модель не отдаётся вовсе (ADR §8)`() {
        val confirmed = known + mapOf(
            META_GRAPH_ROLE_PREFIX + "sender" + META_SOURCE_SUFFIX to Provenance.HUMAN.wire,
        )

        assertTrue(
            "слово человека ушло на исправление",
            fixableFacts(confirmed).none { it.key == META_GRAPH_ROLE_PREFIX + "sender" },
        )
    }

    @Test
    fun `исправленное проходит те же проверки формы, что и найденное впервые`() {
        // Модель «исправила» дату в относительное слово — такое значение датой не бывает (#659).
        val fixes = parseFixes("${numberOf(META_ENTITY_PREFIX + "date")} = завтра", facts())

        assertTrue("негодная форма прошла как исправление", fixes.isEmpty())
    }

    @Test
    fun `номер не из выданных и мусор молчат`() {
        val fixes = parseFixes("9 = что-то\nпросто проза\n= пусто", facts())

        assertTrue(fixes.isEmpty())
    }

    @Test
    fun `то же самое значение исправлением не считается`() {
        val fixes = parseFixes("${numberOf(META_GRAPH_ROLE_PREFIX + "sender")} = Паринкн", facts())

        assertTrue(fixes.isEmpty())
        assertTrue(applyFixes(known, fixes).isEmpty())
    }

    @Test
    fun `нечего исправлять — про это сказано словами, а не пустотой`() {
        assertEquals("В прочитанном ошибок не нашлось", fixedMessage(0))
        assertEquals("Исправлено: 2", fixedMessage(2))
    }

    @Test
    fun `дверь появляется только там, где есть что исправлять`() {
        assertTrue(hasFixableFacts(known))
        assertFalse(hasFixableFacts(emptyMap()))
        assertFalse(
            "аннотации знанием не являются",
            hasFixableFacts(mapOf(META_ENTITY_PREFIX + "date" + META_ALT_SUFFIX to "что-то")),
        )
    }

    @Test
    fun `в промпт уходят все значения под своими номерами`() {
        val prompt = fixPrompt(facts(), withObject = false)

        facts().forEachIndexed { i, f -> assertTrue("${i + 1} = ${f.value}" in prompt) }
        assertFalse("снимок не прикладывался — про него молчим", "снимком" in prompt)
    }

    @Test
    fun `«сильнее» говорит модели, что снимок приложен`() {
        assertTrue("снимком" in fixPrompt(facts(), withObject = true))
    }
}
