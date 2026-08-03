package com.point.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import com.point.core.flow.Box
import com.point.core.flow.FrameRotation
import com.point.core.flow.GrayFrame
import com.point.core.flow.METER_WORK_PX
import com.point.core.flow.MeterDisplayCandidate
import com.point.core.flow.MeterDisplayReading
import com.point.core.flow.MeterReadout
import com.point.core.flow.MeterReader
import com.point.core.flow.cropRegionIn
import com.point.core.flow.findMeterDisplays
import com.point.core.flow.meterDigitsRead
import com.point.core.flow.meterInk
import com.point.core.flow.meterPlaceStage
import com.point.core.flow.meterUpscale
import com.point.core.flow.reportStage
import com.point.core.model.PointObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Чтение табло прибора на устройстве (#262): подготовленный кусок кадра плюс цифровой словарь.
 *
 * Замер корпуса назвал причину прямо: на фото счётчика движок не читает **ничего** — слой атомов
 * выходит символьным шумом. Здесь ему дают другой вход:
 *
 * 1. **место** — [findMeterDisplays] предлагает до трёх мест, похожих на строку одинаковых знаков;
 * 2. **доворот** — кадр крутится на угол кандидата, и наклонный барабан становится горизонтальным
 *    (на кадре 09 корпуса он стоит почти вертикально: так вкручен прибор);
 * 3. **кроп** — режется только табло, а не весь двор с гравием;
 * 4. **увеличение** — знак дотягивается до высоты, которую движок читает уверенно;
 * 5. **контраст** — местный порог и одна полярность (чёрные знаки на белом);
 * 6. **словарь** — движку разрешены только цифры, режим «одна строка».
 *
 * Всё вместе — не подмена обычного чтения, а отдельный путь: [TesseractTextRecognizer] читает
 * страницу целиком, а сюда приходят **по отдельному тапу человека** («Прочитать показание»).
 * Само собой это чтение не запускается: поиск табло срабатывает на 22 кадрах корпуса из 23, а
 * движку здесь разрешены только цифры — значит на строке букв он выдаёт цифры, и отличить прибор
 * от документа этот путь не может. Кто на снимке, знает человек (#262, разбор в `DECISIONS.md`).
 *
 * **Ничего не теряется молча.** Не нашли ни одного места — [MeterReadout.nothingFound]; нашли, но
 * цифр не собралось — [MeterReadout.foundButUnread]. Это две разные новости для человека, и
 * сливать их в одну пустоту нельзя.
 *
 * **Стадии (#288) и чего про них не проверить.** Поиск места называет своё имя в реализаторе
 * («Ищу табло прибора»), а чтение каждого места — здесь, [meterPlaceStage]. Сама фраза под тестом
 * в `:core:flow`; порядок, в котором она звучит, на JVM не проверяется — цикл живёт вплотную к
 * `Bitmap` и нативному Tesseract. Сказано вслух, а не спрятано: это тот же случай, что рисование
 * страниц PDF и конвейер OpenCV — проверяется живьём на телефоне.
 */
class TesseractMeterReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : MeterReader {

    override suspend fun read(obj: PointObject): MeterReadout = withContext(Dispatchers.IO) {
        if (!obj.mime.startsWith("image/")) return@withContext MeterReadout.NOTHING
        val upright = decodeBoundedUpright(obj.uri.value, DECODE_MAX_PX)
        if (upright == null) {
            Log.w(TAG, "meter: bitmap decode failed @ ${obj.uri.value}")
            return@withContext MeterReadout.NOTHING
        }
        try {
            val candidates = findMeterDisplays(grayFrame(upright, METER_WORK_PX))
            Log.i(TAG, "meter: ${candidates.size} display candidate(s)")
            if (candidates.isEmpty()) return@withContext MeterReadout.NOTHING
            val readings = readDigits(upright, candidates)
            Log.i(TAG, "meter: read ${readings.size} of ${candidates.size}")
            MeterReadout(readings, candidates.size)
        } catch (e: Throwable) {
            Log.w(TAG, "meter: read error", e)
            MeterReadout.NOTHING
        } finally {
            upright.recycle()
        }
    }

    /**
     * Один запуск движка на все кандидаты: инициализация Tesseract дороже самого чтения полоски.
     *
     * `suspend` — ради канала стадий (#288), не ради потока: каждое место это отдельный проход
     * движка по отдельно довёрнутому и увеличенному куску кадра, и все они шли молча после
     * единственной фразы «Ищу табло прибора». Слова берутся из настоящего цикла — сколько мест
     * нашлось, столько и произносится.
     */
    private suspend fun readDigits(upright: Bitmap, candidates: List<MeterDisplayCandidate>): List<MeterDisplayReading> {
        val tess = TessBaseAPI()
        return try {
            // OEM 1 = LSTM_ONLY — как и у чтения страницы: модели в assets только LSTM.
            if (!tess.init(TessData.ensure(context).absolutePath, TessData.LANG, 1)) {
                Log.w(TAG, "meter: Tesseract init failed")
                return emptyList()
            }
            // Словарь и режим — вся разница с чтением страницы. Без словаря барабан читается
            // буквами («ОО8» вместо «008»), без режима одной строки движок ищет абзацы там,
            // где их нет.
            tess.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, DIGITS)
            tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
            candidates.mapIndexedNotNull { index, candidate ->
                // Стадия — до подготовки куска: это место уже взято в работу, даже если рамка
                // выродится и читать окажется нечего. Сказать о нём после было бы враньём про
                // то, чем занята прошедшая секунда.
                reportStage(meterPlaceStage(index, candidates.size))
                val prepared = prepare(upright, candidate) ?: return@mapIndexedNotNull null
                try {
                    tess.setImage(prepared)
                    val digits = meterDigitsRead(tess.utF8Text.orEmpty()) ?: return@mapIndexedNotNull null
                    MeterDisplayReading(digits, candidate.cropRegion(upright), candidate.angleDegrees)
                } finally {
                    prepared.recycle()
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "meter: digits error", e)
            emptyList()
        } finally {
            runCatching { tess.recycle() }
        }
    }

    /** Рамка кандидата в масштабе полноразмерного снимка — тем же путём, что и кроп. */
    private fun MeterDisplayCandidate.cropRegion(upright: Bitmap): Box =
        cropRegionIn(FrameRotation(angleDegrees, upright.width, upright.height))

    /**
     * Кусок кадра, готовый к чтению: доворот → кроп → увеличение → контраст.
     *
     * `null` — рамка выродилась (кандидат уехал за край после округления). Молчание здесь
     * законно: место отбрасывается, число кандидатов остаётся прежним, и человек увидит
     * «нашли, но не прочитали», а не пустоту.
     */
    private fun prepare(upright: Bitmap, candidate: MeterDisplayCandidate): Bitmap? {
        val rotation = FrameRotation(candidate.angleDegrees, upright.width, upright.height)
        val rotated = upright.turned(candidate.angleDegrees)
        try {
            val region = candidate.cropRegionIn(rotation)
            val left = region.left.toInt().coerceIn(0, rotated.width - 1)
            val top = region.top.toInt().coerceIn(0, rotated.height - 1)
            val width = (region.right - region.left).roundToInt().coerceIn(1, rotated.width - left)
            val height = (region.bottom - region.top).roundToInt().coerceIn(1, rotated.height - top)
            if (width < MIN_CROP_PX || height < MIN_CROP_PX) return null
            val cut = Bitmap.createBitmap(rotated, left, top, width, height)
            // Высота знака в масштабе снимка: кандидат мерил её на уменьшенной копии.
            val digitPx = candidate.digitHeight * rotation.rotatedHeight / candidate.frame.rotatedHeight
            val scale = meterUpscale(digitPx)
            val big = if (scale == 1) cut else Bitmap.createScaledBitmap(cut, width * scale, height * scale, true)
            val ink = meterInk(grayFrame(big), digitPx * scale, candidate.darkDigits)
            val out = Bitmap.createBitmap(big.width, big.height, Bitmap.Config.ARGB_8888)
            val row = IntArray(big.width)
            for (y in 0 until big.height) {
                for (x in 0 until big.width) {
                    val v = ink.at(x, y)
                    row[x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
                out.setPixels(row, 0, big.width, 0, y, big.width, 1)
            }
            if (big !== cut) big.recycle()
            cut.recycle()
            return out
        } finally {
            if (rotated !== upright) rotated.recycle()
        }
    }

    /** Копия, довёрнутая по часовой — то же соглашение, что и у [FrameRotation]. */
    private fun Bitmap.turned(degrees: Int): Bitmap =
        if (degrees % 360 == 0) {
            this
        } else {
            Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        }

    private companion object {
        const val TAG = "PointOCR"
        const val DIGITS = "0123456789"

        /**
         * Потолок декодирования — ниже, чем у чтения страницы (2048), и это не экономия «на всякий
         * случай». Кадр здесь **крутится целиком** на произвольный угол, и повёрнутая копия
         * 2048×1536 на 45° занимает вчетверо больше исходной; три кандидата подряд — уже десятки
         * мегабайт пикового расхода на слабом телефоне (#18). При 1600 знак барабана на снимке с
         * телефона всё равно выходит выше [METER_TARGET_DIGIT_PX], то есть увеличивать его не
         * приходится вовсе — терять нечего. Число то же, что у скана (`Bitmaps.PROCESS_MAX_PX`).
         */
        const val DECODE_MAX_PX = 1600

        /** Мельче — не табло, а огрызок: движку такое давать бессмысленно. */
        const val MIN_CROP_PX = 16
    }
}

/** Кадр в оттенках серого для чистого поиска: Android-часть работы, ядро пикселей не видит. */
internal fun grayFrame(bitmap: Bitmap, longEdge: Int = 0): GrayFrame {
    val source = if (longEdge <= 0 || maxOf(bitmap.width, bitmap.height) <= longEdge) {
        bitmap
    } else {
        val k = longEdge.toFloat() / maxOf(bitmap.width, bitmap.height)
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * k).roundToInt().coerceAtLeast(1),
            (bitmap.height * k).roundToInt().coerceAtLeast(1),
            true,
        )
    }
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    val luma = IntArray(pixels.size)
    for (i in pixels.indices) {
        val p = pixels[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        luma[i] = ((299 * r + 587 * g + 114 * b) / 1000).coerceIn(0, 255)
    }
    val frame = GrayFrame(source.width, source.height, luma)
    if (source !== bitmap) source.recycle()
    return frame
}
