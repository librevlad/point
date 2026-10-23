package com.point.desktop

import com.point.vision.PhotoVision
import com.point.vision.depthToSpace
import com.point.vision.whiteBalanceGains
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import java.awt.Color
import java.awt.image.BufferedImage

/**
 * «Улучшить фото» (#433): конвейер исполняется по-настоящему, той же OpenCV, что у компьютера.
 *
 * Проверяется главное обещание — не испортить: хороший снимок выходит почти таким же, тёмный
 * светлеет, мелкий становится вдвое крупнее, а крупный остаётся своего размера.
 */
class PhotoVisionTest {

    /** Ровно освещённая сцена во весь диапазон яркости: плавные переходы, цветные пятна, без шума. */
    private fun scene(width: Int, height: Int, light: Double = 1.0): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) {
            val r = (255.0 * x / width) * light
            val g = (255.0 * y / height) * light
            val b = (255.0 * (x + y) / (width + height)) * light
            image.setRGB(x, y, Color(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255)).rgb)
        }
        image.createGraphics().apply {
            color = Color((200 * light).toInt(), (40 * light).toInt(), (40 * light).toInt())
            fillOval(width / 5, height / 4, width / 3, height / 3)
            color = Color((30 * light).toInt(), (160 * light).toInt(), (70 * light).toInt())
            fillRect(width / 2, height / 2, width / 3, height / 4)
            dispose()
        }
        return image
    }

    private fun enhanced(image: BufferedImage): BufferedImage =
        onFrame(image) { rgba, scratch -> imageOf(runBlocking { PhotoVision.enhance(rgba, scratch) }) }

    private fun meanLuma(image: BufferedImage): Double {
        val mat = rgbaMatOf(image)
        try {
            val m = Core.mean(mat).`val`
            return 0.299 * m[0] + 0.587 * m[1] + 0.114 * m[2]
        } finally {
            mat.release()
        }
    }

    @Test
    fun `мелкий снимок становится вдвое крупнее, крупный остаётся своего размера`() {
        assumeTrue("OpenCV для этой машины не загрузилась", PcOpenCv.available)

        val small = enhanced(scene(400, 300))
        assertEquals(800, small.width)
        assertEquals(600, small.height)

        val large = enhanced(scene(1400, 1000))
        assertEquals(1400, large.width)
        assertEquals(1000, large.height)
    }

    @Test
    fun `тёмный снимок светлеет`() {
        assumeTrue("OpenCV для этой машины не загрузилась", PcOpenCv.available)

        val dark = scene(1400, 1000, light = 0.35)
        assertTrue(meanLuma(enhanced(dark)) > meanLuma(dark) + 20)
    }

    @Test
    fun `хороший снимок выходит почти таким же`() {
        assumeTrue("OpenCV для этой машины не загрузилась", PcOpenCv.available)

        val good = scene(1400, 1000)
        val out = enhanced(good)
        val a = rgbaMatOf(good)
        val b = rgbaMatOf(out)
        val diff = Mat()
        try {
            Core.absdiff(a, b, diff)
            val m = Core.mean(diff).`val`
            assertTrue("снимок изменился слишком сильно: ${m.take(3)}", m.take(3).all { it < 12.0 })
        } finally {
            a.release()
            b.release()
            diff.release()
        }
    }

    @Test
    fun `цвет правится только когда обе оценки света согласны`() {
        // Края говорят «мало синего», средние — «синего много»: сцена синяя сама, свет не виноват.
        val disagree = whiteBalanceGains(doubleArrayOf(10.0, 10.0, 5.0), doubleArrayOf(100.0, 100.0, 200.0))
        assertArrayEquals(doubleArrayOf(1.0, 1.0, 1.0), disagree, 1e-9)

        // Обе говорят «мало синего» — синий поднимается, но не больше чем на 8 %.
        val agree = whiteBalanceGains(doubleArrayOf(10.0, 10.0, 5.0), doubleArrayOf(120.0, 120.0, 60.0))
        assertTrue(agree[2] > 1.0 && agree[2] <= 1.08 + 1e-9)
    }

    @Test
    fun `четыре канала раскладываются в картинку вдвое крупнее`() {
        // Сетка 1×2, четыре канала: канал 2i + j ложится в точку (2y + i, 2x + j).
        val channels = floatArrayOf(
            1f, 2f, // канал 0
            3f, 4f, // канал 1
            5f, 6f, // канал 2
            7f, 8f, // канал 3
        )
        val out = depthToSpace(channels, h = 1, w = 2)
        assertArrayEquals(floatArrayOf(1f, 3f, 2f, 4f, 5f, 7f, 6f, 8f), out, 0f)
    }
}
