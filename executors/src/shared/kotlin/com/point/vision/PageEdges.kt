package com.point.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

/**
 * Не режет ли найденный лист текст (#1044, замер 23.10.2026).
 *
 * На двух кадрах корпуса из восьми углы легли не на края бумаги, а внутрь неё: копия выходила
 * ровной, только без концов строк или без левого столбца — и молча. Неверные углы хуже
 * ненайденных: ненайденную страницу Point честно отдаёт кадром целиком.
 *
 * Признак — строки, которые продолжаются за краем. У настоящего края по одну сторону поле
 * листа, по другую стол; у ложного по обе стороны те же строки на той же высоте. Сравниваются
 * полоса внутри у края и полоса снаружи: чернила снаружи должны лежать там же, где внутри.
 * Пёстрый стол (древесина, ткань) чернила даёт, но не строками напротив строк листа.
 */
internal object PageEdges {

    /** Режет ли четырёхугольник [corners] (верх-лево, верх-право, низ-право, низ-лево) текст на [gray]. */
    fun cutsText(gray: Mat, corners: Array<Point>, scratch: MutableList<Mat>): Boolean {
        val (tl, tr, br, bl) = corners
        val pageW = maxOf(PageVision.distance(tl, tr), PageVision.distance(bl, br))
        val pageH = maxOf(PageVision.distance(tl, bl), PageVision.distance(tr, br))
        if (pageW < 1.0 || pageH < 1.0) return false
        val s = WORK_SIDE / maxOf(pageW, pageH)
        val w = pageW * s
        val h = pageH * s
        val m = MARGIN * maxOf(w, h)

        // Лист кладётся в середину холста с запасом вокруг: за краем видно, что было на снимке.
        val src = MatOfPoint2f(tl, tr, br, bl).also { scratch += it }
        val dst = MatOfPoint2f(Point(m, m), Point(m + w, m), Point(m + w, m + h), Point(m, m + h)).also { scratch += it }
        val transform = Imgproc.getPerspectiveTransform(src, dst).also { scratch += it }
        val canvas = Mat().also { scratch += it }
        Imgproc.warpPerspective(
            gray, canvas, transform, Size(w + 2 * m, h + 2 * m),
            Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE,
        )
        val ink = Mat().also { scratch += it }
        Imgproc.adaptiveThreshold(canvas, ink, 1.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 25, 15.0)

        // Где холст вышел за снимок, там не стол и не текст, а ничего (кадр 18 корпуса: угол
        // на самой кромке, и половина полосы снаружи — повтор кромки).
        val seen = Mat().also { scratch += it }
        Imgproc.warpPerspective(
            Mat.ones(gray.size(), CvType.CV_8U).also { scratch += it }, seen, transform, canvas.size(),
            Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT,
        )
        Core.multiply(ink, seen, ink)

        // Длинные линии — разлиновка, рамки, волокна стола — не текст: строки за краем
        // узнаются по буквам, а не по полосам.
        val run = (STRIP * maxOf(w, h)).coerceAtLeast(3.0)
        for (size in listOf(Size(run, 1.0), Size(1.0, run))) {
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, size).also { scratch += it }
            val lines = Mat().also { scratch += it }
            Imgproc.morphologyEx(ink, lines, Imgproc.MORPH_OPEN, kernel)
            Core.subtract(ink, lines, ink)
        }

        val gap = GAP * maxOf(w, h)
        val strip = STRIP * maxOf(w, h)
        val x0 = m.toInt()
        val y0 = m.toInt()
        val x1 = (m + w).toInt()
        val y1 = (m + h).toInt()
        val g = gap.toInt()
        val st = strip.toInt()

        // Слева и справа строки идут поперёк края — сравниваются профили по рядам; сверху
        // и снизу поперёк края идут столбцы текста — профили по столбцам.
        fun edge(inY: IntRange, inX: IntRange, outY: IntRange, outX: IntRange, byRows: Boolean) = Edge(
            inside = ink.submat(inY.first, inY.last, inX.first, inX.last),
            outside = ink.submat(outY.first, outY.last, outX.first, outX.last),
            outsideSeen = Core.countNonZero(seen.submat(outY.first, outY.last, outX.first, outX.last)),
            byRows = byRows,
        )
        return listOf(
            edge(y0..y1, x0 + g..x0 + g + st, y0..y1, x0 - g - st..x0 - g, byRows = true),
            edge(y0..y1, x1 - g - st..x1 - g, y0..y1, x1 + g..x1 + g + st, byRows = true),
            edge(y0 + g..y0 + g + st, x0..x1, y0 - g - st..y0 - g, x0..x1, byRows = false),
            edge(y1 - g - st..y1 - g, x0..x1, y1 + g..y1 + g + st, x0..x1, byRows = false),
        ).any { it.cuts() }
    }

    /** Что за краями найденного четырёхугольника. */
    enum class Beyond {
        /** Ни одного края не видно — все у кромки кадра: листа ничто не показало, это кадр целиком. */
        UNSEEN,

        /** За краем на той же бумаге остался текст: это рамка на листе или кусок страницы, а не лист. */
        TEXT_LEFT_OUT,

        /** Края видны, и на той же бумаге за ними текста нет: обрезка ничего не теряет. */
        CLEAR,
    }

    /**
     * Не оставляет ли четырёхугольник за краем текст на той же бумаге (#1044, кадр 04 корпуса).
     *
     * Лист, заполнивший кадр, края на снимке не имеет, и самым крупным четырёхугольником
     * становится рамка таблицы на нём — или модель углов обводит блок текста. Текста такой край
     * не режет, а шапка и подпись за ним терялись. Вред здесь один — потерянный текст, и
     * проверяется ровно он: на бумаге того же тона за краем (стол другого тона, гравий,
     * обложка — не в счёт) не должно остаться букв. Пустые поля за рамкой грамоты срезать
     * можно, как и прежде.
     *
     * Прежде (проверка 28.11.2026) здесь сравнивались признаки рамки — яркость и тон по обе
     * стороны края и сплошная черта вдоль него, — и каждый ломался по-своему: тёмная кромка за
     * снимком читалась чертой, закрашенная шапка таблицы — другой бумагой, край по пустому полю
     * листа — настоящим краем.
     *
     * Края на кромке снимка не в счёт: что за ними, не видно. Если не видно ни одного,
     * четырёхугольник ничем не показал лист — это кадр целиком без полей (разлинованный лист
     * без краёв: модель углов обводила крайние линии сетки).
     *
     * [rgba] — тот же кадр, что [gray], в цвете: по нему сравнивается тон бумаги.
     */
    fun beyondOf(gray: Mat, rgba: Mat, corners: Array<Point>, scratch: MutableList<Mat>): Beyond {
        val (tl, tr, br, bl) = corners
        val pageW = maxOf(PageVision.distance(tl, tr), PageVision.distance(bl, br))
        val pageH = maxOf(PageVision.distance(tl, bl), PageVision.distance(tr, br))
        if (pageW < 1.0 || pageH < 1.0) return Beyond.UNSEEN
        val s = BEYOND_SIDE / maxOf(pageW, pageH)
        val w = pageW * s
        val h = pageH * s
        val m = BEYOND * maxOf(w, h)

        val src = MatOfPoint2f(tl, tr, br, bl).also { scratch += it }
        val dst = MatOfPoint2f(Point(m, m), Point(m + w, m), Point(m + w, m + h), Point(m, m + h)).also { scratch += it }
        val transform = Imgproc.getPerspectiveTransform(src, dst).also { scratch += it }
        val size = Size(w + 2 * m, h + 2 * m)
        val canvas = Mat().also { scratch += it }
        Imgproc.warpPerspective(gray, canvas, transform, size, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        val seen = Mat().also { scratch += it }
        Imgproc.warpPerspective(
            Mat.ones(gray.size(), CvType.CV_8U).also { scratch += it }, seen, transform, canvas.size(),
            Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT,
        )

        val g = (GAP * maxOf(w, h)).toInt().coerceAtLeast(1)
        val st = (STRIP * maxOf(w, h)).toInt().coerceAtLeast(1)
        val x0 = m.toInt()
        val y0 = m.toInt()
        val x1 = (m + w).toInt().coerceAtMost(canvas.cols() - g - 1)
        val y1 = (m + h).toInt().coerceAtMost(canvas.rows() - g - 1)

        // Виден ли хоть один край: узкая полоса сразу за ним — на снимке, а не за кромкой.
        val strips = listOf(
            Rect(x0 - g - st, y0, st, y1 - y0),
            Rect(x1 + g, y0, st, y1 - y0),
            Rect(x0, y0 - g - st, x1 - x0, st),
            Rect(x0, y1 + g, x1 - x0, st),
        )
        if (strips.none { Core.countNonZero(seen.submat(it)) >= MIN_EDGE_SEEN * it.area() }) return Beyond.UNSEEN

        // Бумага страницы — медиана яркости и тона внутри четырёхугольника: текста на листе
        // меньше, чем бумаги.
        val inside = Rect(x0 + g, y0 + g, (x1 - x0 - 2 * g).coerceAtLeast(1), (y1 - y0 - 2 * g).coerceAtLeast(1))
        val colour = Mat().also { scratch += it }
        Imgproc.warpPerspective(rgba, colour, transform, size, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        val bgr = Mat().also { scratch += it }
        Imgproc.cvtColor(colour, bgr, Imgproc.COLOR_RGBA2BGR)
        val lab = Mat().also { scratch += it }
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
        val tone = ArrayList<Mat>().also { Core.split(lab, it) }.onEach { scratch += it }
        val paperGray = median(canvas.submat(inside), null)
        val paperA = median(tone[1].submat(inside), null)
        val paperB = median(tone[2].submat(inside), null)

        // Где та же бумага: близко по яркости и по тону. Буквы на ней темнее бумаги — закрытием
        // они входят в бумагу, на которой напечатаны.
        val near = Mat().also { scratch += it }
        Core.inRange(canvas, Scalar(paperGray - PAPER_LEVELS), Scalar(paperGray + PAPER_LEVELS), near)
        val da = Mat().also { scratch += it }
        val db = Mat().also { scratch += it }
        tone[1].convertTo(da, CvType.CV_32F, 1.0, -paperA)
        tone[2].convertTo(db, CvType.CV_32F, 1.0, -paperB)
        val apart = Mat().also { scratch += it }
        Core.magnitude(da, db, apart)
        val sameTone = Mat().also { scratch += it }
        Imgproc.threshold(apart, sameTone, PaperColor.FOREIGN_TONE, 255.0, Imgproc.THRESH_BINARY_INV)
        sameTone.convertTo(sameTone, CvType.CV_8U)
        val paper = Mat().also { scratch += it }
        Core.bitwise_and(near, sameTone, paper)
        val letter = (LETTER * maxOf(w, h)).coerceAtLeast(3.0)
        Imgproc.morphologyEx(
            paper, paper, Imgproc.MORPH_CLOSE,
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(letter, letter)).also { scratch += it },
        )

        // Буквы: чернила на снимке без длинных линий — рамок, разлиновки, волокон.
        val ink = Mat().also { scratch += it }
        Imgproc.adaptiveThreshold(canvas, ink, 1.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 25, 15.0)
        Core.multiply(ink, seen, ink)
        val run = (STRIP * maxOf(w, h)).coerceAtLeast(3.0)
        for (kernelSize in listOf(Size(run, 1.0), Size(1.0, run))) {
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, kernelSize).also { scratch += it }
            val lines = Mat().also { scratch += it }
            Imgproc.morphologyEx(ink, lines, Imgproc.MORPH_OPEN, kernel)
            Core.subtract(ink, lines, ink)
        }

        // За краем — всё, что дальше зазора от четырёхугольника, на снимке и на той же бумаге.
        val beyond = Mat(canvas.size(), CvType.CV_8U, Scalar(1.0)).also { scratch += it }
        beyond.submat(Rect(x0 - g, y0 - g, x1 - x0 + 2 * g, y1 - y0 + 2 * g)).setTo(Scalar(0.0))
        Core.multiply(beyond, seen, beyond)
        Core.multiply(beyond, paper, beyond, 1.0 / 255.0)
        Core.multiply(ink, beyond, beyond)
        val outsideText = Core.countNonZero(beyond)
        val insideText = Core.countNonZero(ink.submat(inside))
        val lost = outsideText >= MIN_TEXT_BEYOND * inside.area() && outsideText >= TEXT_BEYOND_SHARE * insideText
        return if (lost) Beyond.TEXT_LEFT_OUT else Beyond.CLEAR
    }

    private fun median(m: Mat, mask: Mat?): Double {
        val hist = Mat()
        try {
            Imgproc.calcHist(listOf(m), MatOfInt(0), mask ?: Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f))
            val total = Core.sumElems(hist).`val`[0]
            if (total <= 0.0) return 0.0
            var seen = 0.0
            for (i in 0 until 256) {
                seen += hist.get(i, 0)[0]
                if (seen >= total / 2) return i.toDouble()
            }
            return 255.0
        } finally {
            hist.release()
        }
    }

    /**
     * Край режет текст, если за ним те же строки напротив строк — или просто не меньше
     * чернил, чем у края изнутри (кадры 18 и 21 корпуса: отрезанная шапка и начала строк
     * дерева лежат не строка в строку, но гуще, чем внутри). У настоящего края изнутри
     * поле, снаружи стол — чернил там меньше, чем в тексте.
     */
    private class Edge(val inside: Mat, val outside: Mat, val outsideSeen: Int, val byRows: Boolean) {
        fun cuts(): Boolean {
            // Край на кромке снимка: что за ним, не видно, и резать там нечего.
            if (outsideSeen < MIN_SEEN * outside.total()) return false
            val a = profile(inside, byRows)
            val b = profile(outside, byRows)
            val inDensity = a.sum() / inside.total().toDouble()
            val outDensity = b.sum() / outsideSeen.toDouble()
            if (inDensity < MIN_DENSITY) return false
            if (inDensity >= MIN_TEXT_DENSITY && outDensity >= TEXT_GOES_ON * inDensity) return true
            return outDensity >= OUTSIDE_SHARE * inDensity && correlation(a, b) > MIN_CORRELATION
        }
    }

    private fun profile(m: Mat, byRows: Boolean): DoubleArray {
        val reduced = Mat()
        try {
            Core.reduce(m, reduced, if (byRows) 1 else 0, Core.REDUCE_SUM, CvType.CV_32S)
            val values = IntArray(reduced.total().toInt())
            reduced.get(0, 0, values)
            return DoubleArray(values.size) { values[it].toDouble() }
        } finally {
            reduced.release()
        }
    }

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val n = minOf(a.size, b.size)
        if (n < 2) return 0.0
        val ma = a.take(n).average()
        val mb = b.take(n).average()
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in 0 until n) {
            ab += (a[i] - ma) * (b[i] - mb)
            aa += (a[i] - ma) * (a[i] - ma)
            bb += (b[i] - mb) * (b[i] - mb)
        }
        return if (aa == 0.0 || bb == 0.0) 0.0 else ab / sqrt(aa * bb)
    }

    private const val WORK_SIDE = 800.0
    private const val MARGIN = 0.10
    private const val GAP = 0.012
    private const val STRIP = 0.06
    private const val MIN_DENSITY = 0.02
    private const val OUTSIDE_SHARE = 0.4
    private const val MIN_CORRELATION = 0.5
    private const val MIN_TEXT_DENSITY = 0.03
    private const val TEXT_GOES_ON = 0.8
    private const val MIN_SEEN = 0.3

    /**
     * Сколько полосы за краем должно быть на снимке, чтобы край считался видимым, — меньше,
     * чем для разреза: чтобы назвать край видимым, хватает узкой полоски стола (кадр 23:
     * тёмный стол за правым краем шириной в полтора десятка точек кадра поиска).
     */
    private const val MIN_EDGE_SEEN = 0.1

    /** Длинная сторона листа, на которой ищется текст за краем. */
    private const val BEYOND_SIDE = 500.0

    /** Как далеко за краем ищется текст — доля стороны листа (подпись под таблицей кадра 04). */
    private const val BEYOND = 0.3

    /** Насколько (уровней из 255) бумага за краем может разниться по яркости с бумагой листа. */
    private const val PAPER_LEVELS = 25.0

    /** Буква — доля стороны листа: закрытием такие тёмные пятна входят в бумагу. */
    private const val LETTER = 0.02

    /** Сколько текста за краем — доля текста внутри — уже потеря. */
    private const val TEXT_BEYOND_SHARE = 0.03

    /** И не меньше этой доли площади листа: отдельные точки шума текстом не считаются. */
    private const val MIN_TEXT_BEYOND = 0.0005
}
