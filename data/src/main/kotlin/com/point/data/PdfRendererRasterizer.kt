package com.point.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.point.core.flow.ObjectStore
import com.point.core.flow.PdfRasterizer
import com.point.core.flow.READER_NO_PAGES
import com.point.core.flow.READER_NOT_DECODED
import com.point.core.flow.RasterizedPages
import com.point.core.flow.pagesToDraw
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

class PdfRendererRasterizer @Inject constructor(
    private val store: ObjectStore,
) : PdfRasterizer {

    override suspend fun rasterize(obj: PointObject): RasterizedPages = rasterize(obj, except = emptySet())

    override suspend fun rasterize(obj: PointObject, except: Set<Int>): RasterizedPages = withContext(Dispatchers.IO) {
        val src = File(obj.uri.value)

        ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val renderer = PdfRenderer(pfd)
            try {

                // Документ без единой страницы — про сам объект, а не про попытку (#570).
                // Молчаливая пустая папка выглядела успехом разбора. Слова человеку даёт
                // readerFailure, сам этот сигнал остаётся внутри.
                if (renderer.pageCount == 0) error(READER_NO_PAGES)
                val dir = File(store.newScratchFile("pages").value).apply { mkdirs() }

                // За заход рисуется не больше MAX_PAGES — это бережёт память и время телефона.
                // Но срез не молчит: настоящий счёт страниц уходит дальше вместе с папкой, и
                // чтение говорит человеку «100 из 150», а не «100 из 100» — непрочитанный хвост
                // не выдаётся за конец документа (см. PdfRasterizer.rasterize). И срез больше не
                // навсегда: заход рисует сотню ещё не прочитанных страниц, и следующее чтение
                // рисует хвост (карточка «Не отвечать «Ничего не нашлось» по скану, прочитанному
                // не целиком»).
                val drawn = pagesToDraw(except, renderer.pageCount, MAX_PAGES)
                if (drawn.size == MAX_PAGES && drawn.last() < renderer.pageCount) {
                    Log.w(TAG, "PDF has ${renderer.pageCount} pages; rasterising ${drawn.size} up to page ${drawn.last()}")
                }
                for (number in drawn) {
                    val page = renderer.openPage(number - 1)
                    try {
                        val w = (page.width * SCALE).coerceIn(1, MAX_DIM)
                        val h = (page.height * SCALE).coerceIn(1, MAX_DIM)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        // Имя — настоящий номер страницы в документе, а не номер в этом заходе.
                        File(dir, "page-%03d.jpg".format(number)).outputStream().use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }
                        bmp.recycle()
                    } finally {
                        page.close()
                    }
                }
                RasterizedPages(ScratchRef(dir.absolutePath), total = renderer.pageCount, numbers = drawn)
            } finally {
                renderer.close()
            }
        }
    }

    /**
     * Ошибка чтения наружу не глотается (#570): по ней человеку называют причину — пустой
     * документ или испорченный файл, — а молчаливый `null` был неотличим от «нечего показать».
     */
    override suspend fun rasterizeFirstPage(obj: PointObject): ScratchRef? = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.open(File(obj.uri.value), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val renderer = opened(pfd)
            try {
                if (renderer.pageCount == 0) error(READER_NO_PAGES)
                val page = renderer.openPage(0)
                try {
                    val w = (page.width * SCALE).coerceIn(1, MAX_DIM)
                    val h = (page.height * SCALE).coerceIn(1, MAX_DIM)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val ref = store.newScratchFile("jpg")
                    File(ref.value).outputStream().use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    bmp.recycle()
                    ref
                } finally {
                    page.close()
                }
            } finally {
                renderer.close()
            }
        }
    }

    /**
     * Документ не собрался из байтов — это про сам объект, и говорит это тот, кто байты
     * видел (#1258, #1271).
     *
     * `PdfRenderer` отвечает на негодный документ `IOException`. Тем же `IOException` дальше
     * по этому пути говорит запись готовой страницы в scratch — на полном телефоне «места
     * нет» превращалось в приговор «файл повреждён», и снять его в сеансе нечем. Поэтому
     * отказ разобрать документ ридер называет сам, ровно как уже называет «страниц нет»:
     * слова человеку из этого сигнала делает `readerFailure`, наружу он не выходит.
     *
     * Нужно это там, где по ответу решают годность объекта, — на пути первой страницы.
     * Разбор всего документа судьёй годности не бывает: его отказ уходит неудачей операции,
     * и подменять сигнал незачем.
     */
    private fun opened(pfd: ParcelFileDescriptor): PdfRenderer = try {
        PdfRenderer(pfd)
    } catch (e: IOException) {
        Log.w(TAG, "PDF did not parse", e)
        error(READER_NOT_DECODED)
    }

    private companion object {
        const val TAG = "PointPdf"
        const val SCALE = 2
        const val MAX_DIM = 2400
        const val MAX_PAGES = 100
    }
}
