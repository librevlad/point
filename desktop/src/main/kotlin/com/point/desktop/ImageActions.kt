package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class PcShrinkImageRealizer(private val outbox: Outbox) : Realizer {
    override val capabilityId = com.point.core.flow.capabilities.ImageCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = File(input.uri.value).takeIf(File::isFile)
                    ?: return@withContext ActionResult.Failure("Файла картинки нет на диске", recoverable = false)
                val image = ImageIO.read(source)
                    ?: return@withContext ActionResult.Failure(
                        "Это не картинка или формат, который компьютер не читает",
                        recoverable = false,
                    )
                val scale = minOf(1.0, MAX_SIDE.toDouble() / maxOf(image.width, image.height))
                val transparent = image.colorModel.hasAlpha()
                if (scale >= 1.0 && !transparent && source.length() < ALREADY_SMALL) {

                    return@withContext ActionResult.Failure(
                        "Эта картинка и так лёгкая — уменьшать нечего",
                        recoverable = false,
                    )
                }
                val width = (image.width * scale).toInt().coerceAtLeast(1)
                val height = (image.height * scale).toInt().coerceAtLeast(1)
                val type = if (transparent) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
                val resized = BufferedImage(width, height, type)
                val g = resized.createGraphics()
                g.drawImage(image.getScaledInstance(width, height, Image.SCALE_SMOOTH), 0, 0, null)
                g.dispose()
                val format = if (transparent) "png" else "jpg"
                val out = File.createTempFile("pc-small-", ".$format")
                ImageIO.write(resized, format, out)

                ActionResult.Success(
                    com.point.core.model.ResultObject(
                        type = ObjectKind.IMAGE,
                        mime = if (transparent) "image/png" else "image/jpeg",
                        uri = ScratchRef(out.absolutePath),
                        metadata = mapOf(
                            "name" to ("Лёгкая · " + mb(out.length()) + " · " + width + "×" + height),
                        ),
                    ),
                )
            }.getOrElse {
                ActionResult.Failure("Уменьшить не вышло — этот формат картинки компьютер не открывает", recoverable = true)
            }
        }

    private fun mb(bytes: Long): String =
        if (bytes < 1024 * 1024) (bytes / 1024).toString() + " КБ" else String.format("%.1f МБ", bytes / 1048576.0)

    private companion object {

        const val MAX_SIDE = 1920

        const val ALREADY_SMALL = 300L * 1024
    }
}
