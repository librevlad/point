package com.point.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Превращение добытого в объект — чистые функции: что именно родится из буфера и из снимка,
 * решается здесь и судится на JVM. Источникам остаётся системная работа.
 */
class ProducedTest {

    /** Момент съёмки. Ожидаемое время берётся у той же [stampLabel] — иначе тест проверял бы не
     *  имя кадра, а часовой пояс машины, на которой его запустили (сам формат пришит `AgoLabelTest`). */
    private val takenAt = 1_754_325_912_345L
    private val stamp: String get() = com.point.core.flow.stampLabel(takenAt)

    @Test
    fun `текст из буфера ложится в файл и становится текстовым объектом`() {
        val produced = clipToProduced(
            text = "накладная 204514", uri = null, mime = null,
            textFile = { "file:///scratch/clip.txt" },
        )
        // Имя — первые слова самого текста (#533): файл в кэше остаётся `clip-…​.txt`, но в
        // «Недавнем» человек читает то, что скопировал.
        assertEquals(Produced("file:///scratch/clip.txt", "text/plain", "накладная 204514"), produced)
    }

    /** Файл пришёл со своим именем — выдумывать поверх него нечего и незачем. */
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

    /** #533: до правки кадр звался `shot-1754325912345.jpg`, и два снимка подряд в «Недавнем»
     *  различались только временем ПОД строкой — то есть не различались вовсе. */
    @Test
    fun `имя кадра называет, что это и когда снято`() {
        val name = captureToProduced("/scratch/shot.jpg", sizeBytes = 1, epochMillis = takenAt)?.name
        assertEquals("Снимок, $stamp", name)
        assertTrue("имя снова машинное: $name", name!!.startsWith("Снимок") && !name.contains("shot"))
    }

    @Test
    fun `отменённая съёмка оставляет пустой файл — объекта нет`() {
        // Камера создаёт файл заранее; отмена оставляет его нулевым, и это НЕ объект.
        assertNull(captureToProduced("/scratch/shot.jpg", sizeBytes = 0))
    }

    // --- «Принять файл» (#388): файл из чужих рук ------------------------------------------

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
        // Тот же разрез, что у камеры: пустая карточка вместо файла хуже честной тишины.
        assertNull(receivedToProduced("/cache/pulled/f", "application/pdf", exists = { false }, toUri = { it }))
    }
}
