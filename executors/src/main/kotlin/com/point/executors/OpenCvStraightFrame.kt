package com.point.executors

import android.graphics.Bitmap
import com.point.core.flow.FrameTransform
import com.point.core.flow.ObjectStore
import com.point.core.flow.StraightFrame
import com.point.core.flow.StraightenedFrame
import com.point.core.flow.toRaw
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
 *
 * Вместе с копией отдаются четыре угла страницы — в координатах файла снимка, а не
 * развёрнутого кадра (#1332). Во сколько раз кадр мельче файла и на сколько его развернули,
 * знает декодер, и спрашивается это у него, а не считается здесь заново.
 */
class OpenCvStraightFrame(private val store: ObjectStore) : StraightFrame {

    override suspend fun of(path: String): StraightenedFrame? = withContext(Dispatchers.IO) {
        if (!OpenCvScan.available) return@withContext null
        val upright = Bitmaps.uprightFrame(path, Bitmaps.SCAN_PLUS_MAX_PX) ?: return@withContext null
        val src = upright.bitmap
        val toFile = FrameTransform(
            sample = upright.shrink,
            rotationDegrees = upright.degrees.toInt(),
            uprightWidth = src.width,
            uprightHeight = src.height,
        )
        val straight = runCatching { OpenCvScan.straightened(src) }.getOrNull()
        src.recycle()
        if (straight == null) return@withContext null

        val copy = straight.bitmap
        val saved = runCatching {
            val ref = store.newScratchFile("jpg")
            File(ref.value).outputStream().use {
                copy.compress(Bitmap.CompressFormat.JPEG, Bitmaps.JPEG_QUALITY, it)
            }
            ref.value
        }.getOrNull()
        val size = copy.width to copy.height
        copy.recycle()
        saved?.let {
            StraightenedFrame(
                path = it,
                page = straight.page?.let(toFile::toRaw),
                width = size.first,
                height = size.second,
            )
        }
    }
}
