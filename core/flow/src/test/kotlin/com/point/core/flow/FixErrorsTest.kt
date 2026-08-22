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
        assertEquals("Ошибок не нашлось — знание оставлено как было", fixedMessage(0))
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

    // ---- Правка самого текста (#1023): у текстового объекта знание — его текст ----

    /** Текст из живого прогона владельца: пять опечаток, которых прежде никто не смотрел. */
    private val typed = "Превет, Иван! Эт тестовый тектс с пятью ашибками и опичатками, проверка 17.08.2026."

    private val typedFixed = "Привет, Иван! Это тестовый текст с пятью ошибками и опечатками, проверка 17.08.2026."

    @Test
    fun `в модель уходит сам текст, а не сводка значений`() {
        val prompt = fixTextPrompt(typed)

        assertTrue("текст обязан быть в запросе целиком", typed in prompt)
        assertTrue("модель не знает, чем ответить, если править нечего", FIX_NOTHING in prompt)
    }

    @Test
    fun `правки ложатся в текст, а дельта — то, что легло`() {
        val answer = "Превет = Привет\nЭт = Это\nтектс = текст\nашибками = ошибками\nопичатками = опечатками"

        val fixed = fixText(typed, answer)

        assertEquals(typedFixed, fixed.text)
        assertEquals(5, fixed.fixes.size)
        val said = fixedTextMessage(fixed.fixes)
        fixed.fixes.forEach { fix ->
            assertTrue("в итоге не видно, что было: $said", fix.was in said)
            assertTrue("в итоге не видно, что стало: $said", fix.now in said)
        }
        assertTrue("итог не называет счёт правок: $said", "5" in said)
    }

    @Test
    fun `фрагмент внутри другого слова не трогается`() {
        val before = "Эт пример. Этот же — нет."
        val after = "Это пример. Этот же — нет."

        val fixed = fixText(before, "Эт = Это")

        assertEquals(after, fixed.text)
        assertEquals(1, fixed.fixes.size)
    }

    @Test
    fun `чего в тексте нет — не правится и в дельту не попадает`() {
        val fixed = fixText(typed, "Првет = Привет\n9 = что-то\nпросто проза\n= пусто\nЭт = Эт")

        assertEquals(typed, fixed.text)
        assertTrue("дельта выдумана: ${fixed.fixes}", fixed.fixes.isEmpty())
    }

    @Test
    fun `нечего править — сказано про текст, который проверили, а не про знание вообще`() {
        val said = fixedTextMessage(fixText(typed, FIX_NOTHING).fixes)

        assertTrue("без правок итог не может звучать как «исправлено»: $said", "Исправлено" !in said)
        assertTrue("итог обязан сказать, что именно оставлено: $said", "текст" in said)
    }
}
