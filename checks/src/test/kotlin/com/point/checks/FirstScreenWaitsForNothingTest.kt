package com.point.checks

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Первый экран никого не ждёт (#539, Конституция: первый экран ≤ 300 мс и без I/O).
 *
 * От тапа «Поделиться» до первых слов на экране проходили секунды: замер аудита — 5–8 секунд
 * холодного старта, повторные запуски 9,9 и 11,2, один раз система объявила приложение не
 * отвечающим. В `onCreate` копилось всё, что кому-то однажды понадобилось на старте, и цену
 * этого никто не считал (живая охота 13.08.2026, `ANR in com.point`).
 *
 * Правило починили, но держалось оно на внимательности автора: следующая строка в `onCreate`
 * так же легко оказалась бы работой с диском, и заметил бы это человек, а не сборка. Число
 * запуска в журнале устройства (`tellHowLongStartupTook`) показывает превышение только тому,
 * кто смотрит logcat.
 *
 * Проверка узкая: спрашивается ровно то, что нельзя делать на главном потоке при запуске —
 * работа, которую видно словами. Своя работа `onCreate` разрешена, когда она уходит за
 * [OFF_MAIN]; всё прочее либо в списке исключений с причиной, либо роняет сборку.
 *
 * Живёт в `:checks` (#1293): читается файл `:app`, а проверка про норму всего продукта.
 */
class FirstScreenWaitsForNothingTest {

    /** Что делать на главном потоке при запуске нельзя: это и есть I/O в понимании нормы. */
    private val forbidden = listOf(
        "readText(", "writeText(", "readBytes(", "writeBytes(",
        "listFiles(", "mkdirs(", "delete(", "exists(",
        "openInputStream(", "openOutputStream(", "openConnection(",
        "getSharedPreferences(", "openFileInput(", "openFileOutput(",
    )

    /**
     * Кому на главном потоке остаться можно — и почему.
     *
     * Список может только уменьшаться: каждая новая строка в нём — это секунды, которые
     * человек ждёт, глядя в пустой экран.
     */
    private val allowed = mapOf(
        "tellHowLongStartupTook" to "меряет сам запуск: считает разницу часов и пишет в журнал",
        "offMainThread" to "сам и есть уход с главного потока",
        "onCreate" to "проверяется отдельно — ниже",
    )

    private fun application(): String =
        File(repo, "app/src/main/kotlin/com/point/PointApplication.kt").readText()

    /** Объявления верхнего уровня класса: имя и всё, что до следующего объявления. */
    private fun functions(src: String): List<Pair<String, String>> {
        val heads = HEAD.findAll(src).toList()
        return heads.mapIndexed { i, head ->
            val to = heads.getOrNull(i + 1)?.range?.first ?: src.length
            head.groupValues[1] to src.substring(head.range.first, to)
        }
    }

    @Test
    fun `запуск не делает на главном потоке того, чего человек будет ждать`() {
        val src = application()
        val declared = functions(src)
        assertTrue("функций запуска не нашлось — проверка ослепла", declared.size > 3)

        val onCreate = declared.firstOrNull { it.first == "onCreate" }
        assertTrue("onCreate не найден — проверка ослепла", onCreate != null)

        // Своя работа onCreate: всё, что он делает не через уход с главного потока.
        val guilty = mutableListOf<String>()
        val called = CALL.findAll(onCreate!!.second).map { it.groupValues[1] }.toSet()
        declared
            .filter { (name, _) -> name in called && name !in allowed }
            .forEach { (name, body) ->
                if (OFF_MAIN in body) return@forEach
                forbidden.filter { it in body }.forEach { guilty += "$name: $it" }
            }
        forbidden.filter { it in withoutNested(onCreate.second, declared) }
            .forEach { guilty += "onCreate: $it" }

        assertTrue(
            "запуск ждёт диска на главном потоке — человек смотрит в пустой экран: $guilty",
            guilty.isEmpty(),
        )
    }

    /** Тело `onCreate` без тел вызванных им функций: их проверяет цикл выше. */
    private fun withoutNested(onCreate: String, declared: List<Pair<String, String>>): String =
        declared.fold(onCreate) { text, (_, body) -> if (body === onCreate) text else text }

    private companion object {

        const val OFF_MAIN = "offMainThread"

        val HEAD = Regex(
            """^\s{4}(?:override |private |internal |suspend )*fun\s+(\w+)""",
            RegexOption.MULTILINE,
        )

        val CALL = Regex("""\b(\w+)\s*\(""")
    }
}
