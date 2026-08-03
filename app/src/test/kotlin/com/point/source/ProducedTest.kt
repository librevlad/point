package com.point.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Превращение добытого в объект — чистые функции: что именно родится из буфера и из снимка,
 * решается здесь и судится на JVM. Источникам остаётся системная работа.
 */
class ProducedTest {

    @Test
    fun `текст из буфера ложится в файл и становится текстовым объектом`() {
        val produced = clipToProduced(
            text = "накладная 204514", uri = null, mime = null,
            textFile = { "file:///scratch/clip.txt" },
        )
        assertEquals(Produced("file:///scratch/clip.txt", "text/plain"), produced)
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
        val produced = captureToProduced("/scratch/shot.jpg", sizeBytes = 240_000)
        assertEquals(Produced("/scratch/shot.jpg", "image/jpeg"), produced)
    }

    @Test
    fun `отменённая съёмка оставляет пустой файл — объекта нет`() {
        // Камера создаёт файл заранее; отмена оставляет его нулевым, и это НЕ объект.
        assertNull(captureToProduced("/scratch/shot.jpg", sizeBytes = 0))
    }
}
