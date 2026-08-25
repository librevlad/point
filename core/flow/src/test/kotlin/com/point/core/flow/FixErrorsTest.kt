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

    /** Итог про текст, проверенный целиком. */
    private fun whole(fixed: FixedText) = fixedTextMessage(fixed, checked = fixed.text.length, total = fixed.text.length)

    @Test
    fun `правки ложатся в текст, а дельта — то, что легло`() {
        val answer = "Превет = Привет\nЭт = Это\nтектс = текст\nашибками = ошибками\nопичатками = опечатками"

        val fixed = fixText(typed, answer)

        assertEquals(typedFixed, fixed.text)
        assertEquals(5, fixed.fixes.size)
        val said = whole(fixed)
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
    fun `чего в тексте нет — не правится, но и не молчит — это предложенная правка, которая не легла`() {
        val fixed = fixText(typed, "Првет = Привет\n9 = что-то\nпросто проза\n= пусто\nЭт = Эт")

        assertEquals(typed, fixed.text)
        assertTrue("дельта выдумана: ${fixed.fixes}", fixed.fixes.isEmpty())
        assertTrue("пара, которая не легла, обязана быть видна: ${fixed.missed}", TextFix("Првет", "Привет", places = 0) in fixed.missed)
        assertTrue("«то же самое» — не правка и не срыв", fixed.missed.none { it.was == "Эт" })
        assertTrue("проза и пустота — не пары", fixed.missed.none { it.was.isEmpty() || "проза" in it.was })
    }

    @Test
    fun `кавычки из образца запроса вокруг сторон или всей строки правку не срывают`() {
        val quoted = "«Превет» = «Привет»\n«Эт = Это»\n\"тектс\" = \"текст\"\n«ашибками» = ошибками"

        val fixed = fixText(typed, quoted)

        assertEquals(4, fixed.fixes.size)
        assertTrue("ни одна пара не должна сорваться из-за кавычек: ${fixed.missed}", fixed.missed.isEmpty())
        assertTrue(fixed.text.startsWith("Привет, Иван! Это тестовый текст с пятью ошибками"))
    }

    @Test
    fun `фрагмент, стоящий в тексте в кавычках, правится внутри них`() {
        val after = "Он сказал «привет» и ушёл."

        val fixed = fixText("Он сказал «превет» и ушёл.", "«превет» = «привет»")

        assertEquals(after, fixed.text)
    }

    @Test
    fun `счёт в итоге — по местам, где правка легла, а не по парам`() {
        val fixed = fixText("Эт раз, эт два, эт три.", "эт = это")

        assertEquals(1, fixed.fixes.size)
        assertEquals(2, fixed.fixes.single().places)
        val said = whole(fixed)
        assertTrue("итог обязан считать места: $said", "Исправлено: 2" in said)
        assertTrue("повтор обязан быть виден у пары: $said", "×2" in said)
    }

    @Test
    fun `перечисление правок ограничено, остальное названо числом`() {
        val words = (1..30).map { "слово$it" }
        val text = words.joinToString(" ")
        val answer = words.joinToString("\n") { "$it = ${it.replace("слово", "слова")}" }

        val fixed = fixText(text, answer)
        val said = whole(fixed)

        assertEquals(30, fixed.fixes.size)
        assertTrue("итог разросся без предела: ${said.length}", said.length < 260)
        assertTrue("остаток обязан быть назван числом: $said", Regex("и ещё \\d+").containsMatchIn(said))
        assertTrue("счёт — по всем правкам, а не по показанным: $said", "Исправлено: 30" in said)
    }

    @Test
    fun `предложенные, но не легшие правки названы числом рядом с легшими`() {
        val said = whole(fixText(typed, "Превет = Привет\nПрвет = Привет\nЭтт = Это"))

        assertTrue("две правки не легли, и об этом сказано: $said", "2 применить не удалось" in said)
    }

    @Test
    fun `нечего править — сказано про текст, который проверили, а не про знание вообще`() {
        val said = whole(fixText(typed, FIX_NOTHING))

        assertTrue("без правок итог не может звучать как «исправлено»: $said", "Исправлено" !in said)
        assertTrue("итог обязан сказать, что именно оставлено: $said", "текст" in said)
    }

    @Test
    fun `окно запроса — не весь текст, и итог говорит, какая часть проверена`() {
        val long = (1..5000).joinToString(" ") { "слово$it" }
        val window = fixTextWindow(long)

        assertTrue("окно обязано быть короче разведочного предела", window.length <= KNOWN_TEXT_LIMIT)
        assertTrue("окно не рвёт слово пополам", long.startsWith(window) && long[window.length] == ' ')

        val silent = fixedTextMessage(fixText(long, FIX_NOTHING), checked = window.length, total = long.length)
        assertTrue("вердикт о непроверенном хвосте недопустим: $silent", "проверено начало" in silent)
        assertTrue(
            "«не нашлось» про весь текст нельзя: $silent",
            !silent.startsWith("Ошибок не нашлось"),
        )

        val fixed = fixedTextMessage(fixText(long, "слово1 = слова1"), checked = window.length, total = long.length)
        assertTrue("и при правках проверенная часть названа: $fixed", "проверено начало" in fixed)
        assertTrue("правка уходит во весь текст, а не только в окно", fixText(long, "слово1 = слова1").text.endsWith("слово5000"))
    }

    @Test
    fun `окно целого короткого текста — он сам, и про «начало» итог молчит`() {
        assertEquals(typed, fixTextWindow(typed))
        assertTrue("проверено начало" !in whole(fixText(typed, FIX_NOTHING)))
    }

    // ---- Значения, вычитанные из текста, следуют за его правкой ----

    @Test
    fun `значение из текста правится теми же парами, что и текст`() {
        val facts = fixableFacts(known)
        val fixes = fixText("Відправник: Паринкн, Бритовка, ZeHTpaJIbHa, 586", "Паринкн = Паринкін\nZeHTpaJIbHa = Центральна").fixes

        val patch = fixesForFacts(facts, fixes)

        assertEquals(fixes[0].now, patch[META_GRAPH_ROLE_PREFIX + "sender"])
        assertEquals(known.getValue(META_ENTITY_ADDRESS).replace(fixes[1].was, fixes[1].now), patch[META_ENTITY_ADDRESS])
        assertTrue("значение, которого правка не касалась, не трогается", META_ENTITY_PREFIX + "date" !in patch)
    }

    @Test
    fun `значение следует за текстом через ту же проверку формы`() {
        val date = listOf(FixableFact(META_ENTITY_PREFIX + "date", "01.12.2020"))

        // Правка текста превратила бы дату в относительное слово — такое значение датой не бывает (#659).
        assertTrue(fixesForFacts(date, listOf(TextFix("01.12.2020", "завтра"))).isEmpty())
    }

    @Test
    fun `накладная судится по той же странице, что и найденная впервые`() {
        val was = "8806923102858"
        val now = "8806923102859"
        val track = listOf(FixableFact(META_ENTITY_TRACK, was))
        val fixes = listOf(TextFix(was, now))

        // Тринадцать цифр — накладная только по слову рядом (#1032). Страница у правки та же,
        // что и у первого чтения, иначе правка знания молча выбрасывалась бы гейтом формы.
        assertEquals(now, fixesForFacts(track, fixes, "Експрес-накладна № $now")[META_ENTITY_TRACK])
        assertTrue("без слова рядом тринадцать цифр накладной не становятся", fixesForFacts(track, fixes, now).isEmpty())
    }
}
