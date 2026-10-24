package com.point.vision

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import kotlin.math.exp

/**
 * Обученный искатель углов страницы (#1044) — второй ход, когда свой не нашёл лист.
 *
 * Свой ход ([PageVision]) ищет лист по контрасту бумаги и краёв и сдаётся на пёстром фоне,
 * на листе без полей и там, где бумага сливается со столом. Здесь — готовая модель
 * DocCornerNet LEAN (`point/models/doccornernet_lean.onnx`, MIT, 1,8 МБ): она отвечает сразу
 * четырьмя углами и тем, есть ли на кадре документ вообще. Своё не обучаем — решение
 * владельца 15.08.2026: где есть готовое, берём готовое.
 *
 * Исполняет её та же OpenCV, что выпрямляет страницу: на телефоне и на компьютере, снимок
 * никуда не уходит.
 *
 * Модель отвечает не всегда правдой, поэтому её слово проверяется так же строго, как своё:
 * документа нет по её же оценке, лист меньше обычного или углы не складываются в выпуклый
 * четырёхугольник — значит, страницы не нашлось, и это ответ, а не догадка.
 */
internal object LearnedCorners {

    private val net: Net? by lazy {
        runCatching {
            val bytes = LearnedCorners::class.java.getResourceAsStream(MODEL)?.use { it.readBytes() }
                ?: return@runCatching null
            Dnn.readNetFromONNX(MatOfByte(*bytes))
        }.getOrNull()
    }

    /** Четыре угла листа в координатах [rgba] (верх-лево, верх-право, низ-право, низ-лево) или `null`. */
    fun find(rgba: Mat, scratch: MutableList<Mat>): Array<Point>? {
        val model = net ?: return null
        val (coords, score) = runCatching { synchronized(model) { infer(model, rgba, scratch) } }.getOrNull()
            ?: return null
        if (score < MIN_SCORE) return null

        val w = rgba.cols().toDouble()
        val h = rgba.rows().toDouble()
        val corners = Array(4) { i ->
            Point(coords[2 * i].toDouble().coerceIn(0.0, 1.0) * w, coords[2 * i + 1].toDouble().coerceIn(0.0, 1.0) * h)
        }
        return corners.takeIf { plausible(it, w * h) }
    }

    private fun infer(model: Net, rgba: Mat, scratch: MutableList<Mat>): Pair<FloatArray, Double> {
        val rgb = Mat().also { scratch += it }
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val small = Mat().also { scratch += it }
        Imgproc.resize(rgb, small, Size(SIDE.toDouble(), SIDE.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val float = Mat().also { scratch += it }
        small.convertTo(float, CvType.CV_32FC3, 1.0 / 255)

        val pixels = FloatArray(SIDE * SIDE * 3)
        float.get(0, 0, pixels)
        for (i in pixels.indices) pixels[i] = (pixels[i] - MEAN[i % 3]) / STD[i % 3]

        // Вход модели — NHWC, как её учили: [1, 224, 224, 3].
        val blob = Mat(intArrayOf(1, SIDE, SIDE, 3), CvType.CV_32F).also { scratch += it }
        blob.put(intArrayOf(0, 0, 0, 0), pixels)
        model.setInput(blob, INPUT)
        val outs = ArrayList<Mat>()
        // Имена выходов уходят в нативный код как есть, и он зовёт у списка методы
        // java.util.ArrayList: неизменяемый `listOf` ронял телефон на кадре 18 корпуса
        // (CheckJNI, SIGABRT), в release поведение не определено.
        model.forward(outs, arrayListOf(COORDS, SCORE))
        scratch += outs

        val coords = FloatArray(8)
        outs[0].reshape(1, 1).get(0, 0, coords)
        val logit = FloatArray(1)
        outs[1].reshape(1, 1).get(0, 0, logit)
        return coords to 1.0 / (1.0 + exp(-logit[0].toDouble()))
    }

    private fun plausible(corners: Array<Point>, frameArea: Double): Boolean {
        val quad = MatOfPoint(*corners.map { Point(Math.round(it.x).toDouble(), Math.round(it.y).toDouble()) }.toTypedArray())
        try {
            return Imgproc.contourArea(quad) >= MIN_PAGE_FRACTION * frameArea && Imgproc.isContourConvex(quad)
        } finally {
            quad.release()
        }
    }

    private const val MODEL = "/point/models/doccornernet_lean.onnx"
    private const val INPUT = "input"
    private const val COORDS = "coords"
    private const val SCORE = "score_logit"

    /** Сторона, на которой модель учили. */
    private const val SIDE = 224

    /** Ниже — модель сама не уверена, что на кадре документ. */
    private const val MIN_SCORE = 0.5

    /** Тот же нижний предел листа, что у своего хода. */
    private const val MIN_PAGE_FRACTION = 0.12

    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
}
