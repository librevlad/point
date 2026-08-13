package com.point.desktop

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Окно компьютера разложено по частям, а не лежит одним файлом (#836).
 *
 * `CompactApp.kt` вырос до 942 строк: окно, список, экран объекта и настройки в одном месте.
 * Живая ошибка #822 указывала ровно сюда, и найти в этих строках причину было дороже, чем в
 * двухстах. Решение владельца: «сначала починить #822, резать вокруг него».
 */
class WindowIsNotOneFileTest {

    private val ui = File("src/main/kotlin/com/point/desktop/ui")

    private fun lines(name: String) = File(ui, name).readLines().size

    @Test
    fun `у каждой части окна свой файл`() {
        listOf("CompactApp.kt", "RecentPane.kt", "ObjectPane.kt", "SettingsPane.kt").forEach {
            assertTrue("части окна нет: $it", File(ui, it).isFile)
        }
    }

    @Test
    fun `ни одна часть окна не разрастается снова`() {
        val big = ui.listFiles().orEmpty()
            .filter { it.name.endsWith(".kt") }
            .filter { it.readLines().size > 520 }
            .map { it.name + ":" + it.readLines().size }

        assertTrue("файл окна снова стал складом: $big", big.isEmpty())
    }

    @Test
    fun `окно осталось окном, а не переехало целиком в другой файл`() {
        val root = File(ui, "CompactApp.kt").readText()

        assertTrue("корень окна пропал", root.contains("fun CompactApp("))
        assertTrue("список вернулся в корень", !root.contains("internal fun CompactList("))
        assertTrue("экран объекта вернулся в корень", !root.contains("internal fun CompactObject("))
        assertTrue("настройки вернулись в корень", !root.contains("fun CompactSettings("))
    }
}
