package com.point.desktop

import com.point.core.flow.BackgroundRemover
import com.point.core.flow.ImageCompositor
import com.point.core.flow.ObjectStore
import com.point.core.flow.OwnWords
import com.point.core.flow.READER_NOT_DECODED
import com.point.core.flow.Realizer
import com.point.core.flow.ownWords
import com.point.core.flow.readerFailure
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.model.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Отделить объект от фона на компьютере (#1379): «Убрать фон», «Размыть фон» и «Заменить фон».
 *
 * Телефон отделяет моделью ML Kit, которой на компьютере нет. Здесь — малая U²-Net
 * (`point/models/u2netp.onnx`, Apache-2.0), исполняемая той же OpenCV, что выпрямляет
 * страницы: снимок не покидает компьютер, а модель весит меньше пяти мегабайт.
 *
 * Слова отказа те же, что у телефона: объекта не нашли, фона почти нет, снимок не прочитан.
 * Чужой текст сбоя наружу не выходит (#992) — тогда действие называет себя само.
 */
class PcBackgroundRemover(
    private val store: ObjectStore,
    private val model: () -> ByteArray = { PcBackgroundRemover::class.java.getResource(MODEL)!!.readBytes() },
) : BackgroundRemover {

    private val net: Net by lazy { Dnn.readNetFromONNX(MatOfByte(*model())) }

    override suspend fun cutout(imagePath: String): ScratchRef = withContext(Dispatchers.IO) {
        try {
            segment(imagePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OwnWords) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException(null as String?, e)
        }
    }

    private suspend fun segment(imagePath: String): ScratchRef {
        val picture = uprightPicture(File(imagePath), MAX_PX)
            ?: ownWords(readerFailure(READER_NOT_DECODED, ObjectKind.IMAGE))
        val mask = maskOf(picture)
        val opaque = mask.count { it >= HALF }.toDouble() / mask.size

        if (opaque < MIN_OPAQUE_RATIO) ownWords(tr("Объект на фото не найден"))
        if (opaque > MAX_OPAQUE_RATIO) ownWords(tr("На фото почти нет фона — объект занимает весь кадр"))

        val ref = store.newScratchFile("png")
        ImageIO.write(withAlpha(picture, mask), "png", File(ref.value))
        return ref
    }

    /** Доля «объекта» в каждой точке снимка, 0..255, в размер снимка. */
    private fun maskOf(picture: BufferedImage): IntArray {
        val small = scaled(picture, SIDE, SIDE)
        val rgb = small.getRGB(0, 0, SIDE, SIDE, null, 0, SIDE)
        val input = FloatArray(SIDE * SIDE * 3)
        for (i in rgb.indices) {
            for (c in 0 until 3) {
                val value = (rgb[i] shr (16 - 8 * c) and 0xFF) / 255f
                input[i * 3 + c] = (value - MEAN[c]) / STD[c]
            }
        }

        val frame = Mat(SIDE, SIDE, CvType.CV_32FC3).apply { put(0, 0, input) }
        val blob = Dnn.blobFromImage(frame)
        val raw = FloatArray(SIDE * SIDE)
        val output = synchronized(net) {
            net.setInput(blob)
            net.forward()
        }
        output.reshape(1, 1).get(0, 0, raw)
        listOf(frame, blob, output).forEach(Mat::release)

        // Модель отдаёт не вероятность, а яркость; к полной шкале её приводят так же, как
        // rembg. Но тусклая карта целиком значит «объекта нет», и растягивать шум до объекта
        // нельзя.
        val low = raw.min()
        val high = raw.max()
        if (high < HALF / 255f || high - low < 1e-6f) return IntArray(picture.width * picture.height)

        val small8 = Mat(SIDE, SIDE, CvType.CV_8UC1).apply {
            put(0, 0, ByteArray(raw.size) { (((raw[it] - low) / (high - low)) * 255f).toInt().coerceIn(0, 255).toByte() })
        }
        val big = Mat()
        Imgproc.resize(small8, big, Size(picture.width.toDouble(), picture.height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val bytes = ByteArray(picture.width * picture.height)
        big.get(0, 0, bytes)
        listOf(small8, big).forEach(Mat::release)
        return IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
    }

    private fun withAlpha(picture: BufferedImage, mask: IntArray): BufferedImage {
        val width = picture.width
        val height = picture.height
        val pixels = picture.getRGB(0, 0, width, height, null, 0, width)
        for (i in pixels.indices) pixels[i] = (mask[i] shl 24) or (pixels[i] and 0xFFFFFF)
        return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, width, height, pixels, 0, width) }
    }

    private companion object {
        const val MODEL = "/point/models/u2netp.onnx"

        /** Сторона, на которой модель учили. */
        const val SIDE = 320

        /** Тот же предел, что у телефона (`MlKitBackgroundRemover`). */
        const val MAX_PX = 2048
        const val HALF = 128
        const val MIN_OPAQUE_RATIO = 0.01
        const val MAX_OPAQUE_RATIO = 0.96
        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}

/**
 * Сборка снимка на компьютере — то же правило, что у телефона (`AndroidImageCompositor`): фон
 * заполняет кадр объекта по центру, объект ложится поверх; размытие — уменьшить в 16 раз и
 * растянуть обратно.
 */
class PcImageCompositor(private val store: ObjectStore) : ImageCompositor {

    override suspend fun composite(subjectPath: String, backgroundPath: String): ScratchRef =
        withContext(Dispatchers.IO) {
            val subject = runCatching { ImageIO.read(File(subjectPath)) }.getOrNull() ?: error(tr("Не удалось прочитать объект"))
            val background = uprightPicture(File(backgroundPath), MAX_PX) ?: error(tr("Не удалось прочитать фон"))
            val out = BufferedImage(subject.width, subject.height, BufferedImage.TYPE_INT_RGB)
            out.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                val scale = maxOf(subject.width.toDouble() / background.width, subject.height.toDouble() / background.height)
                val width = (background.width * scale).toInt()
                val height = (background.height * scale).toInt()
                drawImage(background, (subject.width - width) / 2, (subject.height - height) / 2, width, height, null)
                drawImage(subject, 0, 0, null)
                dispose()
            }
            png(out)
        }

    override suspend fun blur(imagePath: String): ScratchRef = withContext(Dispatchers.IO) {
        val source = uprightPicture(File(imagePath), MAX_PX) ?: error(tr("Не удалось прочитать изображение"))

        // Уменьшение — усреднением, а не выборкой точек: иначе вместо размытия выходит рябь.
        val small = source.getScaledInstance(
            maxOf(1, source.width / DOWNSCALE),
            maxOf(1, source.height / DOWNSCALE),
            java.awt.Image.SCALE_AREA_AVERAGING,
        )
        val tiny = BufferedImage(maxOf(1, source.width / DOWNSCALE), maxOf(1, source.height / DOWNSCALE), BufferedImage.TYPE_INT_RGB)
        tiny.createGraphics().apply { drawImage(small, 0, 0, null); dispose() }
        png(scaled(tiny, source.width, source.height))
    }

    private suspend fun png(image: BufferedImage): ScratchRef {
        val ref = store.newScratchFile("png")
        ImageIO.write(image, "png", File(ref.value))
        return ref
    }

    private companion object {
        const val MAX_PX = 2048
        const val DOWNSCALE = 16
    }
}

/**
 * Действие, которому нужна картинка от человека (`NeedsImage`, «Заменить фон»): на компьютере
 * её выбирают окном файлов, и действие продолжается тем же заходом. Не выбрали — шаг не
 * провален, а отменён человеком, и так и назван.
 */
class PcPicksImage(
    private val inner: Realizer,
    private val pick: suspend (prompt: String) -> String?,
) : Realizer by inner {

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        val first = inner.perform(input, amendment)
        if (first !is ActionResult.NeedsImage) return first
        val chosen = pick(first.prompt)
            ?: return ActionResult.Failure(tr("Картинка не выбрана — без неё действию не с чем работать"), recoverable = true)
        return inner.perform(input, chosen)
    }
}

/** Отделение фона без OpenCV невозможно: тогда действие честно недоступно, как скан. */
class PcNeedsOpenCv(
    private val inner: Realizer,
    private val ready: () -> Boolean = { PcOpenCv.available },
) : Realizer by inner {
    override fun isAvailable(): Boolean = ready() && inner.isAvailable()
    override fun unavailableReason(): String? = if (ready()) inner.unavailableReason() else tr("нужен пакет обработки снимков")
}

private fun scaled(source: BufferedImage, width: Int, height: Int): BufferedImage =
    BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(source, 0, 0, width, height, null)
            dispose()
        }
    }

/** Картинка с диска для действия, которое её ждёт: окно файлов, как у «Выбрать файл». */
internal fun pickPicture(prompt: String): String? {
    var chosen: String? = null
    val ask = Runnable {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, prompt, java.awt.FileDialog.LOAD)
        dialog.file = "*.jpg;*.jpeg;*.png;*.webp;*.bmp"
        dialog.isVisible = true
        chosen = dialog.files.orEmpty().firstOrNull()?.absolutePath
    }
    if (javax.swing.SwingUtilities.isEventDispatchThread()) ask.run() else javax.swing.SwingUtilities.invokeAndWait(ask)
    return chosen
}
