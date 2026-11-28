package com.point.desktop

import com.point.core.flow.META_UNUSABLE_REASON
import com.point.core.flow.READER_NOT_DECODED
import com.point.core.flow.Resolver
import com.point.core.flow.readerFailure
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Обломок вместо PDF компьютер называет так же, как телефон, — повреждённым (#994, #1101).
 *
 * Прежде компьютер такой файл не узнавал: не прочитанный слой — не скан, а пометку негодности
 * получал только пустой файл. Человеку предлагались все двери PDF подряд, и каждая по нажатию
 * отказывала своими словами мимо дела: «Извлечь текст» винило компьютер, «В Word» советовало
 * распознать скан. Телефон тот же файл называет одной общей фразой и двери чтения ему не
 * предлагает. PDF здесь настоящий и битый настоящими байтами — выдуманный ответ ридера ничего
 * не доказал бы.
 */
class BrokenPdfOnPcTest {

    @get:Rule val temp = TemporaryFolder()

    /** Те же слова, что у телефона: их произносит один словарь ридера. */
    private val sharedWords = readerFailure(READER_NOT_DECODED, ObjectKind.PDF)

    private fun realPdf(name: String = "счёт.pdf"): File {
        val file = temp.newFile(name)
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { out ->
                out.beginText()
                out.setFont(PDType1Font.HELVETICA, 12f)
                out.newLineAtOffset(50f, 700f)
                out.showText("Invoice 4512 total 320")
                out.endText()
            }
            doc.save(file)
        }
        return file
    }

    /** Письмо, сохранённое с расширением PDF: байты есть, документа нет. */
    private fun notAPdf(): File = temp.newFile("накладная.pdf").apply {
        writeText("Добрый день! Накладную пришлю завтра.\r\nОльга")
    }

    /** Докачанный наполовину: заголовок PDF на месте, а тела документа нет. */
    private fun cutShort(): File {
        val whole = realPdf("целый.pdf").readBytes()
        return temp.newFile("оборванный.pdf").apply { writeBytes(whole.copyOf(24)) }
    }

    private fun window(linked: Boolean = false): DesktopState = DesktopState(
        DesktopRegistry(desktopCapabilities()),
        object : Resolver {
            override fun realizerFor(capabilityId: CapabilityId) = error("до нажатия исполнитель не нужен")
        },
        clipboard = { },
        linked = { linked },
    )

    @Test
    fun `письмо с расширением PDF помечено негодным словами телефона`() {
        val item = Inbox(temp.newFolder("inbox")).addFile(notAPdf().absolutePath)

        assertEquals(ObjectKind.PDF, item.obj.state.kind)
        assertTrue("обломок снова сойдёт за документ", item.obj.state.has(Feature.UNUSABLE))
        assertEquals(sharedWords, item.obj.metadata[META_UNUSABLE_REASON])
        assertFalse("обломок не скан: в нём нечего читать и страницами", item.obj.state.has(Feature.IS_IMAGE_PDF))
    }

    @Test
    fun `оборванный PDF помечен негодным теми же словами`() {
        val item = Inbox(temp.newFolder("inbox")).addFile(cutShort().absolutePath)

        assertTrue(item.obj.state.has(Feature.UNUSABLE))
        assertEquals(sharedWords, item.obj.metadata[META_UNUSABLE_REASON])
    }

    @Test
    fun `обломку PDF не предлагается ни одной двери чтения — и оставшиеся называют причину до нажатия`() {
        val st = window()
        val item = Inbox(temp.newFolder("inbox")).addFile(notAPdf().absolutePath)
        st.onReceived(item)

        val doors = st.actionsFor(item).mapNotNull { it.bubble }
        val reading = doors.filter { door ->
            Intent.UNDERSTAND in DesktopRegistry(desktopCapabilities()).byId(door.capabilityId).intents(item.obj.state)
        }

        assertTrue("обломку предложили прочитать себя: ${reading.map { it.title }}", reading.isEmpty())
        val ids = doors.map { it.capabilityId.value }
        assertFalse("«Извлечь текст» снова откажет «Компьютер не смог открыть»", "pdf" in ids)
        assertFalse("«Прочитать документ» отправит обломок в сервис", pcReadDocument().id.value in ids)
        assertTrue("у обломка не осталось даже «Открыть»", "pc-open" in ids)
        assertTrue(
            "оставшаяся дверь молчит о причине до нажатия: ${doors.filter { it.unusableReason != sharedWords }.map { it.title }}",
            doors.all { it.unusableReason == sharedWords },
        )
    }

    /**
     * Дверь чтения, которую компьютер у обломка спрятал, не возвращается телефонной (#994,
     * #1101): иначе «Извлечь текст · на телефоне» встало бы на место спрятанного своего и повезло
     * бы обломок на телефон — отказывать там. Своими компьютер считает и двери, которые прячет
     * сам, а не только показанные.
     */
    @Test
    fun `обломку PDF и с телефоном рядом не предлагается телефонная дверь чтения`() {
        val st = window(linked = true)
        st.setPhoneCaps(
            com.point.core.flow.advertisedActions(
                com.point.core.flow.capabilities.sharedCapabilities() + com.point.core.flow.ReadDocumentCapability(),
            ),
            persist = false,
        )
        val item = Inbox(temp.newFolder("inbox")).addFile(notAPdf().absolutePath)
        st.onReceived(item)

        val hiddenHere = desktopCapabilities()
            .filter { Intent.UNDERSTAND in it.intents(item.obj.state) }
            .map { it.id.value }
            .toSet()
        val phoneReading = st.actionsFor(item).mapNotNull { it.remote }.filter { it.id in hiddenHere }

        assertTrue("обломку предложили прочитать себя на телефоне: ${phoneReading.map { it.label }}", phoneReading.isEmpty())
    }

    @Test
    fun `целый PDF ничего не теряет`() {
        val st = window()
        val item = Inbox(temp.newFolder("inbox")).addFile(realPdf().absolutePath)
        st.onReceived(item)

        assertFalse(item.obj.state.has(Feature.UNUSABLE))
        assertNull(item.obj.metadata[META_UNUSABLE_REASON])
        val doors = st.actionsFor(item).mapNotNull { it.bubble }
        assertTrue("у целого документа пропало «Извлечь текст»", doors.any { it.capabilityId.value == "pdf" })
        assertTrue("целый документ назван негодным", doors.none { it.unusableReason != null })
    }

    /** Пустой файл уже назван пустым: вторая причина поверх первой — не то, что он есть. */
    @Test
    fun `пустой PDF остаётся пустым, а не повреждённым`() {
        val item = Inbox(temp.newFolder("inbox")).addFile(temp.newFile("пусто.pdf").absolutePath)

        assertTrue(item.obj.state.has(Feature.UNUSABLE))
        assertEquals(com.point.core.flow.EMPTY_FILE_REASON, item.obj.metadata[META_UNUSABLE_REASON])
    }

    /**
     * Документ под паролем цел — его нельзя открыть без ключа, но он не обломок. Негодным его
     * не объявляют: «не прочиталось» не равно «повреждён» (Конституция §13).
     */
    @Test
    fun `PDF под паролем не объявляется повреждённым`() {
        val locked = temp.newFile("под-паролем.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val policy = org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                "owner",
                "key",
                org.apache.pdfbox.pdmodel.encryption.AccessPermission(),
            )
            doc.protect(policy)
            doc.save(locked)
        }

        val item = Inbox(temp.newFolder("inbox")).addFile(locked.absolutePath)

        assertFalse("документ под паролем назван обломком", item.obj.state.has(Feature.UNUSABLE))
        assertNull(item.obj.metadata[META_UNUSABLE_REASON])
    }

    /**
     * Файл, который сейчас не читается, — не обломок (Конституция §13). Заперт другой программой,
     * лежит в OneDrive без сети, нет прав — pdfbox отвечает тем же `IOException`, что и на
     * обломок, а метку «повреждён» в сеансе снять нечем: человек терял двери чтения у целого
     * документа и получал отказ «С этим файлом нечего делать» на каждой двери наружу.
     */
    @Test
    fun `PDF, который сейчас не читается, повреждённым не объявляется`() {
        val healthy = realPdf()

        val item = whileUnreadable(healthy) { Inbox(temp.newFolder("inbox")).addFile(healthy.absolutePath) }

        assertEquals(ObjectKind.PDF, item.obj.state.kind)
        assertFalse("файл, который просто не прочитался, назван обломком", item.obj.state.has(Feature.UNUSABLE))
        assertNull(item.obj.metadata[META_UNUSABLE_REASON])
    }

    /** Чтение, сорвавшееся посреди разбора (отвалилась сетевая папка), — тоже про попытку. */
    @Test
    fun `чтение, сорвавшееся посреди разбора, приговором файлу не становится`() {
        val healthy = realPdf()
        val dropsMidway = PdfBoxText { file ->
            BreaksAfter(org.apache.pdfbox.io.RandomAccessBufferedFileInputStream(file), healthy.length() + 64)
        }

        assertNull("сорвавшееся чтение записано поломкой файла", dropsMidway.trouble(healthy))
        assertNull("целый документ назван обломком", PdfBoxText().trouble(healthy))
        assertEquals("обломок перестал узнаваться", READER_NOT_DECODED, PdfBoxText().trouble(notAPdf()))
    }

    /**
     * Защита сертификатом и защита, которой pdfbox не знает, — тот же целый документ, что и под
     * паролем: pdfbox называет их голым `IOException`, но поломкой они не становятся. Телефон
     * такой документ тоже не зовёт повреждённым — его ридер отвечает отказом доступа.
     */
    @Test
    fun `PDF под защитой сертификатом не объявляется повреждённым`() {
        val item = Inbox(temp.newFolder("inbox")).addFile(protectedBy("Adobe.PubSec", "под-сертификатом").absolutePath)

        assertFalse("документ под сертификатом назван обломком", item.obj.state.has(Feature.UNUSABLE))
        assertNull(item.obj.metadata[META_UNUSABLE_REASON])
    }

    @Test
    fun `PDF под незнакомой защитой не объявляется повреждённым`() {
        val item = Inbox(temp.newFolder("inbox")).addFile(protectedBy("NoSuchDRM", "под-чужой-защитой").absolutePath)

        assertFalse("документ под чужой защитой назван обломком", item.obj.state.has(Feature.UNUSABLE))
        assertNull(item.obj.metadata[META_UNUSABLE_REASON])
    }

    /** Целый документ, объявивший защиту [filter]: ключ `/Encrypt` в трейлере, тело нетронуто. */
    private fun protectedBy(filter: String, name: String): File {
        val plain = realPdf("$name-открытый.pdf").readBytes().toString(Charsets.ISO_8859_1)
        val trailer = plain.lastIndexOf("trailer")
        val opens = plain.indexOf("<<", trailer)
        check(trailer >= 0 && opens > trailer) { "у документа не нашёлся трейлер" }

        // Трейлер стоит после таблицы ссылок: вставка в него не сдвигает ни одного адреса.
        val sealed = plain.substring(0, opens + 2) +
            "\n/Encrypt << /Filter /$filter /SubFilter /adbe.pkcs7.s4 /V 4 /R 4 /Length 128 >>" +
            plain.substring(opens + 2)
        return temp.newFile("$name.pdf").apply { writeBytes(sealed.toByteArray(Charsets.ISO_8859_1)) }
    }

    /**
     * Пока идёт [body], файл не читается: на Windows его запирает замок, на остальных — снятое
     * право чтения. Система, которая не умеет ни того, ни другого, проверку пропускает.
     */
    private fun <T> whileUnreadable(file: File, body: () -> T): T {
        if (file.setReadable(false) && !readable(file)) {
            try {
                return body()
            } finally {
                file.setReadable(true)
            }
        }
        java.nio.channels.FileChannel.open(
            file.toPath(),
            java.nio.file.StandardOpenOption.READ,
            java.nio.file.StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use {
                org.junit.Assume.assumeFalse("эта система не запирает файл от чтения", readable(file))
                return body()
            }
        }
    }

    private fun readable(file: File) = runCatching { file.inputStream().use { it.read() } }.isSuccess

    /** Источник байтов, который отдаёт [left] байт и обрывается, как отвалившаяся сетевая папка. */
    private class BreaksAfter(
        private val inner: org.apache.pdfbox.io.RandomAccessRead,
        private var left: Long,
    ) : org.apache.pdfbox.io.RandomAccessRead by inner {

        private fun took(count: Int): Int {
            if (count > 0) {
                left -= count
                if (left < 0) throw java.io.IOException("The specified network name is no longer available")
            }
            return count
        }

        override fun read(): Int = inner.read().also { if (it >= 0) took(1) }
        override fun read(b: ByteArray): Int = took(inner.read(b))
        override fun read(b: ByteArray, offset: Int, length: Int): Int = took(inner.read(b, offset, length))
        override fun readFully(length: Int): ByteArray = inner.readFully(length).also { took(it.size) }
    }
}
