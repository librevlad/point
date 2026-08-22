package com.point.core.flow

import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectClassifierTest {

    private val classifier = ObjectClassifier()

    @Test
    fun `maps MIME to kind`() {
        assertEquals(ObjectKind.IMAGE, classifier.classify("image/png").kind)
        assertEquals(ObjectKind.PDF, classifier.classify("application/pdf").kind)
        assertEquals(ObjectKind.ZIP, classifier.classify("application/zip").kind)
        assertEquals(ObjectKind.URL, classifier.classify("text/uri-list", head = "https://example.com".toByteArray()).kind)
        assertEquals(ObjectKind.TEXT, classifier.classify("text/plain").kind)
    }

    // ---- #999: ссылка, переданная файлом, — ссылка только когда в байтах есть адрес. ----

    @Test
    fun `uri-list с адресом в байтах — ссылка`() {
        val head = "# заметка\r\nhttps://example.com/pointtest?a=1\r\n".toByteArray()

        assertEquals(ObjectKind.URL, classifier.classify("text/uri-list", 42, "link.txt", head).kind)
    }

    @Test
    fun `uri-list без адреса — не ссылка, а текстовый файл`() {
        val head = "просто строка без адреса".toByteArray()

        assertEquals(ObjectKind.TEXT, classifier.classify("text/uri-list", 24, "link.txt", head).kind)
    }

    @Test
    fun `uri-list без прочитанных байтов ссылкой не объявляется`() {
        assertEquals(ObjectKind.TEXT, classifier.classify("text/uri-list").kind)
    }

    @Test
    fun `tolerates charset suffix and case`() {
        assertEquals(ObjectKind.TEXT, classifier.classify("TEXT/Plain; charset=utf-8").kind)
    }

    // ---- Живой дефект 2026-08-08: файл «1-Перевод» без расширения с чистым текстом
    // внутри становился мёртвым UNKNOWN — байты объекта никто не спрашивал. ----

    @Test
    fun `файл без расширения с текстом внутри — текст, а не неизвестное`() {
        val head = "Invoice 4512 from LLC Romashka. Payable by September 20, 2026.".toByteArray()

        assertEquals(
            ObjectKind.TEXT,
            classifier.classify("application/octet-stream", 141, "1-Перевод", head).kind,
        )
    }

    @Test
    fun `знакомые сигнатуры байтов дают вид без имени и мима`() {
        fun kindOf(head: ByteArray) = classifier.classify("application/octet-stream", 10, null, head).kind

        assertEquals(ObjectKind.PDF, kindOf("%PDF-1.7\n".toByteArray()))
        assertEquals(ObjectKind.ZIP, kindOf(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0)))
        assertEquals(ObjectKind.IMAGE, kindOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        assertEquals(ObjectKind.IMAGE, kindOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals(ObjectKind.AUDIO, kindOf("OggS ".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(ObjectKind.AUDIO, kindOf("ID3".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun `заявленный вид байтами не переспоривается`() {
        val textHead = "просто текст".toByteArray()

        assertEquals(ObjectKind.IMAGE, classifier.classify("image/png", 10, null, textHead).kind)
    }

    @Test
    fun `бинарный мусор остаётся неизвестным`() {
        val binary = ByteArray(64) { if (it % 3 == 0) 0 else (it * 7).toByte() }

        assertEquals(ObjectKind.UNKNOWN, classifier.classify("application/octet-stream", 64, null, binary).kind)
        assertEquals(ObjectKind.UNKNOWN, classifier.classify("application/octet-stream", 0, null, ByteArray(0)).kind)
    }

    @Test
    fun `falls back to file extension when MIME is generic`() {
        assertEquals(
            ObjectKind.IMAGE,
            classifier.classify("application/octet-stream", fileName = "photo.JPG").kind,
        )
    }

    @Test
    fun `recognizes office documents by mime and by extension`() {
        assertEquals(
            ObjectKind.OFFICE,
            classifier.classify("application/vnd.openxmlformats-officedocument.wordprocessingml.document").kind,
        )

        assertEquals(ObjectKind.OFFICE, classifier.classify("application/zip", fileName = "report.docx").kind)
        assertEquals(ObjectKind.OFFICE, classifier.classify("application/octet-stream", fileName = "book.xlsx").kind)
    }

    @Test
    fun `recognizes broader archive formats as ZIP kind`() {
        assertEquals(ObjectKind.ZIP, classifier.classify("application/x-tar").kind)
        assertEquals(ObjectKind.ZIP, classifier.classify("application/gzip").kind)
        assertEquals(ObjectKind.ZIP, classifier.classify("application/octet-stream", fileName = "logs.tar.gz").kind)
    }

    @Test
    fun `голосовое из мессенджера — запись, каким бы типом его ни назвали`() {

        assertEquals(ObjectKind.AUDIO, classifier.classify("audio/ogg").kind)
        assertEquals(ObjectKind.AUDIO, classifier.classify("audio/opus").kind)
        assertEquals(ObjectKind.AUDIO, classifier.classify("audio/mpeg").kind)
        assertEquals(ObjectKind.AUDIO, classifier.classify("audio/mp4").kind)
        assertEquals(ObjectKind.AUDIO, classifier.classify("audio/amr").kind)
        assertEquals(ObjectKind.AUDIO, classifier.classify("audio/wav").kind)

        assertEquals(ObjectKind.AUDIO, classifier.classify("application/ogg").kind)
    }

    @Test
    fun `запись без типа узнаётся по расширению`() {

        assertEquals(ObjectKind.AUDIO, classifier.classify("application/octet-stream", fileName = "AUD-0001.OGG").kind)
        assertEquals(ObjectKind.AUDIO, classifier.classify("application/octet-stream", fileName = "voice.m4a").kind)
        assertEquals(ObjectKind.AUDIO, classifier.classify("application/octet-stream", fileName = "заметка.amr").kind)
    }

    @Test
    fun `музыка и голосовое — один вид, разбираться дальше не классификатору`() {

        assertEquals(ObjectKind.AUDIO, classifier.classify("audio/flac", fileName = "album.flac").kind)
    }

    @Test
    fun `classifies an unpacked directory as a COLLECTION`() {

        assertEquals(ObjectKind.COLLECTION, classifier.classify("inode/directory").kind)
    }

    @Test
    fun `flags large objects for deferred enrichment`() {
        val small = classifier.classify("application/zip", sizeBytes = 1_000)
        val large = classifier.classify("application/zip", sizeBytes = 200L * 1024 * 1024)
        assertFalse(small.has(Feature.LARGE))
        assertTrue(large.has(Feature.LARGE))
    }

    // ---- #684: пустота — нулевой сигнал первого экрана, а не находка после тапа. ----

    @Test
    fun `файл без единого байта отмечен негодным сразу, без чтения`() {
        val empty = classifier.classify("text/plain", sizeBytes = 0L, fileName = "note.txt")

        assertTrue(empty.has(Feature.UNUSABLE))
    }

    @Test
    fun `у файла с содержимым пометки негодности нет`() {
        val filled = classifier.classify("text/plain", sizeBytes = 141L, fileName = "note.txt")

        assertFalse(filled.has(Feature.UNUSABLE))
    }

    @Test
    fun `набор файлов — не пустой файл, у папки размер ничего не значит`() {
        val collection = classifier.classify("inode/directory", sizeBytes = 0L)

        assertFalse(collection.has(Feature.UNUSABLE))
        assertEquals(ObjectKind.COLLECTION, collection.kind)
    }
}
