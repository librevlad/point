package com.point.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Запуск из проводника (#252): что Point делает с тем, что пришло в командной строке.
 *
 * Решения здесь неочевидные и стоят теста: пустой запуск — это обычное окно, а несуществующий
 * путь не должен ни падать, ни превращаться в пустой объект.
 */
class SendToRunningTest {

    private fun tempFile(name: String): File =
        File.createTempFile("point-", "-$name").apply { writeText("содержимое"); deleteOnExit() }

    @Test
    fun `обычный запуск без аргументов — файлов нет`() {
        assertTrue(filesFromArgs(emptyArray()).isEmpty())
    }

    @Test
    fun `путь к настоящему файлу берётся`() {
        val file = tempFile("отчёт.pptx")
        assertEquals(listOf(file), filesFromArgs(arrayOf(file.absolutePath)))
    }

    @Test
    fun `несуществующий путь отбрасывается, а не роняет запуск`() {
        val файлов = filesFromArgs(arrayOf("C:/нет/такого/файла.pptx"))
        assertTrue(файлов.isEmpty())
    }

    @Test
    fun `каталог — не объект`() {
        val dir = File(System.getProperty("java.io.tmpdir"))
        assertTrue(filesFromArgs(arrayOf(dir.absolutePath)).isEmpty())
    }

    @Test
    fun `без файлов передавать нечего — работающий Point не тревожим`() {
        val handed = SendToRunning.handOff(emptyList(), PcConfig(token = "t", name = "PC", port = 47713))
        assertFalse(handed)
    }

    @Test
    fun `живого Point нет — файл остаётся нам, и это не ошибка`() {
        // Порт заведомо свободен: никто не ответит, и запуск обязан пойти обычным путём.
        val handed = SendToRunning.handOff(
            listOf(tempFile("смета.xlsx")),
            PcConfig(token = "t", name = "PC", port = 59_991),
        )
        assertFalse(handed)
    }

    @Test
    fun `презентация едет на ПК своим типом, а не «неизвестно чем»`() {
        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            mimeFor("квартальный отчёт.pptx"),
        )
    }
}
