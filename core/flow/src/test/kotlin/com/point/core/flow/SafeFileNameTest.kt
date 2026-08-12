package com.point.core.flow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Имя, пришедшее снаружи, в путь не годится (#865).
 *
 * Имя файла Point получает от чужого приложения, с сервера или из каталога архива — и тут же
 * делает его частью пути, по которому пишет копию объекта. Проверяется не «чисто ли выглядит
 * строка», а то, ради чего чистка нужна: копия остаётся там, куда её клали, и приём не падает
 * на непривычном имени.
 */
class SafeFileNameTest {

    @Test
    fun `путь в имени не уводит копию из своего каталога`() {
        val dir = File("/scratch/object")

        listOf("../../point-history/index.jsonl", "..\\..\\база.db", "/etc/passwd", "..").forEach { evil ->
            val child = File(dir, safeFileName(evil))

            assertEquals("$evil увёл файл: $child", dir, child.parentFile)
        }
    }

    /**
     * Провайдер, кладущий в `DISPLAY_NAME` путь, ронял приём **всего набора**: каталога нет,
     * `outputStream()` бросает — и человек видит отказ на ровном месте.
     */
    @Test
    fun `имя с каталогом внутри становится просто именем`() {
        assertEquals("IMG_1.jpg", safeFileName("Camera/IMG_1.jpg"))
        assertEquals("scan.pdf", safeFileName("Downloads\\scan.pdf"))
    }

    @Test
    fun `запрещённые знаки не слипают имя в кашу — человек его узнаёт`() {
        assertEquals("check 12 05 pdf", safeFileName("check:12*05?pdf"))
        assertEquals("may report", safeFileName("may\nreport"))
        assertEquals("scan.pdf", safeFileName("scan\t.pdf"))
    }

    @Test
    fun `имени нет — объект получает своё слово, а не падение`() {
        assertEquals("none", safeFileName("", ifBlank = "none"))
        assertEquals("none", safeFileName("   ..  ", ifBlank = "none"))
        assertEquals("file-2", safeFileName("///", ifBlank = "file-2"))
    }

    @Test
    fun `длинное имя обрезается, а не обрывает приём`() {
        val long = "i".repeat(500) + ".jpg"

        assertEquals(MAX_FILE_NAME, safeFileName(long).length)
    }

    @Test
    fun `обычное имя не трогается`() {
        assertEquals("Contract 2026.pdf", safeFileName("Contract 2026.pdf"))
        assertEquals("photo_2026-08-12.png", safeFileName("photo_2026-08-12.png"))
    }

    /**
     * Сторож шва: правило знали пятью способами в пяти местах, а в шестом не знали вовсе.
     * Седьмое место не должно завести свой способ.
     */
    @Test
    fun `места приёма чистят имя общей чисткой, а не своей`() {
        val repo = File("../..")
        val guilty = listOf(
            "data/src/main/kotlin/com/point/data/ScratchObjectStore.kt",
            "app/src/main/kotlin/com/point/ClipboardSyncActivity.kt",
            "desktop/src/main/kotlin/com/point/desktop/Inbox.kt",
        ).filterNot { File(repo, it).readText().contains("safeFileName(") }

        assertTrue("своя чистка имени: $guilty", guilty.isEmpty())
    }
}
