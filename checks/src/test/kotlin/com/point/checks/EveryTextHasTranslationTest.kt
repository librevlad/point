package com.point.checks

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * У каждой фразы человека есть все три языка (#1473).
 *
 * Тексты в коде пишутся по-русски и оборачиваются в `tr("…")` / `trPlural(n, …)`; русская
 * строка — ключ каталога `core/model/src/main/resources/point/i18n/texts.tsv`, где рядом
 * стоят украинский и английский. Забытый перевод не роняет приложение — человек просто видит
 * русскую фразу посреди английского экрана. Поэтому ловится он здесь, при сборке, а не
 * в отзыве.
 *
 * Проверка читает код текстом: ключ — литерал внутри вызова (и склейка литералов через `+`).
 * Подстановка `$x` внутри ключа запрещена — значение встаёт в фразу через `{0}`, иначе
 * у ключа нет постоянного вида и перевод не найдётся никогда.
 */
class EveryTextHasTranslationTest {

    private val catalogFile = File(repo, "core/model/src/main/resources/point/i18n/texts.tsv")

    private val sources: List<File> = repo.listFiles().orEmpty()
        .filter { it.isDirectory && it.name != "build" && !it.name.startsWith('.') }
        .flatMap { top -> top.walkTopDown().onEnter { it.name != "build" && !it.name.startsWith('.') }.toList() }
        .filter { it.isFile && it.extension == "kt" && ("/src/main/" in it.invariantSeparatorsPath || "/src/shared/" in it.invariantSeparatorsPath) }

    private val catalog: Map<String, List<String>> by lazy {
        catalogFile.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .associate { line ->
                val cells = line.split('\t')
                unescape(cells[0]) to cells.drop(1).map(::unescape)
            }
    }

    private class Use(val file: String, val key: String?, val problem: String? = null)

    private val uses: List<Use> by lazy { sources.flatMap { usesIn(it) } }

    @Test
    fun `каталог разбирается — в каждой строке ровно три языка`() {
        val broken = catalogFile.readLines(Charsets.UTF_8)
            .withIndex()
            .filter { (_, line) -> line.isNotBlank() && line.split('\t').size != 3 }
            .map { (i, line) -> "${i + 1}: $line" }
        assertTrue("строки каталога не из трёх колонок:\n" + broken.joinToString("\n"), broken.isEmpty())
    }

    @Test
    fun `у каждой фразы в коде есть украинский и английский`() {
        val bad = uses.mapNotNull { use ->
            when {
                use.problem != null -> "${use.file}: ${use.problem}"
                use.key == null -> null
                catalog[use.key] == null -> "${use.file}: нет в каталоге — ${use.key}"
                catalog.getValue(use.key).any { it.isBlank() } -> "${use.file}: пустой перевод — ${use.key}"
                else -> null
            }
        }.distinct()
        assertTrue("фразы без перевода (добавьте строку в texts.tsv):\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `в каталоге нет фраз, которых код больше не говорит`() {
        val used = uses.mapNotNull { it.key }.toSet()
        // Слово, которое код держит литералом и переводит уже значением (`tr(target)`), — тоже живое.
        val literals = sources.flatMapTo(HashSet()) { literalsIn(withoutComments(it.readText())) }
        val dead = catalog.keys.filter { it !in used && it !in literals }
        assertTrue("в каталоге лишние строки — их код уже не произносит:\n" + dead.joinToString("\n"), dead.isEmpty())
    }

    @Test
    fun `английский перевод написан латиницей`() {
        val cyrillic = Regex("[\\u0400-\\u04FF]")
        val bad = catalog.filter { (_, langs) -> cyrillic.containsMatchIn(langs[1]) }.keys
        assertTrue("кириллица в английском переводе:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `перевод подставляет только те значения, что есть в оригинале`() {
        val slot = Regex("\\{\\d+\\}")
        val format = Regex("%[-0-9.]*[a-zA-Z]")
        val bad = catalog.flatMap { (ru, langs) ->
            val slots = slot.findAll(ru).map { it.value }.toSet()
            val formats = format.findAll(ru).map { it.value }.toList()
            langs.filter { tr ->
                !slots.containsAll(slot.findAll(tr).map { it.value }.toSet()) ||
                    format.findAll(tr).map { it.value }.toList() != formats
            }.map { "$ru → $it" }
        }
        assertTrue("перевод ссылается на значения, которых у фразы нет:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    private fun usesIn(file: File): List<Use> {
        val path = file.relativeTo(repo).invariantSeparatorsPath
        val text = withoutComments(file.readText())
        val out = mutableListOf<Use>()
        CALL.findAll(text).forEach { match ->
            // Объявления самих функций в Tr.kt — не вызовы.
            if (text.substring(maxOf(0, match.range.first - 4), match.range.first).endsWith("fun ")) return@forEach
            val plural = match.groupValues[1] == "trPlural"
            var i = match.range.last + 1
            if (plural) {
                // Первый аргумент — число; слова идут после первой запятой верхнего уровня.
                i = afterTopLevelComma(text, i) ?: return@forEach
            }
            repeat(if (plural) 3 else 1) {
                i = skipSpace(text, i)
                if (i >= text.length || text[i] != '"') {
                    // Переводится значение, а не литерал: `tr(target)`. Слова лежат в каталоге отдельно.
                    return@forEach
                }
                val (key, end) = readConcatenated(text, i) ?: run {
                    out += Use(path, null, "ключ с подстановкой \$… вместо {0}: ${text.substring(i, minOf(text.length, i + 60))}")
                    return@forEach
                }
                out += Use(path, key)
                i = skipSpace(text, end)
                if (i < text.length && text[i] == ',') i++
            }
        }
        return out
    }

    /** Склейка `"…" + "…"`; null — в литерале есть шаблон `$`. */
    private fun readConcatenated(text: String, start: Int): Pair<String, Int>? {
        val out = StringBuilder()
        var i = start
        while (true) {
            val (lit, end) = readLiteral(text, i) ?: return null
            out.append(lit)
            val next = skipSpace(text, end)
            if (next < text.length && text[next] == '+') {
                val after = skipSpace(text, next + 1)
                if (after < text.length && text[after] == '"') {
                    i = after
                    continue
                }
            }
            return out.toString() to end
        }
    }

    /** Обычный строковый литерал Kotlin; null — внутри шаблон `$` или это `"""`. */
    private fun readLiteral(text: String, start: Int): Pair<String, Int>? {
        if (text.startsWith("\"\"\"", start)) return null
        val out = StringBuilder()
        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> {
                    when (val e = text[i + 1]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'u' -> {
                            out.append(text.substring(i + 2, i + 6).toInt(16).toChar())
                            i += 4
                        }
                        else -> out.append(e)
                    }
                    i += 2
                }
                c == '$' && i + 1 < text.length && (text[i + 1] == '{' || text[i + 1].isLetter()) -> return null
                c == '"' -> return out.toString() to i + 1
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return null
    }

    private fun literalsIn(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            if (text[i] == '"' && !text.startsWith("\"\"\"", i)) {
                val read = readLiteral(text, i)
                if (read != null) {
                    out += read.first
                    i = read.second
                    continue
                }
            }
            i++
        }
        return out
    }

    /**
     * Код без комментариев, но со строками целиком: общий `code()` режет строку по `//`, а ключ
     * перевода бывает с адресом внутри.
     */
    private fun withoutComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("//", i) -> {
                    val end = text.indexOf('\n', i)
                    i = if (end < 0) text.length else end
                }
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) text.length else end + 2
                    out.append(' ')
                }
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3)
                    val stop = if (end < 0) text.length else end + 3
                    out.append(text, i, stop)
                    i = stop
                }
                text[i] == '"' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != '"' && text[j] != '\n') j += if (text[j] == '\\') 2 else 1
                    val stop = minOf(text.length, j + 1)
                    out.append(text, i, stop)
                    i = stop
                }
                text[i] == '\'' && i + 2 < text.length -> {
                    // Символ: '"' не открывает строку.
                    val stop = if (text[i + 1] == '\\') text.indexOf('\'', i + 3) + 1 else i + 3
                    out.append(text, i, stop)
                    i = stop
                }
                else -> out.append(text[i++])
            }
        }
        return out.toString()
    }

    private fun afterTopLevelComma(text: String, from: Int): Int? {
        var depth = 0
        var i = from
        while (i < text.length) {
            when (text[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth == 0) return null else depth--
                ',' -> if (depth == 0) return i + 1
                '"' -> {
                    i = readLiteral(text, i)?.second ?: return null
                    continue
                }
            }
            i++
        }
        return null
    }

    private fun skipSpace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun unescape(s: String): String = s.replace("\\\\", "\u0000").replace("\\n", "\n")
        .replace("\\t", "\t").replace("\u0000", "\\")

    private companion object {
        val CALL = Regex("(?<![\\w.])(tr|trPlural)\\(")
    }
}
