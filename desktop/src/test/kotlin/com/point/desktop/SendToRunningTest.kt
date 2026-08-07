package com.point.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendToRunningTest {

    private fun tempDir(): File =
        File.createTempFile("point-dir-", "").apply { delete(); mkdirs(); deleteOnExit() }

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
        assertFalse(SendToRunning.handOff(emptyList(), tempDir()))
    }

    @Test
    fun `живого Point нет — файл остаётся нам, и это не ошибка`() {

        assertFalse(SendToRunning.handOff(listOf(tempFile("смета.xlsx")), tempDir()))
    }

    @Test
    fun `живой Point забирает переданное, а второго окна не появляется`() {

        val dir = tempDir()
        val alive = SendToRunning.takeLock(dir)
        assertNotNull("замок в пустом каталоге обязан взяться", alive)
        val file = tempFile("смета.xlsx")

        assertTrue("живому Point файл отдан", SendToRunning.handOff(listOf(file), dir))
        assertEquals(listOf(file), SendToRunning.collectHandOffs(dir))
        assertTrue("прочитанное не возвращается эхом", SendToRunning.collectHandOffs(dir).isEmpty())

        alive!!.release()
    }

    @Test
    fun `презентация едет на ПК своим типом, а не «неизвестно чем»`() {
        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            mimeFor("квартальный отчёт.pptx"),
        )
    }
}
