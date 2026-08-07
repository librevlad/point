package com.point.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProducedTest {

    private val takenAt = 1_754_325_912_345L
    private val stamp: String get() = com.point.core.flow.stampLabel(takenAt)

    @Test
    fun `текст из буфера ложится в файл и становится текстовым объектом`() {
        val produced = clipToProduced(
            text = "накладная 204514", uri = null, mime = null,
            textFile = { "file:///scratch/clip.txt" },
        )

        assertEquals(Produced("file:///scratch/clip.txt", "text/plain", "накладная 204514"), produced)
    }

    @Test
    fun `файлу из буфера имя не переписывают`() {
        val produced = clipToProduced(
            text = null, uri = "content://media/42", mime = "image/png",
            textFile = { error("файл не пишем") },
        )
        assertNull(produced?.name)
    }

    @Test
    fun `файл из буфера идёт своей ссылкой и своим типом`() {
        val produced = clipToProduced(
            text = null, uri = "content://media/42", mime = "image/png",
            textFile = { error("файл не пишем") },
        )
        assertEquals(Produced("content://media/42", "image/png"), produced)
    }

    @Test
    fun `у файла без типа — общий тип, а не выдуманный`() {
        val produced = clipToProduced(
            text = null, uri = "content://media/42", mime = null,
            textFile = { error("файл не пишем") },
        )
        assertEquals(Produced("content://media/42", "application/octet-stream"), produced)
    }

    @Test
    fun `пустой буфер — ничего, а не пустой объект`() {
        assertNull(clipToProduced(text = "   ", uri = null, mime = null, textFile = { "нет" }))
        assertNull(clipToProduced(text = null, uri = null, mime = null, textFile = { "нет" }))
    }

    @Test
    fun `снятый кадр становится объектом-картинкой`() {
        val produced = captureToProduced("/scratch/shot.jpg", sizeBytes = 240_000, epochMillis = takenAt)
        assertEquals(Produced("/scratch/shot.jpg", "image/jpeg", "Снимок, $stamp"), produced)
    }

    @Test
    fun `имя кадра называет, что это и когда снято`() {
        val name = captureToProduced("/scratch/shot.jpg", sizeBytes = 1, epochMillis = takenAt)?.name
        assertEquals("Снимок, $stamp", name)
        assertTrue("имя снова машинное: $name", name!!.startsWith("Снимок") && !name.contains("shot"))
    }

    @Test
    fun `отменённая съёмка оставляет пустой файл — объекта нет`() {

        assertNull(captureToProduced("/scratch/shot.jpg", sizeBytes = 0))
    }

    @Test
    fun `принятый файл становится объектом своего типа`() {
        val produced = receivedToProduced(
            path = "/cache/pulled/отчёт.pdf", mime = "application/pdf",
            exists = { true }, toUri = { "file://$it" },
        )
        assertEquals(Produced("file:///cache/pulled/отчёт.pdf", "application/pdf"), produced)
    }

    @Test
    fun `у принятого файла без типа — общий тип, а не выдуманный`() {
        val produced = receivedToProduced(
            path = "/cache/pulled/f", mime = "  ",
            exists = { true }, toUri = { "file://$it" },
        )
        assertEquals(Produced("file:///cache/pulled/f", "application/octet-stream"), produced)
    }

    @Test
    fun `отменённое ожидание — ничего, а не пустой объект`() {
        assertNull(receivedToProduced(null, null, exists = { true }, toUri = { it }))
        assertNull(receivedToProduced("   ", "text/plain", exists = { true }, toUri = { it }))
    }

    @Test
    fun `пустой файл объектом не становится`() {

        assertNull(receivedToProduced("/cache/pulled/f", "application/pdf", exists = { false }, toUri = { it }))
    }
}
