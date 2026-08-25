package com.point.desktop

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
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
    fun `второй запуск без файлов не живёт второй копией — будит первую и уходит`() {
        // Живой запуск 2026-08-09: у владельца оказалось две копии Point — запуск
        // без аргументов не смотрел на замок вовсе.
        val dir = tempDir()
        val alive = SendToRunning.takeLock(dir)

        assertTrue("при живом Point второй обязан уйти", SendToRunning.handOff(emptyList(), dir))
        assertTrue("первому оставлен сигнал «покажись»", SendToRunning.takeWake(dir))
        assertTrue("сигнал одноразовый", !SendToRunning.takeWake(dir))

        alive!!.release()
    }

    /**
     * «Открыть в Point», когда Point уже живёт: вторая копия отдала файл и ушла (#1019).
     * Живая принимает принесённое и зовёт окно один раз — письмо с файлами и сигнал
     * «покажись» оставлены одним нажатием человека.
     *
     * Сторож нормы владельца «`toFront()`/`requestFocus()` один раз», а не починка того,
     * что человек видел: двух подъёмов подряд он от одного не отличит.
     */
    @Test
    fun `открыть в Point у живой копии — файл принят, окно позвано один раз`() {
        val dir = tempDir()
        val alive = SendToRunning.takeLock(dir)
        val file = tempFile("счёт.pdf")
        assertTrue("живому Point файл отдан", SendToRunning.handOff(listOf(file), dir))

        val received = mutableListOf<File>()
        val summoned = AtomicInteger(0)
        try {
            SendToRunning.serveHandOffs(dir, receive = received::add, summon = { summoned.incrementAndGet() })
            assertEquals(listOf(file), received)
            assertEquals("один зов человека — один подъём окна, а не за письмо и за сигнал порознь", 1, summoned.get().toLong())

            SendToRunning.serveHandOffs(dir, receive = received::add, summon = { summoned.incrementAndGet() })
            assertEquals("без нового зова окно не дёргается", 1, summoned.get().toLong())
        } finally {
            runCatching { alive?.release() }
        }
    }

    /** Пробуждение второй копией без файла — тоже зов: показывать нечего, выйти вперёд надо. */
    @Test
    fun `второй запуск без файла зовёт окно вперёд`() {
        val dir = tempDir()
        val alive = SendToRunning.takeLock(dir)
        assertTrue("при живом Point второй обязан уйти", SendToRunning.handOff(emptyList(), dir))

        val received = mutableListOf<File>()
        val summoned = AtomicInteger(0)
        try {
            SendToRunning.serveHandOffs(dir, receive = received::add, summon = { summoned.incrementAndGet() })
            assertTrue("принимать было нечего", received.isEmpty())
            assertEquals("голое «покажись» окно не позвало", 1, summoned.get().toLong())
        } finally {
            runCatching { alive?.release() }
        }
    }

    @Test
    fun `никого нет — запуск без файлов стартует сам`() {
        val dir = tempDir()

        assertTrue("замок свободен — жить самому", !SendToRunning.handOff(emptyList(), dir))
    }

    @Test
    fun `презентация едет на ПК своим типом, а не «неизвестно чем»`() {
        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            mimeFor("квартальный отчёт.pptx"),
        )
    }
}
