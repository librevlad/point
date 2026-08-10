package com.point.desktop

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чтение в облаке само укладывается в предел сервиса (#592): человек не делает второй тап
 * «Сделать легче» там, где хотел один.
 */
class CloudReadFitsItselfTest {

    @Test
    fun `тяжёлый снимок укладывается в предел сервиса`() {
        val heavy = noisyJpeg(2400, 1800)
        assertTrue("снимок должен быть тяжелее предела", heavy.length() > LIMIT)

        val fitted = ImageFit.toFit(heavy, LIMIT)

        assertNotNull("тяжёлый снимок обязан быть уменьшен", fitted)
        assertTrue("уменьшенное не влезло: " + fitted!!.now, fitted.now <= LIMIT)
        assertTrue("сторона должна уменьшиться", fitted.width < 2400)
        assertEquals(heavy.length(), fitted.was)
    }

    @Test
    fun `лёгкий снимок не трогается вовсе`() {
        val light = noisyJpeg(200, 150)
        assertTrue("снимок должен быть легче предела", light.length() < LIMIT)

        assertNull("лёгкое уменьшать незачем", ImageFit.toFit(light, LIMIT))
    }

    @Test
    fun `уменьшенное остаётся картинкой, а не обрубком`() {
        val fitted = ImageFit.toFit(noisyJpeg(2400, 1800), LIMIT)

        val reopened = ImageIO.read(fitted!!.file)

        assertNotNull("уменьшенное должно открываться как картинка", reopened)
        assertEquals(fitted.width, reopened.width)
        assertEquals(fitted.height, reopened.height)
    }

    @Test
    fun `человеку сказано, что читалось уменьшенное, а не отданное им`() {
        val note = shrunkNote(Fitted(File("x"), was = 3_500_000, now = 700_000, width = 1200, height = 900))

        assertTrue(note, note.contains("уменьшенн"))
        assertTrue("названы обе величины: " + note, note.contains("3,3 МБ") && note.contains("683 КБ"))
    }

    @Test
    fun `не пролезшее ни при каком уменьшении не выдаёт себя за уложенное`() {
        val impossible = ImageFit.toFit(noisyJpeg(2400, 1800), maxBytes = 1)

        assertTrue("невозможное уменьшение не должно объявляться удавшимся", impossible == null)
    }

    private fun noisyJpeg(width: Int, height: Int): File {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val random = Random(width * 31 + height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF))
            }
        }
        val file = File.createTempFile("cloud-read-", ".jpg").apply { deleteOnExit() }
        ImageIO.write(image, "jpg", file)
        return file
    }

    private companion object {

        const val LIMIT = 1024L * 1024
    }
}
