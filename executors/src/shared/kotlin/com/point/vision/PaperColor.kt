package com.point.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Лист и светлый стол того же тона — разного цвета (#1044, кадр 19 корпуса).
 *
 * По яркости светлое дерево над страницей неотличимо от бумаги, и пятно «светлого» берёт
 * их вместе: страница выходила вместе с куском стола. По цвету они разные: тон бумаги внутри
 * листа почти один (на кадрах корпуса у 90% её точек расхождение не больше 5 единиц a/b в
 * Lab), а дерево отстоит на 12–13.
 *
 * Отрезается только чужой цвет, входящий в пятно с края кадра: стол лежит вокруг листа. Цветное
 * внутри листа — печать, шапка, рисунок — края кадра не касается и остаётся страницей.
 */
internal object PaperColor {

    /**
     * Маска [light] без светлых частей чужого цвета, касающихся края кадра, — или `null`, если
     * таких нет и маска осталась бы той же. [rgba] — тот же кадр, что у маски, того же размера.
     */
    fun withoutForeignEdges(light: Mat, rgba: Mat, scratch: MutableList<Mat>): Mat? {
        val labels = Mat().also { scratch += it }
        val stats = Mat().also { scratch += it }
        val count = Imgproc.connectedComponentsWithStats(light, labels, stats, Mat().also { scratch += it })
        if (count <= 1) return null
        var biggest = 1
        for (i in 2 until count) {
            if (stats.get(i, Imgproc.CC_STAT_AREA)[0] > stats.get(biggest, Imgproc.CC_STAT_AREA)[0]) biggest = i
        }
        val paper = Mat().also { scratch += it }
        Core.compare(labels, Scalar(biggest.toDouble()), paper, Core.CMP_EQ)

        // Тон бумаги — медиана a и b по самому большому светлому пятну.
        val bgr = Mat().also { scratch += it }
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        Imgproc.GaussianBlur(bgr, bgr, Size(7.0, 7.0), 0.0)
        val lab = Mat().also { scratch += it }
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
        val channels = ArrayList<Mat>().also { Core.split(lab, it) }.onEach { scratch += it }
        val a0 = median(channels[1], paper)
        val b0 = median(channels[2], paper)

        // Насколько каждая точка отстоит от тона бумаги.
        val da = Mat().also { scratch += it }
        val db = Mat().also { scratch += it }
        channels[1].convertTo(da, CvType.CV_32F, 1.0, -a0)
        channels[2].convertTo(db, CvType.CV_32F, 1.0, -b0)
        val distance = Mat().also { scratch += it }
        Core.magnitude(da, db, distance)
        val foreign = Mat().also { scratch += it }
        Imgproc.threshold(distance, foreign, FOREIGN_TONE, 255.0, Imgproc.THRESH_BINARY)
        foreign.convertTo(foreign, CvType.CV_8U)
        Core.bitwise_and(foreign, light, foreign)
        // Отдельные точки чужого тона — шум и края букв, а не стол.
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)).also { scratch += it }
        Imgproc.morphologyEx(foreign, foreign, Imgproc.MORPH_OPEN, kernel)

        val parts = Mat().also { scratch += it }
        val partStats = Mat().also { scratch += it }
        val partCount = Imgproc.connectedComponentsWithStats(foreign, parts, partStats, Mat().also { scratch += it })
        val w = light.cols()
        val h = light.rows()
        val minArea = MIN_FOREIGN_FRACTION * w * h
        val cut = Mat.zeros(light.size(), CvType.CV_8U).also { scratch += it }
        var any = false
        var cutArea = 0.0
        for (i in 1 until partCount) {
            val area = partStats.get(i, Imgproc.CC_STAT_AREA)[0]
            if (area < minArea) continue
            val x = partStats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
            val y = partStats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
            val pw = partStats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
            val ph = partStats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
            val touchesEdge = x <= 0 || y <= 0 || x + pw >= w || y + ph >= h
            if (!touchesEdge) continue
            val part = Mat().also { scratch += it }
            Core.compare(parts, Scalar(i.toDouble()), part, Core.CMP_EQ)
            Core.bitwise_or(cut, part, cut)
            cutArea += area
            any = true
        }
        if (!any) return null
        // Стол, слившийся с листом, — край пятна, а не его половина. Когда «чужого» больше, то
        // светлое пятно — не лист на столе: на снимке экрана так отрезалась карта, и «листом»
        // становилась панель интерфейса под ней (кадр 14 корпуса).
        if (cutArea > MAX_FOREIGN_SHARE * stats.get(biggest, Imgproc.CC_STAT_AREA)[0]) return null

        val trimmed = Mat().also { scratch += it }
        Core.subtract(light, cut, trimmed)
        Imgproc.morphologyEx(trimmed, trimmed, Imgproc.MORPH_OPEN, kernel)
        return trimmed
    }

    private fun median(channel: Mat, mask: Mat): Double {
        val hist = Mat()
        try {
            Imgproc.calcHist(listOf(channel), MatOfInt(0), mask, hist, MatOfInt(256), MatOfFloat(0f, 256f))
            val total = Core.sumElems(hist).`val`[0]
            var seen = 0.0
            for (i in 0 until 256) {
                seen += hist.get(i, 0)[0]
                if (seen >= total / 2) return i.toDouble()
            }
            return 128.0
        } finally {
            hist.release()
        }
    }

    /**
     * С какого расхождения с тоном бумаги (a/b в Lab) светлое — уже не бумага. Внутри листа
     * корпуса расхождение почти всегда меньше 6, светлое дерево отстоит на 12–13.
     */
    private const val FOREIGN_TONE = 8.0

    /** Чужое пятно меньше этой доли кадра столом не считается. */
    private const val MIN_FOREIGN_FRACTION = 0.01

    /** Какую долю светлого пятна может занимать слившийся с ним стол. */
    private const val MAX_FOREIGN_SHARE = 0.25
}
