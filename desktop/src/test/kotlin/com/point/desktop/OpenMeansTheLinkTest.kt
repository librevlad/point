package com.point.desktop

import com.point.core.flow.META_ENTITY_URL
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * «Открыть на компьютере» открывает смысл, а не файл (#1087, решение владельца 21.08.2026).
 *
 * Ссылка, поделённая с телефона строкой, приезжает на компьютер файлом `.txt` — и системный
 * обработчик расширения открывал её текстовым редактором. Адрес объект знает: он приехал
 * знанием графа. Действие следует из знания.
 *
 * Граница у правила узкая: ссылкой объект становится, когда он ссылка и есть, а не когда
 * адрес внутри него найден. У снимка с QR и у документа с адресом в тексте тоже стоит
 * `entity.url` — но открывать их браузером значит увести человека от самого объекта.
 */
class OpenMeansTheLinkTest {

    @get:Rule val temp = TemporaryFolder()

    private var openedFile: File? = null
    private var openedUrl: String? = null

    private val opener = SystemOpener { file -> openedFile = file }
    private val browser = com.point.core.flow.BrowserOpener { url -> openedUrl = url }

    private fun objectOf(
        kind: ObjectKind,
        content: String? = null,
        metadata: Map<String, String> = emptyMap(),
        extension: String = ".txt",
    ): PointObject {
        val path = if (content == null) {
            File(temp.root, "нет-такого-" + System.nanoTime()).absolutePath
        } else {
            temp.newFile("объект-" + System.nanoTime() + extension).apply { writeText(content) }.absolutePath
        }
        return PointObject(
            id = "obj-" + System.nanoTime(),
            mime = "text/plain",
            uri = ScratchRef(path),
            state = ObjectState(kind),
            metadata = metadata,
        )
    }

    @Test fun `ссылка, поделённая текстом, открывается браузером компьютера, а не редактором`() = runTest {
        val address = "https://example.com/pointtest?a=1"
        val shared = objectOf(ObjectKind.TEXT, content = address, metadata = mapOf(META_ENTITY_URL to address))

        val result = PcOpenRealizer(opener, browser).perform(shared, null)

        assertTrue("шаг обязан состояться, а не отказать — $result", result is ActionResult.Done)
        assertEquals(address, openedUrl)
        assertNull("файл не уходит обработчику расширения, когда адрес известен", openedFile)
    }

    @Test fun `объект без адреса достаётся программе по умолчанию`() = runTest {
        val note = objectOf(ObjectKind.TEXT, content = "просто заметка без адреса")

        val result = PcOpenRealizer(opener, browser).perform(note, null)

        assertTrue("шаг обязан состояться, а не отказать — $result", result is ActionResult.Done)
        assertEquals(File(note.uri.value), openedFile)
        assertNull("браузер незачем звать без адреса", openedUrl)
    }

    @Test fun `объект-ссылка называет адрес собственным содержимым`() = runTest {
        val link = objectOf(ObjectKind.URL, content = "https://point.leerio.app/d/1")

        PcOpenRealizer(opener, browser).perform(link, null)

        assertEquals("https://point.leerio.app/d/1", openedUrl)
        assertNull(openedFile)
    }

    @Test fun `узел ссылки открывается по знанию, даже когда файла рядом нет`() = runTest {
        val fromQr = objectOf(
            ObjectKind.URL,
            content = null,
            metadata = mapOf(META_ENTITY_URL to "https://example.org/qr"),
        )

        val result = PcOpenLinkRealizer(browser).perform(fromQr, null)

        assertTrue("знание об адресе и есть адрес — $result", result is ActionResult.Done)
        assertEquals("https://example.org/qr", openedUrl)
    }

    // ===== граница правила: найденный внутри адрес объект ссылкой не делает =====

    @Test fun `снимок с найденным QR открывается просмотрщиком, а не браузером`() = runTest {
        val shot = objectOf(
            ObjectKind.IMAGE,
            content = "PNG",
            metadata = mapOf(META_ENTITY_URL to "https://point.app/x"),
            extension = ".png",
        )

        val result = PcOpenRealizer(opener, browser).perform(shot, null)

        assertTrue("шаг обязан состояться — $result", result is ActionResult.Done)
        assertEquals(File(shot.uri.value), openedFile)
        assertNull("найденный внутри адрес — у своего узла своё действие, снимок остаётся снимком", openedUrl)
    }

    @Test fun `документ с адресом в тексте открывается программой по умолчанию`() = runTest {
        val invoice = objectOf(
            ObjectKind.PDF,
            content = "%PDF-1.4 квитанция",
            metadata = mapOf(META_ENTITY_URL to "https://bank.example/receipt/77"),
            extension = ".pdf",
        )

        val result = PcOpenRealizer(opener, browser).perform(invoice, null)

        assertTrue("шаг обязан состояться — $result", result is ActionResult.Done)
        assertEquals(File(invoice.uri.value), openedFile)
        assertNull("человек просил документ, а не страницу банка", openedUrl)
    }

    @Test fun `заметка, внутри которой встретился адрес, открывается редактором`() = runTest {
        val note = objectOf(
            ObjectKind.TEXT,
            content = "созвон в четверг, ссылка https://meet.example/abc — не потеряй",
            metadata = mapOf(META_ENTITY_URL to "https://meet.example/abc"),
        )

        val result = PcOpenRealizer(opener, browser).perform(note, null)

        assertTrue("шаг обязан состояться — $result", result is ActionResult.Done)
        assertEquals(File(note.uri.value), openedFile)
        assertNull("адрес внутри текста — не сам текст", openedUrl)
    }

    // ===== отказ двери доходит до человека =====

    @Test fun `не открывшийся браузер называется отказом, а не словом Открыто`() = runTest {
        // Та самая дверь, что стоит в main, — только вместо живого браузера её зов срывается.
        val broken = SystemBrowser { throw java.io.IOException("на этом компьютере браузера нет") }
        val link = objectOf(ObjectKind.URL, content = "https://example.com/x")

        val result = PcOpenRealizer(opener, broken).perform(link, null)

        assertTrue("человеку сказали об успехе над не открывшимся браузером — $result", result is ActionResult.Failure)
        assertTrue("отказ поправимый — ссылка в буфере, можно открыть руками", (result as ActionResult.Failure).recoverable)
        assertNull("подменять браузер редактором нельзя", openedFile)
    }
}
