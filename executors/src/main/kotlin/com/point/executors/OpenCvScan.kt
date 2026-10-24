package com.point.executors

import android.graphics.Bitmap
import com.point.core.flow.PageQuad
import com.point.vision.PageVision
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point

/**
 * Зрение страницы на телефоне: сам конвейер — общий с компьютером [PageVision] (#1379), здесь
 * только нативная часть Android и переход `Bitmap` ↔ матрица.
 */
object OpenCvScan {

    val available: Boolean by lazy { runCatching { OpenCVLoader.initLocal() }.getOrDefault(false) }

    /**
     * Выпрямленная страница, сведённая к чёрно-белому, или `null` — страницы не нашлось (#1333).
     *
     * Правило здесь то же, что у [enhance], и это важнее сходства кода: этим путём идёт
     * «Скан в PDF», и прежде он молча подставлял исходный кадр (`?: rgba`) — неисправленный
     * снимок уезжал страницей скана, и человеку он был неотличим от выпрямленной страницы.
     */
    fun process(src: Bitmap): Bitmap? = onMat(src) { rgba, scratch -> PageVision.process(rgba, scratch) }

    /**
     * Кадр целиком, сведённый к чёрно-белому (#1333): страницы не нашли, но работа не пропадает.
     *
     * Зовут это, только когда [process] уже ответил «не нашлось», и результат уезжает с
     * пометкой [com.point.core.flow.META_WHOLE_FRAME] — за выпрямленную страницу он не выдаётся.
     */
    fun processAsIs(src: Bitmap): Bitmap? = onMat(src) { rgba, scratch -> PageVision.processAsIs(rgba, scratch) }

    /**
     * Выпрямленная и подготовленная страница или `null` — страницы на снимке не нашлось (#1333).
     *
     * Прежде неудача молча возвращала исходник (`?: rgba`), и снаружи она была неотличима от
     * успеха: кривой кадр уезжал дальше как «скан». Теперь «не нашлось» — это ответ, а не
     * молчание: по нему кадр обрабатывается целиком ([enhanceAsIs]) и результат помечается
     * [com.point.core.flow.META_WHOLE_FRAME], а не выдаётся за выпрямленную страницу.
     */
    suspend fun enhance(src: Bitmap, readTurns: Int? = null): Bitmap? = straightened(src, readTurns)?.bitmap

    /**
     * То же выпрямление — и четыре угла страницы, из которых копия родилась (#1332).
     *
     * Углы нужны тому, кто возвращает прочитанное на снимок человека: копия — это
     * четырёхугольник страницы, растянутый в прямоугольник, и обратный ход по тем же углам
     * ставит найденное туда, куда человек смотрит.
     *
     * Кривую страницу Point расправляет по линиям разлиновки, а не по четырём углам
     * (`PageVision.dewarpByTps`), и обратного хода у неё нет — тогда углов нет: `null` честнее
     * четырёхугольника, которого не было.
     */
    suspend fun straightened(src: Bitmap, readTurns: Int? = null): Straightened? {
        var quad: PageQuad? = null
        val out = onMat(src) { rgba, scratch ->
            PageVision.straightened(rgba, scratch, readTurns)?.let { page -> quad = page.quad; page.mat }
        } ?: return null
        return Straightened(out, quad)
    }

    /**
     * Подготовка кадра без выравнивания (#1333) — то самое, что раньше пряталось за `?: rgba`.
     *
     * Страницы не нашёл никто, но выбеливание, снятие бликов и увеличение кадру всё равно
     * помогают: человек не остаётся ни с чем, а результат называет себя пометкой.
     */
    suspend fun enhanceAsIs(src: Bitmap): Bitmap? = onMat(src) { rgba, scratch -> PageVision.enhanceAsIs(rgba, scratch) }

    /**
     * Ровный свет и белая бумага — без выпрямления и без увеличения (#1046).
     *
     * Тот же выбеливатель, что и в «Скане»: он снимает тень от руки и градиент от окна,
     * из-за которых движок не видит строк, и оставляет цвет — синяя печать остаётся синей,
     * потому что цвет здесь доказательство подлинности, а не грязь.
     *
     * Геометрия кадра не трогается: слова остаются ровно там, где стояли на снимке, поэтому
     * прочитанное по такой копии можно вернуть исходнику.
     *
     * Лист приходит развёрнутым — как и всюду в этом конвейере. Содержимое защищается по
     * строкам, а строки ищутся горизонтальными прогонами: у бокового кадра они не находятся
     * вовсе, и полоса вдоль краёв стирает в белое то, что в ней лежит.
     */
    fun whiten(src: Bitmap): Bitmap = onMat(src) { rgba, scratch -> PageVision.whiten(rgba, scratch) }!!

    /** Кадр — в матрицу, ответ — обратно в картинку; все матрицы освобождаются здесь. */
    private inline fun onMat(src: Bitmap, work: (Mat, MutableList<Mat>) -> Mat?): Bitmap? {
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val scratch = mutableListOf(rgba)
        try {
            val done = work(rgba, scratch) ?: return null
            return bitmapOf(done)
        } finally {
            scratch.forEach { it.release() }
        }
    }

    private fun bitmapOf(mat: Mat): Bitmap {
        val out = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, out)
        return out
    }

    internal fun orderCorners(pts: Array<Point>): Array<Point> = PageVision.orderCorners(pts)

    internal fun distance(a: Point, b: Point): Double = PageVision.distance(a, b)
}

/**
 * Выпрямленная страница и четыре её угла на развёрнутом кадре (#1332).
 *
 * [page] — `null`, когда обратного хода нет: страницу расправили по линиям разлиновки, а не
 * по углам.
 */
class Straightened(val bitmap: Bitmap, val page: PageQuad?)
