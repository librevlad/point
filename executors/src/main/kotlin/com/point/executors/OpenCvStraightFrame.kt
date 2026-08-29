package com.point.executors

import android.graphics.Bitmap
import com.point.core.flow.ObjectStore
import com.point.core.flow.StraightFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Кадр выпрямляет тот же ход, каким «Скан» готовит страницу (#1041): границы листа, снятая
 * перспектива, выбеленная бумага, увеличенное мелкое. Нового движка здесь нет — есть второй
 * вход в уже написанный.
 *
 * Выпрямленная копия ложится в scratch и живёт ровно столько же, сколько сама копия объекта:
 * это не новый объект, а кадр, которым посмотрели на старый. Ни одно действие человека её не
 * рождает и не показывает.
 *
 * Берётся именно [OpenCvScan.enhance], а не `enhanceAsIs`: страницы на кадре не нашлось —
 * выпрямлять нечего, и `null` честнее выбеленного кадра целиком (#1333). Повторное чтение по
 * одному выбеливанию — отдельный ход и отдельная карточка (#1046, #1331).
 *
 * Обработчика нет на устройстве, страница не нашлась или кадр не раскрылся — `null`: второго
 * захода не будет, и чтение остаётся тем, каким было.
 */
class OpenCvStraightFrame(private val store: ObjectStore) : StraightFrame {

    override suspend fun of(path: String): String? = withContext(Dispatchers.IO) {
        if (!OpenCvScan.available) return@withContext null
        val src = Bitmaps.decodeUpright(path, Bitmaps.SCAN_PLUS_MAX_PX) ?: return@withContext null
        val straight = runCatching { OpenCvScan.enhance(src) }.getOrNull()
        src.recycle()
        if (straight == null) return@withContext null

        val saved = runCatching {
            val ref = store.newScratchFile("jpg")
            File(ref.value).outputStream().use {
                straight.compress(Bitmap.CompressFormat.JPEG, Bitmaps.JPEG_QUALITY, it)
            }
            ref.value
        }.getOrNull()
        straight.recycle()
        saved
    }
}
