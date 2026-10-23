package com.point.vision

import com.point.core.flow.reportStage
import com.point.core.model.tr
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * «Улучшить фото» — один код OpenCV на телефон и компьютер (#433).
 *
 * Главное правило — не испортить хороший снимок. Каждый шаг осторожен и меряет, нужен ли он:
 * цвет правится, только когда две независимые оценки сходятся в том, какого канала не
 * хватает; шум снимается, только когда он есть на ровных местах, а не в фактуре; увеличивается
 * только мелкий снимок. Хороший снимок выходит почти таким же, каким пришёл, — а исходник
 * остаётся у человека всё равно.
 *
 * Кадр приходит RGBA-матрицей и уходит ею же; созданное по дороге ложится в `scratch`.
 */
object PhotoVision {

    suspend fun enhance(rgba: Mat, scratch: MutableList<Mat>): Mat {
        val rgb = Mat().also { scratch += it }
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)

        reportStage(tr("Выравниваю свет и цвет"))
        var out = light(balanced(rgb, scratch), scratch)

        if (noiseSigma(out, scratch) > NOISY) {
            reportStage(tr("Убираю шум"))
            out = denoised(out, scratch)
        }
        if (maxOf(out.rows(), out.cols()) < UPSCALE_BELOW) {
            reportStage(tr("Увеличиваю"))
            out = LearnedUpscale.twice(out, scratch) ?: out
        }
        out = sharpened(out, scratch)

        val done = Mat().also { scratch += it }
        Imgproc.cvtColor(out, done, Imgproc.COLOR_RGB2RGBA)
        return done
    }

    /**
     * Баланс белого (серый мир и серые края вместе). Каждая оценка по отдельности путает
     * цвет сцены с цветом света: тарелка апельсинов для «серого мира» — синий свет. Поэтому
     * канал правится, только когда обе оценки согласны в направлении, и не больше чем на 8 %.
     */
    private fun balanced(rgb: Mat, scratch: MutableList<Mat>): Mat {
        val smooth = Mat().also { scratch += it }
        Imgproc.GaussianBlur(rgb, smooth, Size(0.0, 0.0), 1.5)

        // Пересвеченное правды о свете не знает.
        val gray = Mat().also { scratch += it }
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val mask = Mat().also { scratch += it }
        Imgproc.threshold(gray, mask, 244.0, 255.0, Imgproc.THRESH_BINARY_INV)

        val channels = ArrayList<Mat>().also { Core.split(smooth, it); scratch += it }
        val edges = DoubleArray(3)
        val gx = Mat().also { scratch += it }
        val gy = Mat().also { scratch += it }
        val magnitude = Mat().also { scratch += it }
        for (c in 0 until 3) {
            Imgproc.Sobel(channels[c], gx, CvType.CV_32F, 1, 0)
            Imgproc.Sobel(channels[c], gy, CvType.CV_32F, 0, 1)
            Core.magnitude(gx, gy, magnitude)
            edges[c] = Core.mean(magnitude, mask).`val`[0]
        }
        val means = Core.mean(rgb, mask).`val`
        val gains = whiteBalanceGains(edges, doubleArrayOf(means[0], means[1], means[2]))
        if (gains.all { it == 1.0 }) return rgb

        // Восьмибитная матрица на скаляр — с насыщением, без копии снимка во float.
        val out = Mat().also { scratch += it }
        Core.multiply(rgb, Scalar(gains[0], gains[1], gains[2]), out)
        return out
    }

    /**
     * Свет: растянуть яркость до краёв, тёмный снимок поднять гаммой, местный контраст —
     * мягким CLAHE. Цветность поднимается вместе с яркостью, иначе посветлевший снимок
     * выглядит выцветшим.
     */
    private fun light(rgb: Mat, scratch: MutableList<Mat>): Mat {
        val lab = Mat().also { scratch += it }
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val parts = ArrayList<Mat>().also { Core.split(lab, it); scratch += it }
        val l = parts[0]

        val histogram = histogramOf(l)
        val lo = min(percentile(histogram, 0.005), 50.0)
        val hi = percentile(histogram, 0.995)
        val scale = min(255.0 / maxOf(hi - lo, 1.0), 1.8)

        val lut = Mat(1, 256, CvType.CV_8U).also { scratch += it }
        val stretched = DoubleArray(256) { v -> ((v - lo) * scale).coerceIn(0.0, 255.0) }
        val median = percentileAfter(histogram, stretched, 0.5)
        val gamma = if (median < DARK_MEDIAN) {
            (ln(TARGET_MEDIAN / 255.0) / ln(maxOf(median, 1.0) / 255.0)).coerceIn(MIN_GAMMA, 1.0)
        } else {
            1.0
        }
        lut.put(0, 0, ByteArray(256) { v -> (255.0 * (stretched[v] / 255.0).pow(gamma)).toInt().coerceIn(0, 255).toByte() })
        val lifted = Mat().also { scratch += it }
        Core.LUT(l, lut, lifted)

        val local = Mat().also { scratch += it }
        Imgproc.createCLAHE(1.6, Size(8.0, 8.0)).apply(lifted, local)
        val newL = Mat().also { scratch += it }
        Core.addWeighted(lifted, 0.6, local, 0.4, 0.0, newL)

        // Во сколько раз посветлело — во столько (мягче) крепнет цвет; темнеть он не должен.
        val l0 = Mat().also { scratch += it }
        l.convertTo(l0, CvType.CV_32F)
        Core.max(l0, Scalar(1.0), l0)
        val ratio = Mat().also { scratch += it }
        newL.convertTo(ratio, CvType.CV_32F)
        Core.divide(ratio, l0, ratio)
        Core.min(ratio, Scalar(1.6), ratio)
        Core.max(ratio, Scalar(1.0), ratio)
        Core.pow(ratio, 0.6, ratio)
        for (c in 1..2) {
            val chroma = Mat().also { scratch += it }
            parts[c].convertTo(chroma, CvType.CV_32F)
            Core.subtract(chroma, Scalar(128.0), chroma)
            Core.multiply(chroma, ratio, chroma)
            Core.add(chroma, Scalar(128.0), chroma)
            chroma.convertTo(parts[c], CvType.CV_8U)
        }
        parts[0] = newL

        val merged = Mat().also { scratch += it }
        Core.merge(parts, merged)
        val out = Mat().also { scratch += it }
        Imgproc.cvtColor(merged, out, Imgproc.COLOR_Lab2RGB)
        return out
    }

    /**
     * Шум на ровных местах (оценка Иммеркера по блокам). Берётся нижний дециль блоков: в
     * фактуре — шерсти, листве, ткани — разброс есть и без шума, а ровное небо или стена
     * показывают сам шум.
     */
    private fun noiseSigma(rgb: Mat, scratch: MutableList<Mat>): Double {
        val gray = Mat().also { scratch += it }
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val float = Mat().also { scratch += it }
        gray.convertTo(float, CvType.CV_32F)
        val kernel = Mat(3, 3, CvType.CV_32F).also { scratch += it }
        kernel.put(0, 0, floatArrayOf(1f, -2f, 1f, -2f, 4f, -2f, 1f, -2f, 1f))
        val response = Mat().also { scratch += it }
        Imgproc.filter2D(float, response, CvType.CV_32F, kernel)
        Core.absdiff(response, Scalar(0.0), response)

        val cols = response.cols() / NOISE_BLOCK
        val rows = response.rows() / NOISE_BLOCK
        if (cols < 2 || rows < 2) return 0.0
        val blocks = Mat().also { scratch += it }
        Imgproc.resize(response.submat(0, rows * NOISE_BLOCK, 0, cols * NOISE_BLOCK), blocks, Size(cols.toDouble(), rows.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val values = FloatArray(cols * rows)
        blocks.get(0, 0, values)
        values.sort()
        return sqrt(Math.PI / 2) * values[values.size / 10] / 6.0
    }

    private fun denoised(rgb: Mat, scratch: MutableList<Mat>): Mat {
        val strength = min(7.0, noiseSigma(rgb, scratch) * 0.8).toFloat()
        val out = Mat().also { scratch += it }
        Photo.fastNlMeansDenoisingColored(rgb, out, strength, strength, 7, 21)
        return out
    }

    /** Мягкая нерезкая маска по яркости: цвет не звенит, а края становятся чище. */
    private fun sharpened(rgb: Mat, scratch: MutableList<Mat>): Mat {
        val lab = Mat().also { scratch += it }
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val parts = ArrayList<Mat>().also { Core.split(lab, it); scratch += it }
        val blurred = Mat().also { scratch += it }
        val sigma = maxOf(1.0, maxOf(rgb.rows(), rgb.cols()) / 1500.0)
        Imgproc.GaussianBlur(parts[0], blurred, Size(0.0, 0.0), sigma)
        val sharp = Mat().also { scratch += it }
        Core.addWeighted(parts[0], 1.0 + SHARPEN, blurred, -SHARPEN, 0.0, sharp)
        parts[0] = sharp
        val merged = Mat().also { scratch += it }
        Core.merge(parts, merged)
        val out = Mat().also { scratch += it }
        Imgproc.cvtColor(merged, out, Imgproc.COLOR_Lab2RGB)
        return out
    }

    private fun histogramOf(gray8: Mat): LongArray {
        val bytes = ByteArray(gray8.rows() * gray8.cols())
        gray8.get(0, 0, bytes)
        val histogram = LongArray(256)
        for (b in bytes) histogram[b.toInt() and 0xFF]++
        return histogram
    }

    private fun percentile(histogram: LongArray, p: Double): Double =
        percentileAfter(histogram, DoubleArray(256) { it.toDouble() }, p)

    /** Процентиль значений гистограммы, пропущенных через монотонную таблицу [mapped]. */
    private fun percentileAfter(histogram: LongArray, mapped: DoubleArray, p: Double): Double {
        val target = histogram.sum() * p
        var seen = 0L
        for (v in 0 until 256) {
            seen += histogram[v]
            if (seen >= target) return mapped[v]
        }
        return mapped[255]
    }

    /** Ниже этого шум на ровных местах глазу не мешает, а снятие съело бы детали. */
    private const val NOISY = 6.0

    /** Мельче этого по длинной стороне снимок увеличивается вдвое. */
    internal const val UPSCALE_BELOW = 1280

    private const val DARK_MEDIAN = 110.0
    private const val TARGET_MEDIAN = 115.0
    private const val MIN_GAMMA = 0.6
    private const val NOISE_BLOCK = 16
    private const val SHARPEN = 0.45
}

/**
 * Поправка каналов по двум оценкам света: [edges] — средняя сила краёв канала (серые края),
 * [means] — средняя яркость канала (серый мир). Канал правится, только когда обе оценки
 * тянут его в одну сторону, и на меньшую из двух поправок, не больше [MAX_WB_SHIFT].
 */
internal fun whiteBalanceGains(edges: DoubleArray, means: DoubleArray): DoubleArray {
    if (edges.any { it <= 0.0 } || means.any { it <= 0.0 }) return doubleArrayOf(1.0, 1.0, 1.0)
    val edgeMean = edges.average()
    val meanMean = means.average()
    return DoubleArray(3) { c ->
        val byEdges = edgeMean / edges[c] - 1
        val byMeans = meanMean / means[c] - 1
        if (sign(byEdges) != sign(byMeans)) {
            1.0
        } else {
            1 + (sign(byEdges) * min(abs(byEdges), abs(byMeans)) * WB_STRENGTH).coerceIn(-MAX_WB_SHIFT, MAX_WB_SHIFT)
        }
    }
}

private const val WB_STRENGTH = 0.8
private const val MAX_WB_SHIFT = 0.08

/**
 * Увеличение вдвое обученной сетью — FSRCNN ×2 (`point/models/fsrcnn_x2.onnx`, Apache-2.0,
 * 35 КБ). Сеть видит только яркость; цвет увеличивается обычной интерполяцией — глаз
 * читает резкость по яркости.
 *
 * Сеть отдаёт четыре канала на исходной сетке, а в картинку вдвое крупнее их раскладывает
 * [depthToSpace] здесь: эту перестановку импортёр OpenCV 4.9 без известного заранее размера
 * не собирает.
 *
 * Кадр идёт плитками: на целом снимке промежуточные слои сети заняли бы сотни мегабайт.
 */
internal object LearnedUpscale {

    private val net: Net? by lazy {
        runCatching {
            val bytes = LearnedUpscale::class.java.getResourceAsStream(MODEL)?.use { it.readBytes() }
                ?: return@runCatching null
            Dnn.readNetFromONNX(MatOfByte(*bytes))
        }.getOrNull()
    }

    /** Снимок вдвое крупнее или `null` — сети нет, и тогда снимок остаётся своего размера. */
    fun twice(rgb: Mat, scratch: MutableList<Mat>): Mat? {
        val model = net ?: return null
        val ycc = Mat().also { scratch += it }
        Imgproc.cvtColor(rgb, ycc, Imgproc.COLOR_RGB2YCrCb)
        val parts = ArrayList<Mat>().also { Core.split(ycc, it); scratch += it }
        val y = Mat().also { scratch += it }
        parts[0].convertTo(y, CvType.CV_32F, 1.0 / 255)

        val h = y.rows()
        val w = y.cols()
        val big = FloatArray(4 * h * w)
        synchronized(model) {
            for (top in 0 until h step TILE) for (left in 0 until w step TILE) {
                val a = maxOf(0, top - PAD)
                val b = maxOf(0, left - PAD)
                val tile = y.submat(Rect(b, a, min(w, left + TILE + PAD) - b, min(h, top + TILE + PAD) - a)).clone()
                    .also { scratch += it }
                model.setInput(Dnn.blobFromImage(tile).also { scratch += it })
                val out = model.forward().also { scratch += it }
                val th = tile.rows()
                val tw = tile.cols()
                val raw = FloatArray(4 * th * tw)
                out.get(intArrayOf(0, 0, 0, 0), raw)
                val shuffled = depthToSpace(raw, th, tw)
                val rows = min(TILE, h - top)
                val cols = min(TILE, w - left)
                for (r in 0 until 2 * rows) {
                    System.arraycopy(shuffled, (2 * (top - a) + r) * 2 * tw + 2 * (left - b), big, (2 * top + r) * 2 * w + 2 * left, 2 * cols)
                }
            }
        }

        val sharpY = Mat(2 * h, 2 * w, CvType.CV_32F).also { scratch += it }
        sharpY.put(0, 0, big)
        val sharpY8 = Mat().also { scratch += it }
        sharpY.convertTo(sharpY8, CvType.CV_8U, 255.0)

        val bigYcc = Mat().also { scratch += it }
        Imgproc.resize(ycc, bigYcc, Size(2.0 * w, 2.0 * h), 0.0, 0.0, Imgproc.INTER_CUBIC)
        val bigParts = ArrayList<Mat>().also { Core.split(bigYcc, it); scratch += it }
        bigParts[0] = sharpY8
        Core.merge(bigParts, bigYcc)
        val out = Mat().also { scratch += it }
        Imgproc.cvtColor(bigYcc, out, Imgproc.COLOR_YCrCb2RGB)
        return out
    }

    private const val MODEL = "/point/models/fsrcnn_x2.onnx"

    /** Сторона плитки и запас по краям: поле зрения сети — шесть точек в каждую сторону. */
    private const val TILE = 256
    private const val PAD = 8
}

/**
 * Четыре канала на сетке [h]×[w] (порядок NCHW) — в одну картинку 2h×2w: канал `2i + j`
 * ложится в точку `(2y + i, 2x + j)`. Так раскладывает DepthToSpace TensorFlow, на котором
 * сеть учили.
 */
internal fun depthToSpace(channels: FloatArray, h: Int, w: Int): FloatArray {
    val out = FloatArray(4 * h * w)
    val plane = h * w
    for (i in 0 until 2) for (j in 0 until 2) {
        val c = (2 * i + j) * plane
        for (y in 0 until h) {
            val row = (2 * y + i) * 2 * w
            for (x in 0 until w) out[row + 2 * x + j] = channels[c + y * w + x]
        }
    }
    return out
}
