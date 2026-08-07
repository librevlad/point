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
        assertEquals(ObjectKind.URL, classifier.classify("text/uri-list").kind)
        assertEquals(ObjectKind.TEXT, classifier.classify("text/plain").kind)
    }

    @Test
    fun `tolerates charset suffix and case`() {
        assertEquals(ObjectKind.TEXT, classifier.classify("TEXT/Plain; charset=utf-8").kind)
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
}
