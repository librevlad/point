package com.point.checks

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пределы и расчёты — по одному месту, а не по копии на устройство (#861).
 *
 * Каждое из этих чисел — обещание человеку: объект тяжелее просто не поедет. Записанные
 * дважды, они разъезжаются молча, и человек получает «на телефоне работает, на компьютере
 * нет» без единого слова о том, почему.
 *
 * Живёт в `:checks` (#1293): проверка читает файлы `:data`, `:desktop` и `:executors`. Сами
 * общие числа и расчёты остались тестами `:core:flow` — там, где они объявлены.
 */
class LimitsAreWrittenOnceTest {

    private fun source(path: String) = File(repo, path).readText()

    @Test
    fun `предел ссылки не набран числом второй раз`() {
        val guilty = listOf(
            "data/src/main/kotlin/com/point/data/RelayDropLink.kt",
            "desktop/src/main/kotlin/com/point/desktop/DesktopDrop.kt",
        ).filterNot { source(it).contains("com.point.core.flow.MAX_DROP_BYTES") }

        assertTrue("предел объявлен заново: $guilty", guilty.isEmpty())
    }

    @Test
    fun `предел записи не набран числом второй раз`() {
        val guilty = listOf(
            "data/src/main/kotlin/com/point/data/GroqWhisperSpeechToText.kt",
            "desktop/src/main/kotlin/com/point/desktop/SpeechActions.kt",
        ).filterNot { source(it).contains("com.point.core.flow.MAX_SPEECH_BYTES") }

        assertTrue("предел объявлен заново: $guilty", guilty.isEmpty())
    }

    /**
     * Срок брошенного — один на телефон и компьютер (#1317, решение владельца 29.08.2026).
     *
     * Число суток жило только у телефона, и очередь ПК→телефон осталась вовсе без срока:
     * брошенные вещи и записи-исходы копились в папке годами. Записанный по копии на
     * устройство, срок разъедется так же молча, как пределы веса.
     */
    @Test
    fun `срок брошенного не набран числом второй раз`() {
        val guilty = listOf(
            "app/src/main/kotlin/com/point/PointApplication.kt",
            "desktop/src/main/kotlin/com/point/desktop/Main.kt",
        ).filterNot { source(it).contains("COPY_LIFETIME_MS") }

        assertTrue("срок объявлен заново: $guilty", guilty.isEmpty())
    }

    @Test
    fun `цикл уменьшения не вписан руками второй раз`() {
        val guilty = listOf(
            "executors/src/main/kotlin/com/point/executors/Bitmaps.kt",
            "data/src/main/kotlin/com/point/data/TesseractTextRecognizer.kt",

            // Кадр выделения, замазывания и чтения на устройстве (#1013): его ужатие и есть
            // тот перевод координат, по которому метка поиска встаёт на найденную строку.
            "data/src/main/kotlin/com/point/data/ImageDecode.kt",
        ).filterNot { source(it).contains("sampleSizeFor(") }

        assertTrue("расчёт повторён: $guilty", guilty.isEmpty())
    }
}
