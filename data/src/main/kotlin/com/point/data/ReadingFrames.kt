package com.point.data

import android.graphics.Bitmap
import android.util.Log
import com.point.core.flow.AtomCodec
import com.point.core.flow.FrameUpscaler
import com.point.core.flow.META_CLOUD_ATOMS_REF
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.ReadyFrame
import com.point.core.flow.preparedForReading
import com.point.core.flow.typicalTextHeightPx
import com.point.core.model.PointObject
import java.io.File

/**
 * Android-сторона увеличения кадра перед чтением (#273): решение принимает чистое правило
 * (`readingUpscale`), здесь только сам ресайз и то, чем его кормят.
 *
 * **Оба читателя спрашивают одно и то же.** Офлайновый движок (`TesseractTextRecognizer`) и кадр,
 * уходящий облачному читателю (`BitmapOutboundFrames`), зовут отсюда одни функции с одним входом.
 * Иначе повторилась бы грабля, про которую `BitmapOutboundFrames` предупреждает прямо в KDoc:
 * поставь одному читателю другой размер — и расхождение двух чтений начнёт рассказывать про кадр,
 * а не про чтение.
 */

/** Билинейная фильтрация — не украшение: без неё увеличение даёт лестницу на штрихе, а именно по
 *  штриху движок отличает 8 от 6. */
internal val bitmapUpscaler = FrameUpscaler<Bitmap> { bitmap, scale ->
    Bitmap.createScaledBitmap(bitmap, bitmap.width * scale, bitmap.height * scale, true)
}

/**
 * Кадр, готовый к чтению: тот же битмап или его увеличенная копия — плюс множитель, которым это
 * сделано (он уезжает в [com.point.core.flow.FrameTransform.upscale] и в метаданные объекта).
 *
 * Исходный битмап **не освобождается** здесь: чей он и когда его отпускать, знает вызывающий
 * (у офлайнового движка он же служит источником проб поворота). Сравнение `ready.frame !== bitmap`
 * — тот же приём, что у резака улик.
 */
internal fun preparedBitmap(bitmap: Bitmap, textHeightPx: Int?): ReadyFrame<Bitmap> =
    try {
        preparedForReading(bitmap, bitmap.width, bitmap.height, textHeightPx, bitmapUpscaler)
    } catch (e: OutOfMemoryError) {
        // Увеличение — улучшение чтения, а не его условие: не хватило памяти — читаем кадр как
        // есть. Бюджет правила (12 Мп) делает этот случай редким, но телефон бывает занят чем-то
        // ещё, и падать всем чтением из-за необязательного шага нечем оправдать.
        //
        // Молчать при этом нельзя: страница прочтётся хуже, чем могла, и причина обязана быть
        // названа. Пометки в метаданных не будет по построению — множитель там ровно тот, каким
        // кадр читали, и «увеличивали» про неувеличенный кадр было бы неправдой.
        Log.w("PointOCR", "frame upscale skipped: out of memory", e)
        ReadyFrame(bitmap, 1)
    }

/**
 * Высота типичного слова, измеренная тем, кто уже читал этот кадр, — «плотность текста» правила.
 *
 * Слой пишет `OcrEnricher` (`META_OCR_ATOMS_REF`), облачный читатель кладёт свой рядом
 * (`META_CLOUD_ATOMS_REF`). Офлайновый идёт первым: он читал ровно ту копию, которую мы сейчас
 * готовим, а облачный — свою, объявленную сервисом.
 *
 * `null` — никто ещё не читал, файл не дожил до нас или слов в нём слишком мало, чтобы судить.
 * Тогда решает размер кадра, то есть увеличение, а не отказ от него: неизвестная плотность — это
 * незнание, а не «буквы крупные».
 */
internal fun knownTextHeightPx(obj: PointObject): Int? =
    (obj.metadata[META_OCR_ATOMS_REF] ?: obj.metadata[META_CLOUD_ATOMS_REF])
        ?.let { path -> runCatching { AtomCodec.decode(File(path).readText()) }.getOrNull() }
        ?.let(::typicalTextHeightPx)
