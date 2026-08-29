package com.point.executors

import android.graphics.Bitmap
import android.media.ExifInterface
import android.util.Log
import com.point.core.flow.ObjectStore
import com.point.core.flow.PaperWhitener
import com.point.core.flow.READING_FRAME_PX
import com.point.core.flow.WhitenedFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Выбеленная копия снимка для чтения (#1046).
 *
 * Работа целиком на телефоне и бесплатная: кадр никуда не уезжает, обработка та же, что у
 * «Скана». Копия делается уменьшенной до того же потолка, до которого кадр уменьшает и сам
 * читатель, — выбеливать снимок в полный размер значило бы держать в памяти десятки
 * мегабайт ради того же результата.
 */
class OpenCvPaperWhitener(private val store: ObjectStore) : PaperWhitener {

    override suspend fun whitened(path: String): WhitenedFrame? = withContext(Dispatchers.IO) {
        if (!OpenCvScan.available) return@withContext null
        runCatching {
            // Кадр берётся развёрнутым по метке камеры — тем же ходом, каким его берут «Скан»
            // и выпрямление. Выбеливатель защищает содержимое строками листа: он ищет их
            // горизонтальными прогонами, а у снимка с рук метка обычно стоит «повернуть на
            // 90°». Боковой лист остался бы без защиты вовсе, и полоса вдоль краёв стёрла бы
            // в белое шапку, дату, итог и подписи — ровно то, ради чего документ снимали.
            val frame = Bitmaps.uprightFrame(path, READING_FRAME_PX) ?: return@runCatching null
            val white = try {
                OpenCvScan.whiten(frame.bitmap)
            } finally {
                frame.bitmap.recycle()
            }

            // Копия обязана лежать так же, как снимок: слова с неё становятся знанием об
            // исходном кадре и возвращаются на него одним множителем. Поэтому выбеленный лист
            // кладётся обратно в раскладку файла, а метка поворота досылается ему следом.
            val asShot = Bitmaps.turned(white, -frame.degrees)
            val ref = store.newScratchFile("jpg")
            try {
                File(ref.value).outputStream().use {
                    asShot.compress(Bitmap.CompressFormat.JPEG, Bitmaps.JPEG_QUALITY, it)
                }
            } finally {
                asShot.recycle()
            }
            // Метка поворота не легла — второго захода не будет. Молча читать копию
            // неповёрнутой нельзя: слой с неё становится знанием об исходном снимке и унёс
            // бы чужой поворот и переставленные стороны, а по ним потом считают вырезку
            // найденного места и вырезку ячейки. Ошибка не должна исчезать в молчаливой
            // цепочке (ADR-0001 §18).
            if (!keepsOrientation(from = path, to = ref.value)) {
                Log.w(TAG, "whitened copy kept no orientation tag: $path")
                File(ref.value).delete()
                return@runCatching null
            }

            WhitenedFrame(ref.value, frame.shrink)
        }.getOrNull()
    }

    /**
     * Копия наследует поворот снимка: по этой метке читатель разворачивает кадр.
     *
     * `Bitmap.compress` пишет JPEG без EXIF, поэтому метка досылается отдельно — и после
     * записи перечитывается. Обещание «копия лежит так же, как снимок» стоит ровно столько,
     * сколько стоит эта проверка.
     */
    private fun keepsOrientation(from: String, to: String): Boolean = runCatching {
        val orientation = ExifInterface(from)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        if (orientation == ExifInterface.ORIENTATION_NORMAL) return true
        ExifInterface(to).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        val written = ExifInterface(to)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        written == orientation
    }.getOrDefault(false)

    private companion object {

        const val TAG = "PaperWhitener"
    }
}
