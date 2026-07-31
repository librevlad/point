package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Атом обязан знать своё место в **сыром** кадре, а не только в том битмапе, который прочитал
 * движок (ADR-0001, два адресных пространства).
 *
 * Разрыв реальный: перед OCR длинная сторона фото уменьшается, чтобы не словить OOM на большом
 * снимке (#18). Координаты слова после этого относятся к уменьшенной копии, и кроп по ним,
 * взятый из исходного файла, покажет не то место — а именно туда мы будем ходить перечитывать
 * сомнительное значение.
 */
class FrameTransformTest {

    @Test
    fun `координаты уменьшенной копии отображаются обратно в сырой кадр`() {
        val transform = FrameTransform(sample = 2)

        val raw = transform.toRaw(Box(10f, 20f, 30f, 40f))

        assertEquals(Box(20f, 40f, 60f, 80f), raw)
    }

    /**
     * Телефон хранит поворот в EXIF и отдаёт пиксели боком; перед OCR мы доворачиваем снимок,
     * иначе Tesseract читает боковые строки как мусор. После доворота координаты слова живут в
     * повёрнутой копии, а исходный файл остался боком — значит поворот надо отменить, чтобы кроп
     * попал в то же место.
     *
     * Слово `Box(10, 20, 30, 40)` в повёрнутой копии 100×200.
     */
    @Test
    fun `доворот по EXIF отменяется для всех четырёх ориентаций`() {
        val word = Box(10f, 20f, 30f, 40f)

        assertEquals(
            word,
            FrameTransform(sample = 1, rotationDegrees = 0, uprightWidth = 100, uprightHeight = 200)
                .toRaw(word),
        )
        assertEquals(
            Box(20f, 70f, 40f, 90f),
            FrameTransform(sample = 1, rotationDegrees = 90, uprightWidth = 100, uprightHeight = 200)
                .toRaw(word),
        )
        assertEquals(
            Box(70f, 160f, 90f, 180f),
            FrameTransform(sample = 1, rotationDegrees = 180, uprightWidth = 100, uprightHeight = 200)
                .toRaw(word),
        )
        assertEquals(
            Box(160f, 10f, 180f, 30f),
            FrameTransform(sample = 1, rotationDegrees = 270, uprightWidth = 100, uprightHeight = 200)
                .toRaw(word),
        )
    }

    /** Обратная дорога для экрана выделения (#259): подсветка атомов рисуется на выпрямленной
     *  копии, и круговой путь обязан вернуть исходный бокс — иначе рамка мимо слов. */
    @Test
    fun `круговой путь raw-upright-raw тождественен для всех ориентаций и масштаба`() {
        val word = Box(10f, 20f, 30f, 40f)

        listOf(0, 90, 180, 270).forEach { deg ->
            val t = FrameTransform(sample = 3, rotationDegrees = deg, uprightWidth = 100, uprightHeight = 200)

            assertEquals("rotation=$deg", word, t.toUpright(t.toRaw(word)))
            assertEquals("rotation=$deg", t.toRaw(word), t.toRaw(t.toUpright(t.toRaw(word))))
        }
    }
}
