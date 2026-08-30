package com.point.checks

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * К шестерёнке Point не зовёт — ни словом, ни знаком, — кроме шапки окна компьютера (#462,
 * #1318), а у каждого слова настроек один дом на все модули (#1249).
 *
 * Живёт в `:checks` (#1293): проверка обходит исходники всего проекта целиком, вместе с
 * компьютером, а `:app` компьютер не собирает.
 */
class NoGearInProductTest {

    private val productSources: List<File> = repo.walkTopDown()
        .onEnter { it.name != "build" && !it.name.startsWith(".") }
        .filter { it.isFile && it.extension == "kt" }
        .filter { it.invariantSeparatorsPath.contains("/src/main/") }
        .toList()

    @Test fun `поиск и правда смотрит на исходники, а не на пустоту`() {

        assertTrue("исходников продукта не нашлось — сломан поиск, а не продукт", productSources.size > 100)
        assertTrue(productSources.any { it.name == "HomeScreen.kt" })
    }

    /**
     * Обещание в имени этого теста раньше говорило про знак, а искалось одно русское слово
     * «шестерён» — по `src/main` ноль попаданий, то есть проверка была зелена по построению.
     * Сам знак при этом стоял в продукте, и сторож его не видел (#1318).
     *
     * Исключение одно, и названо оно словами: `⚙` в шапке компакт-окна компьютера законна
     * (решение владельца по #1318). Шапка узкого окна — не список действий над объектом:
     * знак стоит рядом с `✕`, и слово «Настройки» съело бы там место. Там, где человек
     * выбирает действие над объектом, #462 в силе — безымянных значков не бывает.
     *
     * Прощается место, а не файл. Прощённый целиком `RecentPane.kt` — это ещё и список
     * «Недавнее», станции «СОЗДАТЬ ОБЪЕКТ» и строки журнала, то есть ровно те места, где
     * человек выбирает действие над объектом: вторая `⚙`, дописанная туда, осталась бы
     * незамеченной. Поэтому знак ищется по месту в тексте: он обязан стоять внутри вызова
     * шапки окна, и такой один на весь продукт.
     */
    @Test fun `к шестерёнке не зовут ни словом, ни знаком — кроме шапки окна компьютера`() {
        val sources = productSources.map { it.relativeTo(repo).invariantSeparatorsPath to code(it.readText()) }

        val words = sources.filter { (_, text) -> text.contains("шестерён", ignoreCase = true) }.map { it.first }

        assertEquals(
            "эти места зовут к шестерёнке словом, а её на экране нет с #462: $words",
            emptyList<String>(),
            words,
        )

        val gears = sources.flatMap { (path, text) -> gearsIn(path, text) }
        val outside = gears.filterNot { it.inWindowHeader }.map { it.where }

        assertEquals(
            "знак стоит не в шапке окна компьютера, а там, где человек выбирает действие: $outside",
            emptyList<String>(),
            outside,
        )
        assertEquals(
            if (gears.isEmpty()) {
                "знак ушёл из шапки окна компьютера — исключение по #1318 стало неправдой"
            } else {
                "знак в шапке окна компьютера один, а стоят ${gears.size}: ${gears.map { it.where }}"
            },
            1,
            gears.size,
        )
    }

    /**
     * Дверь настроек и её разделы человек открывает и на телефоне, и в окне компьютера — и
     * зовутся они одним словом на все модули (#1249, #1144 DIST-2).
     *
     * Обещание в имени этого теста раньше держала одна строка — сверка SETTINGS_TITLE с самим
     * словом. Ни одного модуля она не открывала и падала только на себя, а слова совпадали
     * случайно: окно компьютера набирало все пять заголовков своими литералами. Здесь
     * проверяется обещанное: у слова один дом, а копий нет ни в одном модуле.
     *
     * Само слово берётся из объявления, а не переписывается сюда: `:checks` намеренно живёт
     * без зависимостей (#1293), и второй список слов разошёлся бы с продуктом молча. Пропало
     * объявление — падает счёт домов, а не тишина.
     *
     * Ищется точный литерал целиком. Строка «Настройки не открылись …» в SourcePickerActivity
     * зовёт системные настройки Android — чужая дверь, и своим словом её звать нечем.
     */
    @Test fun `у каждого слова настроек один дом на все модули`() {

        val sources = productSources.map { it.toRelativeString(repo) to code(it.readText()) }

        val copies = SETTINGS_WORDS.flatMap { name ->
            val declaration = Regex("""const val $name = "([^"\n]+)"""")
            val homes = sources.mapNotNull { (path, code) ->
                declaration.find(code)?.let { path to it.groupValues[1] }
            }

            assertEquals(
                "у слова $name домов не один, а ${homes.size}: ${homes.map { it.first }}",
                1,
                homes.size,
            )

            val (home, word) = homes.single()
            sources.filter { (path, code) -> path != home && "\"$word\"" in code }
                .map { "«$word» — ${it.first}" }
        }

        assertEquals(
            "слово настроек набрано вторым литералом — переименование в одном месте оставит " +
                "второе окно со старым именем: $copies",
            emptyList<String>(),
            copies,
        )
    }

    /**
     * Где в этом файле стоит сам знак и шапка ли окна вокруг него.
     *
     * Место называется строкой кода, а не её номером: комментарии из текста уже вырезаны
     * (`code`), и номер после этого показал бы не на ту строку файла.
     */
    private fun gearsIn(path: String, text: String): List<Gear> {
        val headers = headerCalls(text)
        return Regex(GEAR).findAll(text)
            .map { hit ->
                val at = hit.range.first
                Gear(
                    where = "$path — «${text.lineAround(at)}»",
                    inWindowHeader = headers.any { at in it },
                )
            }
            .toList()
    }

    /** Строка, на которой стоит этот знак. */
    private fun String.lineAround(at: Int): String {
        val from = lastIndexOf('\n', at).let { if (it < 0) 0 else it + 1 }
        val to = indexOf('\n', at).let { if (it < 0) length else it }
        return substring(from, to).trim()
    }

    /**
     * Куски текста внутри вызовов шапки окна — по балансу скобок, а не по строке: слот
     * `trailing` стоит отдельной строкой ниже самого вызова.
     *
     * Объявление самой шапки вызовом не считается: в его скобках имена параметров, и знак,
     * дописанный в тело шапки, встал бы разом на все экраны окна.
     */
    private fun headerCalls(text: String): List<IntRange> {
        val calls = mutableListOf<IntRange>()
        var at = text.indexOf(HEADER_CALL)
        while (at >= 0) {
            val open = at + HEADER_CALL.length - 1
            if (!text.startsWith(DECLARED, maxOf(0, at - DECLARED.length))) calls += at..closes(text, open)
            at = text.indexOf(HEADER_CALL, at + HEADER_CALL.length)
        }
        return calls
    }

    /** Где кончается вызов, начатый этой скобкой. */
    private fun closes(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
        }
        return text.length - 1
    }

    /** Одно место со знаком: где оно и стоит ли внутри шапки окна. */
    private data class Gear(val where: String, val inWindowHeader: Boolean)

    private companion object {

        /** Сам знак: слово о нём в тексте ловится отдельно, а он — вот этот. */
        const val GEAR = "⚙"

        /** Единственное место, где знак законен, — шапка компакт-окна компьютера (#1318). */
        const val HEADER_CALL = "CompactHeader("

        /** Начало объявления шапки: перед скобками параметров стоит это, а не имя вызова. */
        const val DECLARED = "fun "

        /** Дверь настроек и заголовки её разделов — то, что человек читает на обоих устройствах. */
        val SETTINGS_WORDS = listOf(
            "SETTINGS_TITLE", "MY_DEVICES_TITLE", "KEY_SECTION_TITLE", "PRIVACY_SECTION_TITLE", "MEMORY_TITLE",
        )
    }
}

