package com.point.desktop

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Беда на компьютере говорит словами, а не именем класса (#822, решение владельца
 * 12.08.2026 «Ошибка говорит словами + видна версия»).
 *
 * Живой прогон: тап по «Не прятать окно — принесу файл» открыл системное окно `Error` с
 * текстом `com/point/desktop/ui/CompactAppKt$CompactApp$18$2$11$1$1`. Установленный Point
 * был от 6 августа, а само действие приехало 10-го.
 */
class TroubleSpeaksWordsTest {

    @get:Rule val temp = TemporaryFolder()

    private fun says(error: Throwable) = troubleWords(error)

    @Test
    fun `ни одна беда не показывает человеку имя класса или стек`() {
        val troubles = listOf(
            NoClassDefFoundError("com/point/desktop/ui/CompactAppKt\$CompactApp\$18\$2\$11\$1\$1"),
            IllegalStateException("java.lang.IllegalStateException: at com.point.desktop.Foo"),
            OutOfMemoryError("Java heap space"),
            java.io.FileNotFoundException("C:\\tmp\\нет.txt"),
        )

        troubles.forEach { error ->
            val said = says(error)
            assertFalse(said, said.contains("$"))
            assertFalse(said, said.contains("com.point"))
            assertFalse(said, said.contains("com/point"))
            assertFalse(said, said.contains("Exception"))
            assertFalse(said, said.contains("Error"))
            assertTrue(said, said.length > 20)
        }
    }

    @Test
    fun `нехватка классов советует обновиться — это и есть причина`() {
        val said = says(NoClassDefFoundError("com/point/desktop/ui/CompactAppKt\$CompactApp\$18"))

        assertTrue(said, said.contains("старая версия"))
        assertTrue(said, said.contains("Обновите"))
    }

    @Test
    fun `след беды остаётся на диске вместе с версией сборки`() {
        val dir = temp.newFolder()

        keepTrouble(dir, IllegalStateException("подробность для разбора"))

        val kept = File(dir, "trouble.txt").readText()
        assertTrue(kept, kept.contains(BuildInfo.VERSION))
        assertTrue(kept, kept.contains(BuildInfo.BUILT_ON))
        assertTrue(kept, kept.contains("подробность для разбора"))
    }
}
