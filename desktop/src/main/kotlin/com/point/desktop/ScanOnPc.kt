package com.point.desktop

import com.point.core.flow.META_WHOLE_FRAME
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerMeta
import com.point.core.flow.reportStage
import com.point.core.flow.wholeFrameNote
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.tr
import com.point.vision.PageVision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * OpenCV компьютера (#1379): та же библиотека, что у телефона, своей сборкой для JVM.
 *
 * Нативная часть грузится один раз и не на первом экране: скан зовут тапом, а не при
 * открытии окна. Не загрузилась — скан компьютера честно недоступен, а не падает.
 */
object PcOpenCv {
    val available: Boolean by lazy { runCatching { nu.pattern.OpenCV.loadLocally(); true }.getOrDefault(false) }
}

/** Кадр — в RGBA-матрицу: так его ждёт общее зрение страницы, как ждёт и на телефоне. */
internal fun rgbaMatOf(image: BufferedImage): Mat {
    val width = image.width
    val height = image.height
    val argb = image.getRGB(0, 0, width, height, null, 0, width)
    val bytes = ByteArray(argb.size * 4)
    for (i in argb.indices) {
        val p = argb[i]
        bytes[i * 4] = (p shr 16).toByte()
        bytes[i * 4 + 1] = (p shr 8).toByte()
        bytes[i * 4 + 2] = p.toByte()
        bytes[i * 4 + 3] = (p ushr 24).toByte()
    }
    return Mat(height, width, CvType.CV_8UC4).apply { put(0, 0, bytes) }
}

/** RGBA-матрица — в картинку без прозрачности: страница уходит JPEG'ом и в PDF. */
internal fun imageOf(rgba: Mat): BufferedImage {
    val width = rgba.cols()
    val height = rgba.rows()
    val bytes = ByteArray(width * height * 4)
    rgba.get(0, 0, bytes)
    val rgb = IntArray(width * height) { i ->
        ((bytes[i * 4].toInt() and 0xFF) shl 16) or
            ((bytes[i * 4 + 1].toInt() and 0xFF) shl 8) or
            (bytes[i * 4 + 2].toInt() and 0xFF)
    }
    return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, width, height, rgb, 0, width) }
}

/** Работа над матрицами кадра: всё, что создано по дороге, освобождается здесь. */
internal inline fun <T> onFrame(image: BufferedImage, work: (Mat, MutableList<Mat>) -> T): T {
    val rgba = rgbaMatOf(image)
    val scratch = mutableListOf(rgba)
    try {
        return work(rgba, scratch)
    } finally {
        scratch.forEach { it.release() }
    }
}

/**
 * Снимок стоя, как его сняли, и не крупнее [maxPx] по длинной стороне — тот же предел, что у
 * телефона: страница в 50 Мп скану ничего не прибавляет, а память и время съедает.
 */
internal fun uprightPicture(file: File, maxPx: Int = SCAN_MAX_PX): BufferedImage? {
    val raw = runCatching { ImageIO.read(file) }.getOrNull() ?: return null
    val upright = uprightOpaque(raw, photoHeadOf(file)?.uprightDegrees ?: 0)
    val longSide = maxOf(upright.width, upright.height)
    if (longSide <= maxPx) return upright
    val scale = maxPx.toDouble() / longSide
    val width = (upright.width * scale).toInt().coerceAtLeast(1)
    val height = (upright.height * scale).toInt().coerceAtLeast(1)
    return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(upright, 0, 0, width, height, null)
            dispose()
        }
    }
}

/**
 * «Скан» и «Скан с цветом» на компьютере (#1379, #1333) — тот же исполнитель, что у
 * телефона (`PageScanRealizer`): сетка строк, не вышло — четыре угла листа, не нашлось —
 * кадр целиком, и тогда результат помечен, а не выдан за выпрямленную страницу.
 */
class PcPageScanRealizer(
    override val capabilityId: CapabilityId,
    private val op: String,
    private val store: ObjectStore,
    private val ready: () -> Boolean = { PcOpenCv.available },
) : Realizer {

    override val meta = RealizerMeta(priority = 20)

    override fun isAvailable(): Boolean = ready()

    override fun unavailableReason(): String = tr("нужен пакет обработки снимков")

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage(tr("Читаю снимок"))
                val picture = uprightPicture(File(input.uri.value))
                    ?: return@runCatching ActionResult.Failure(tr("Не удалось прочитать изображение"), recoverable = false)

                var wholeFrame = false
                val page = onFrame(picture) { rgba, scratch ->
                    PageVision.straightened(rgba, scratch)?.let { imageOf(it.mat) } ?: run {
                        reportStage(tr("Страницу целиком не нашёл — обрабатываю снимок как есть"))
                        wholeFrame = true
                        imageOf(PageVision.enhanceAsIs(rgba, scratch))
                    }
                }

                reportStage(tr("Сохраняю"))
                val ref = store.newScratchFile("jpg")
                writeJpeg(page, File(ref.value))
                ActionResult.Success(
                    ResultObject(
                        ObjectKind.IMAGE,
                        "image/jpeg",
                        ref,
                        mapOf("op" to op) + listOfNotNull(
                            wholeFrameNote(wholeFrames = if (wholeFrame) 1 else 0, pages = 1)?.let { META_WHOLE_FRAME to it },
                        ),
                    ),
                )
            }.getOrElse { ActionResult.Failure(SCAN_FAILED, recoverable = true) }
        }

    private companion object {
        val SCAN_FAILED: String get() = tr("Страницу на снимке не удалось выпрямить — снимите её целиком и при ровном свете")
    }
}

/**
 * «Сканировать в PDF» на компьютере (#1379): каждый снимок набора — выпрямленной чёрно-белой
 * страницей, а лист и сжатие те же, что у «Объединить в PDF». Страницы, на которых лист не
 * нашли, сводятся к чёрно-белому целиком и названы на результате (#1333).
 */
class PcScanPdfRealizer(
    private val store: ObjectStore,
    private val children: (PointObject) -> List<String> = ::collectionChildren,
    private val ready: () -> Boolean = { PcOpenCv.available },
) : Realizer {
    override val capabilityId = com.point.core.flow.ScanPdfCapability.ID

    override fun isAvailable(): Boolean = ready()

    override fun unavailableReason(): String = tr("нужен пакет обработки снимков")

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                picturesToPdf(store, input, children, op = "scan-pdf", name = tr("скан.pdf")) { file ->
                    val picture = uprightPicture(file) ?: return@picturesToPdf null
                    onFrame(picture) { rgba, scratch ->
                        PageVision.process(rgba, scratch)?.let { PdfPicture(imageOf(it), wholeFrame = false) }
                            ?: PdfPicture(imageOf(PageVision.processAsIs(rgba, scratch)), wholeFrame = true)
                    }
                }
            }.getOrElse { ActionResult.Failure(tr("Собрать скан в PDF не вышло — попробуйте ещё раз"), recoverable = true) }
        }
}

internal fun writeJpeg(image: BufferedImage, target: File, quality: Float = 0.92f) {
    val writer = ImageIO.getImageWritersByFormatName("jpg").next()
    val params = writer.defaultWriteParam.apply {
        compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
        compressionQuality = quality
    }
    ImageIO.createImageOutputStream(target).use { out ->
        writer.output = out
        writer.write(null, javax.imageio.IIOImage(image, null, null), params)
    }
    writer.dispose()
}

/** Тот же предел, что у телефона (`Bitmaps.SCAN_PLUS_MAX_PX`). */
private const val SCAN_MAX_PX = 2600
