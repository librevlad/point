package com.point.core.flow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пределы и расчёты — по одному месту, а не по копии на устройство (#861).
 *
 * Каждое из этих чисел — обещание человеку: объект тяжелее просто не поедет. Записанные
 * дважды, они разъезжаются молча, и человек получает «на телефоне работает, на компьютере
 * нет» без единого слова о том, почему.
 */
class OneLimitForBothDevicesTest {

    private val repo = File("../..")

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
     * Имя тут не украшение: сервис расшифровки по расширению решает, как читать байты.
     * Телефон брал его по точному mime и звал незнакомое `ogg`, ПК искал подстроку и звал
     * `audio` — один и тот же файл уезжал под разными именами.
     */
    @Test
    fun `запись зовётся одинаково на обоих устройствах`() {
        assertEquals("ogg", audioExtensionFor("audio/ogg"))
        assertEquals("mp3", audioExtensionFor("audio/mpeg"))
        assertEquals("m4a", audioExtensionFor("audio/mp4"))
        assertEquals("wav", audioExtensionFor("audio/wav"))
        assertEquals("flac", audioExtensionFor("audio/flac"))
        assertEquals("aac", audioExtensionFor("audio/aac"))
    }

    @Test
    fun `незнакомая запись получает имя, с которым её примут, а не выдуманное`() {
        assertEquals("ogg", audioExtensionFor("audio/неизвестное"))
        assertEquals("ogg", audioExtensionFor(""))
    }

    @Test
    fun `уменьшение снимка считается делением пополам, и одинаково везде`() {
        assertEquals(1, sampleSizeFor(80, 60, 96))
        assertEquals(2, sampleSizeFor(192, 100, 96))
        assertEquals(4, sampleSizeFor(400, 300, 96))
        assertEquals(32, sampleSizeFor(4000, 3000, 96))
    }

    @Test
    fun `без предела и без сторон уменьшать нечего`() {
        assertEquals(1, sampleSizeFor(0, 0, 96))
        assertEquals(1, sampleSizeFor(4000, 3000, 0))
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
