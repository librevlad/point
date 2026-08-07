package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameTransformTest {

    @Test
    fun `координаты уменьшенной копии отображаются обратно в сырой кадр`() {
        val transform = FrameTransform(sample = 2)

        val raw = transform.toRaw(Box(10f, 20f, 30f, 40f))

        assertEquals(Box(20f, 40f, 60f, 80f), raw)
    }

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

    @Test
    fun `круговой путь raw-upright-raw тождественен для всех ориентаций и масштаба`() {
        val word = Box(10f, 20f, 30f, 40f)

        listOf(0, 90, 180, 270).forEach { deg ->
            val t = FrameTransform(sample = 3, rotationDegrees = deg, uprightWidth = 100, uprightHeight = 200)

            assertEquals("rotation=$deg", word, t.toUpright(t.toRaw(word)))
            assertEquals("rotation=$deg", t.toRaw(word), t.toRaw(t.toUpright(t.toRaw(word))))
        }
    }

    @Test
    fun `увеличение кадра перед чтением отменяется так же, как прореживание`() {
        val enlarged = FrameTransform(sample = 1, uprightWidth = 3000, uprightHeight = 2250, upscale = 3)

        assertEquals(Box(10f, 20f, 30f, 40f), enlarged.toRaw(Box(30f, 60f, 90f, 120f)))
    }

    @Test
    fun `прореживание и увеличение складываются в один множитель`() {
        val both = FrameTransform(sample = 2, uprightWidth = 4000, uprightHeight = 3000, upscale = 4)

        assertEquals(Box(5f, 10f, 15f, 20f), both.toRaw(Box(10f, 20f, 30f, 40f)))
    }

    @Test
    fun `круговой путь цел и для увеличенного кадра`() {
        val word = Box(12f, 24f, 36f, 48f)

        listOf(0, 90, 180, 270).forEach { deg ->
            val t = FrameTransform(
                sample = 2, rotationDegrees = deg, uprightWidth = 100, uprightHeight = 200, upscale = 4,
            )

            assertEquals("rotation=$deg", word, t.toUpright(t.toRaw(word)))
        }
    }

    @Test
    fun `нулевое увеличение отвергается на входе`() {
        assertThrows(IllegalArgumentException::class.java) { FrameTransform(sample = 1, upscale = 0) }
        assertThrows(IllegalArgumentException::class.java) { FrameTransform(sample = 1, upscale = -2) }
    }
}
