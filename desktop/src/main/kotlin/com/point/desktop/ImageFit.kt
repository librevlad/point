package com.point.desktop

import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Уложить снимок в предел сервиса, не спрашивая человека (#592).
 *
 * Владелец: «в десктопе не сжимается картинка сама». Прежде чтение в облаке упиралось в предел
 * и советовало сначала нажать «Сделать легче» — два тапа там, где человек хотел один.
 *
 * Это НЕ представления из поправки 4 ADR-0001: второго объекта на конвейере не появляется,
 * уменьшенная копия живёт ровно один поход к сервису. Примитив представлений заводится
 * отдельно (#728) — решение владельца 10.08.2026: «сначала узко, представления отдельной
 * карточкой».
 *
 * Молча подменять отданное человеком нельзя: о том, что читалось уменьшенное, говорит
 * [shrunkNote] в самом шаге.
 */
data class Fitted(
    val file: File,
    val was: Long,
    val now: Long,
    val width: Int,
    val height: Int,
)

object ImageFit {

    /**
     * `null` — уменьшать незачем (снимок и так в пределе) либо нечем (не картинка, либо не влез
     * даже самым мелким). Отказ остаётся отказом: выдавать не уложившееся за уложенное нельзя.
     */
    fun toFit(source: File, maxBytes: Long): Fitted? {
        val was = source.length()
        if (was <= maxBytes) return null
        val image = runCatching { ImageIO.read(source) }.getOrNull() ?: return null

        for (side in SIDES) {
            val scale = minOf(1.0, side.toDouble() / maxOf(image.width, image.height))
            val width = (image.width * scale).toInt().coerceAtLeast(1)
            val height = (image.height * scale).toInt().coerceAtLeast(1)
            val resized = redraw(image, width, height)
            for (quality in QUALITIES) {
                val out = writeJpeg(resized, quality) ?: continue
                if (out.length() <= maxBytes) {
                    return Fitted(out, was = was, now = out.length(), width = width, height = height)
                }
                out.delete()
            }
        }
        return null
    }

    private fun redraw(image: BufferedImage, width: Int, height: Int): BufferedImage {
        val resized = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = resized.createGraphics()
        g.drawImage(image.getScaledInstance(width, height, Image.SCALE_SMOOTH), 0, 0, null)
        g.dispose()
        return resized
    }

    private fun writeJpeg(image: BufferedImage, quality: Float): File? {
        val writer = ImageIO.getImageWritersByFormatName("jpg").asSequence().firstOrNull() ?: return null
        val out = File.createTempFile("pc-fit-", ".jpg").apply { deleteOnExit() }
        return runCatching {
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
                writer.write(null, IIOImage(image, null, null), params)
            }
            out
        }.getOrElse {
            out.delete()
            null
        }.also { writer.dispose() }
    }

    private val SIDES = listOf(2000, 1600, 1280, 1024, 800)

    private val QUALITIES = listOf(0.8f, 0.6f, 0.45f)
}

/** Слова человеку о подмене: он отдавал одно, а читалось другое. */
fun shrunkNote(fitted: Fitted): String =
    "читал уменьшенную копию — " + weight(fitted.was) + " не принимает сервис, ушло " + weight(fitted.now)

private fun weight(bytes: Long): String =
    if (bytes < 1024 * 1024) {
        (bytes / 1024).toString() + " КБ"
    } else {
        String.format(Locale.ROOT, "%.1f", bytes / 1048576.0).replace('.', ',') + " МБ"
    }
