package com.point.core.flow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Цемент формулировок не растёт (#584, решение владельца 10.08.2026 — «сначала сторож на новые»).
 *
 * Набор тестов существует ради продукта, а не ради себя. Две трети тестов сверяли точную строку
 * экрана — и одиннадцать правок текста роняли пять тестов, из которых четыре просто цементировали
 * формулировку. Но резать по признаку «сверяет строку» нельзя: пятый поймал настоящую ошибку.
 *
 * Поэтому здесь не запрет, а список исключений, который может только уменьшаться: у каждого файла
 * записано, сколько цемента в нём есть сегодня. Новая проверка точной строки роняет сборку — в том
 * же файле или в новом, безразлично. Прежний общий счётчик этого не ловил: добавить две и удалить
 * две в другом месте было можно, и куча не таяла, а перемешивалась.
 *
 * Сторож обещания не удаляется никогда: тест, проверяющий ОТСУТСТВИЕ жаргона, оправдания или
 * неправды, — это то, ради чего набор существует, и цементом он не является.
 */
class CementedWordingDoesNotGrowTest {

    private val roots = listOf(
        "app/src/test", "app/src/testDebug",
        "core/flow/src/test", "core/ui/src/test", "core/ui/src/testDebug",
        "data/src/test", "executors/src/test", "desktop/src/test",
    )

    private val repo = File("../..")

    private val listed = File(repo, LIST)

    private fun measured(): Map<String, Int> = roots
        .map { File(repo, it) }
        .filter { it.isDirectory }
        .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }

        // Свои же примеры правила — не экранный текст: сторож не считает сам себя.
        .filterNot { it.name.startsWith("CementedWording") }
        .associate { it.relativeTo(repo).invariantSeparatorsPath to CementedWording.countIn(it.readText()) }
        .filterValues { it > 0 }
        .toSortedMap()

    private fun exceptions(): Map<String, Int> = listed.readLines()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .associate { line ->
            val at = line.indexOf(' ')
            line.substring(at + 1) to line.substring(0, at).toInt()
        }

    @Test
    fun `новых проверок точной строки экрана не появляется`() {
        val now = measured()
        val allowed = exceptions()

        val grown = now.filter { (path, count) -> count > (allowed[path] ?: 0) }
            .map { (path, count) -> "$path: ${allowed[path] ?: 0} → $count" }

        assertTrue(
            "проверок точной строки экрана стало больше — новый тест обязан охранять обещание, " +
                "а не цементировать формулировку:\n" + grown.joinToString("\n"),
            grown.isEmpty(),
        )
    }

    @Test
    fun `список исключений не отстаёт от того, что уже разобрано`() {
        val now = measured()
        val allowed = exceptions()

        val stale = allowed.filter { (path, count) -> count > (now[path] ?: 0) }
            .map { (path, count) -> "${now[path] ?: 0} $path (было $count)" }

        assertTrue(
            "цемент разобран, а список не поправлен — список исключений может только уменьшаться. " +
                "Впишите в $LIST новые числа:\n" + stale.joinToString("\n"),
            stale.isEmpty(),
        )
    }

    /** Сам сторож бесполезен, пока он не различает цемент и пояснение к падению. */
    @Test
    fun `пояснение к падению цементом не считается`() {
        assertEquals(0, CementedWording.countIn("""assertEquals("отправитель и получатель", a, b)"""))
        assertEquals(1, CementedWording.countIn("""assertEquals("отправитель и получатель", b)"""))
    }

    @Test
    fun `строка-контракт цементом не считается`() {
        assertEquals(0, CementedWording.countIn("""assertEquals("application/pdf", mime)"""))
        assertEquals(0, CementedWording.countIn("""assertEquals("10.0 20.0 110.0 90.0", wire)"""))
    }

    @Test
    fun `выражение вместо строки цементом не считается`() {
        assertEquals(0, CementedWording.countIn("""assertEquals(NO_INTERNET_NOTE, note)"""))
        assertEquals(0, CementedWording.countIn("""assertEquals("нашёл ${'$'}count", said)"""))
    }

    @Test
    fun `вложенный вызов не путает сторожа`() {
        assertEquals(
            1,
            CementedWording.countIn("""assertEquals("нашёл документ", label(state, listOf(a, b)))"""),
        )
    }

    private companion object {

        /** Список исключений: сколько цемента в файле есть сегодня. Может только уменьшаться. */
        const val LIST = "tools/cemented-wording.txt"
    }
}
