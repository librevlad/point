package com.point.core.flow

/** Точка на кадре. */
data class Spot(val x: Float, val y: Float)

/**
 * Четыре угла страницы на снимке человека (#1332).
 *
 * Выпрямление снимает перспективу: слова уезжают вместе с геометрией, и место найденного,
 * посчитанное по выпрямленной копии, встало бы мимо строки на том снимке, который человек
 * видит. Углы — это всё, что нужно, чтобы вернуть место обратно: копия родилась ровно из
 * этого четырёхугольника, растянутого в прямоугольник.
 *
 * Углы — в координатах файла снимка, а не копии: перевод к файлу уже умеет [FrameTransform],
 * и второго перевода координат в проекте не заводится.
 *
 * Углы известны не всегда. Кривую страницу Point расправляет по линиям разлиновки, а не по
 * четырём углам, и обратного хода у такого выпрямления нет. Тогда углов нет — и места у
 * найденного нет тоже: молчание честнее места, показанного мимо.
 */
data class PageQuad(
    val topLeft: Spot,
    val topRight: Spot,
    val bottomRight: Spot,
    val bottomLeft: Spot,
) {

    /**
     * Место с выпрямленной копии — обратно на снимок.
     *
     * Копия размером [copyWidth]×[copyHeight] — это единичный квадрат, растянутый в
     * прямоугольник; обратный ход — тот же квадрат, натянутый на четырёхугольник страницы.
     * Прямая перспектива, та же, какой копия и делалась, только в другую сторону.
     *
     * Возвращается охватывающая рамка четырёх переведённых углов: на снимке под углом
     * прямоугольник перестаёт быть прямоугольником, а [Box] умеет только прямые стороны.
     */
    fun toSourceFrame(box: Box, copyWidth: Int, copyHeight: Int): Box {
        if (copyWidth <= 0 || copyHeight <= 0) return box
        val w = copyWidth.toFloat()
        val h = copyHeight.toFloat()
        val corners = listOf(
            at(box.left / w, box.top / h),
            at(box.right / w, box.top / h),
            at(box.right / w, box.bottom / h),
            at(box.left / w, box.bottom / h),
        )
        return Box(
            corners.minOf { it.x },
            corners.minOf { it.y },
            corners.maxOf { it.x },
            corners.maxOf { it.y },
        )
    }

    /** Точка единичного квадрата — на снимок. */
    fun at(u: Float, v: Float): Spot {
        val m = map
        val d = m[6] * u + m[7] * v + 1f
        if (d == 0f) return Spot(topLeft.x, topLeft.y)
        return Spot((m[0] * u + m[1] * v + m[2]) / d, (m[3] * u + m[4] * v + m[5]) / d)
    }

    /**
     * Перспектива «единичный квадрат → четырёхугольник», посчитанная один раз.
     *
     * Порядок коэффициентов: a b c d e f g h — x = (a·u + b·v + c) / (g·u + h·v + 1),
     * y = (d·u + e·v + f) / (g·u + h·v + 1). Углы четырёхугольника лежат на одной прямой —
     * страницы на кадре нет, и перспектива вырождается в перенос: возвращается левый верхний
     * угол, а не деление на ноль.
     */
    private val map: FloatArray by lazy {
        val x0 = topLeft.x
        val y0 = topLeft.y
        val x1 = topRight.x
        val y1 = topRight.y
        val x2 = bottomRight.x
        val y2 = bottomRight.y
        val x3 = bottomLeft.x
        val y3 = bottomLeft.y
        val sx = x0 - x1 + x2 - x3
        val sy = y0 - y1 + y2 - y3
        if (sx == 0f && sy == 0f) {
            return@lazy floatArrayOf(x1 - x0, x3 - x0, x0, y1 - y0, y3 - y0, y0, 0f, 0f)
        }
        val dx1 = x1 - x2
        val dx2 = x3 - x2
        val dy1 = y1 - y2
        val dy2 = y3 - y2
        val den = dx1 * dy2 - dx2 * dy1
        if (den == 0f) {
            return@lazy floatArrayOf(0f, 0f, x0, 0f, 0f, y0, 0f, 0f)
        }
        val g = (sx * dy2 - dx2 * sy) / den
        val h = (dx1 * sy - sx * dy1) / den
        floatArrayOf(
            x1 - x0 + g * x1, x3 - x0 + h * x3, x0,
            y1 - y0 + g * y1, y3 - y0 + h * y3, y0,
            g, h,
        )
    }
}

/**
 * Углы страницы, найденные на развёрнутом кадре, — в координатах файла снимка.
 *
 * Тот же перевод, каким возвращается к файлу место найденного: копии своей арифметики
 * координат здесь не заводится (#1013).
 */
fun FrameTransform.toRaw(quad: PageQuad): PageQuad = PageQuad(
    topLeft = toRaw(quad.topLeft),
    topRight = toRaw(quad.topRight),
    bottomRight = toRaw(quad.bottomRight),
    bottomLeft = toRaw(quad.bottomLeft),
)

private fun FrameTransform.toRaw(spot: Spot): Spot =
    toRaw(Box(spot.x, spot.y, spot.x, spot.y)).let { Spot(it.left, it.top) }
