package com.point.desktop

import com.point.core.flow.Box
import com.point.core.flow.EvidenceImage
import com.point.core.flow.ImageRedactor
import com.point.core.flow.Redaction
import com.point.core.flow.UprightAngle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Замазывание на компьютере (#549, #1379): ImageIO читает и пишет пиксели, решает — [Redaction],
 * тот же код, что у телефона.
 *
 * Снимок остаётся в своём разрешении, а результат — PNG: второе сжатие поверх исходного
 * добавляло бы артефакты к тому, что человек и так уже показал.
 *
 * Места — в пикселях файла, а кадр ставится, как его сняли, уже после замазывания: метка
 * поворота в PNG не переезжает, и без разворота портретный снимок вышел бы лежащим на боку.
 */
class PcImageRedactor : ImageRedactor {

    override suspend fun hide(imagePath: String, places: List<Box>): EvidenceImage? =
        withContext(Dispatchers.IO) {
            if (places.isEmpty()) return@withContext null
            val source = ImageIO.read(File(imagePath)) ?: return@withContext null
            val width = source.width
            val height = source.height
            val pixels = source.getRGB(0, 0, width, height, null, 0, width)
            val inside = places.filter { it.right > 0 && it.bottom > 0 && it.left < width && it.top < height }
            if (inside.isEmpty()) return@withContext null
            Redaction.hide(pixels, width, height, inside)

            val type = if (source.colorModel.hasAlpha()) {
                java.awt.image.BufferedImage.TYPE_INT_ARGB
            } else {
                java.awt.image.BufferedImage.TYPE_INT_RGB
            }
            val hidden = java.awt.image.BufferedImage(width, height, type)
            hidden.setRGB(0, 0, width, height, pixels, 0, width)
            val turn = photoHeadOf(File(imagePath))?.uprightDegrees ?: 0
            val out = if (turn == 0) hidden else uprightOpaque(hidden, turn)
            val bytes = ByteArrayOutputStream().also { ImageIO.write(out, "png", it) }.toByteArray()
            EvidenceImage(bytes, out.width, out.height, extension = "png")
        }
}

/**
 * На сколько развёрнут кадр в файле — по метке камеры из заголовка ([com.point.core.flow.photoHead]).
 * Вырезка тогда встаёт так же, как кадр, который человек видел (#1389).
 */
val PcUprightAngle = UprightAngle { path -> photoHeadOf(File(path))?.uprightDegrees ?: 0 }

/**
 * Место, обведённое на поставленном кадре, — в пиксели файла (#1379).
 *
 * Окно показывает снимок так, как его сняли, а исполнители режут и мажут файл как есть: так же
 * устроено и на телефоне. [rawWidth]×[rawHeight] — размер кадра в файле, [turn] — на сколько
 * его поворачивают по часовой стрелке, чтобы он встал.
 */
internal fun uprightToRaw(box: Box, turn: Int, rawWidth: Float, rawHeight: Float): Box = when (normalTurn(turn)) {
    90 -> Box(box.top, rawHeight - box.right, box.bottom, rawHeight - box.left)
    180 -> Box(rawWidth - box.right, rawHeight - box.bottom, rawWidth - box.left, rawHeight - box.top)
    270 -> Box(rawWidth - box.bottom, box.left, rawWidth - box.top, box.right)
    else -> box
}

/** Обратный ход: место в пикселях файла — на поставленный кадр, чтобы показать его там. */
internal fun rawToUpright(box: Box, turn: Int, rawWidth: Float, rawHeight: Float): Box = when (normalTurn(turn)) {
    90 -> Box(rawHeight - box.bottom, box.left, rawHeight - box.top, box.right)
    180 -> Box(rawWidth - box.right, rawHeight - box.bottom, rawWidth - box.left, rawHeight - box.top)
    270 -> Box(box.top, rawWidth - box.right, box.bottom, rawWidth - box.left)
    else -> box
}

private fun normalTurn(turn: Int): Int = ((turn % 360) + 360) % 360
