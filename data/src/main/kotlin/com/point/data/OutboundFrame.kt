package com.point.data

import android.graphics.Bitmap
import com.point.core.flow.FrameTransform
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Кадр, уходящий наружу: **те самые байты**, которые прочитает чужой движок, и [FrameTransform]
 * обратно в сырой файл.
 *
 * Пара неразрывна. Облако вернёт координаты в системе того, что мы ему послали; сомнительное
 * значение человек пойдёт перечитывать в исходный файл (ADR-0001, два адресных пространства). Без
 * преобразования, снятого ровно с этой копии, адрес облачного атома указывал бы мимо — и это тот
 * самый тихий сбой, который на глаз не виден: текст правильный, место чужое.
 */
class OutboundFrame(
    val bytes: ByteArray,
    val mime: String,
    val fileName: String,
    val transform: FrameTransform,
)

/**
 * Подготовка страницы к отправке — за интерфейсом, потому что это Android-декод (#280).
 *
 * Именно поэтому облачные читатели тестируются на подделках: подставив сюда фейк, тест проверяет
 * запрос, разбор ответа и приведение координат без единого пикселя и без сети.
 */
interface OutboundFrames {
    /** Кадр к отправке или `null`, если объект нечего слать (не изображение, файл не читается). */
    suspend fun of(obj: PointObject): OutboundFrame?
}

/**
 * Наружу уходит EXIF-выпрямленная копия — **та же самая**, которую читает офлайновый движок.
 *
 * Так сделано не ради экономии трафика. Два чтения сравнимы, только если они смотрели на одни
 * пиксели: пошли мы в облако исходный файл — сервис сам решил бы, слушаться ли EXIF, и мы бы
 * никогда не узнали, в какой системе пришли координаты. Расхождение двух ридеров должно быть
 * сигналом о чтении, а не о том, что одному из них дали кадр боком.
 *
 * **Размер копии — не «≤2048».** Правило [decodeSelectionFrame] делит длинную сторону пополам,
 * пока `сторона / 2 >= 2048`, поэтому наружу уходит кадр короче 4096 px, а эталонная ведомость
 * владельца (4000×3000) едет целиком, без единого деления. Это не оплошность и не место для
 * экономии: стоит поставить сюда более жёсткий предел, чем у офлайнового движка, — и два ридера
 * посмотрят на разные пиксели, а расхождение станет рассказывать про кадр, а не про чтение.
 * Трафик здесь дешевле сравнимости.
 */
class BitmapOutboundFrames @Inject constructor() : OutboundFrames {

    override suspend fun of(obj: PointObject): OutboundFrame? = withContext(Dispatchers.IO) {
        if (!obj.mime.startsWith("image/")) return@withContext null
        val frame = decodeSelectionFrame(obj.uri.value, PAGE_MAX_PX) ?: return@withContext null
        try {
            val out = ByteArrayOutputStream()
            frame.bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            OutboundFrame(
                bytes = out.toByteArray(),
                mime = "image/jpeg",
                fileName = "page.jpg",
                transform = frame.transform,
            )
        } finally {
            frame.bitmap.recycle()
        }
    }

    private companion object {
        /**
         * Тот же порог прореживания, что у офлайнового движка (`TesseractTextRecognizer.OCR_MAX_PX`):
         * один вход — два чтения. Число это не «максимум стороны», а порог правила деления пополам,
         * см. [decodeSelectionFrame]; менять его в одиночку нельзя — разъедутся кадры ридеров.
         */
        const val PAGE_MAX_PX = 2048

        /** Мелкий текст ведомости живёт в артефактах сжатия — экономить здесь не на чем. */
        const val JPEG_QUALITY = 92
    }
}
