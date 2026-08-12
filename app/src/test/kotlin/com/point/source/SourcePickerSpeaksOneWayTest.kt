package com.point.source

import com.point.core.flow.sourceOrder
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Список «с чего начать» говорит одним строем и ничего не умалчивает (#895).
 *
 * Половина строк называлась существительным («Камера», «Место»), половина на компьютере —
 * глагольной фразой («Взять то, что в буфере»). У двух строк из шести не было пояснения
 * вовсе, у остальных было: список читался как две разные вещи, сложенные вместе.
 */
class SourcePickerSpeaksOneWayTest {

    private val sources = File("src/main/kotlin/com/point/source").listFiles()
        .orEmpty()
        .filter { it.name.endsWith("Source.kt") && it.name != "ObjectSource.kt" }

    private fun valueOf(text: String, field: String): String? =
        Regex("""override val $field = "([^"]*)"""").find(text)?.groupValues?.get(1)

    @Test
    fun `каждый источник называется действием и объясняет себя`() {
        val silent = mutableListOf<String>()
        val nouns = mutableListOf<String>()

        sources.forEach { file ->
            val text = file.readText()
            val label = valueOf(text, "label") ?: return@forEach
            if (valueOf(text, "what") == null) silent += label
            if (label.first().isUpperCase() && !label.contains(' ')) nouns += label
        }

        assertTrue("строки молчат о том, что дадут: $silent", silent.isEmpty())
        assertTrue("строка названа вещью, а не действием: $nouns", nouns.isEmpty())
    }

    @Test
    fun `порядок задан пользой, а не алфавитом`() {
        val order = listOf("clipboard", "camera", "voice", "location", "receive")

        assertEquals(order, order.sortedBy(::sourceOrder))
        assertTrue("незнакомый источник встаёт в конец", sourceOrder("что-то новое") > sourceOrder("receive"))
    }

    @Test
    fun `лист выбора непрозрачен — сквозь него не читается домашний экран`() {
        val screen = File("src/main/kotlin/com/point/source/SourcePickerActivity.kt").readText()

        assertTrue(
            "фон снова полупрозрачный: слова домашнего экрана полезут на список",
            !screen.contains("background.copy(alpha"),
        )
    }
}
