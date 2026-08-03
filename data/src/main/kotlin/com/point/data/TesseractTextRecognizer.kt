package com.point.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import com.point.core.flow.Atom
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.AtomRecognizer
import com.point.core.flow.Box
import com.point.core.flow.CappedRead
import com.point.core.flow.FrameTransform
import com.point.core.flow.OCR_READ_BUDGET_MS
import com.point.core.flow.OcrClock
import com.point.core.flow.ReadingBudget
import com.point.core.flow.ocrDoneLine
import com.point.core.flow.readWithBudget
import com.point.core.model.PointObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * On-device OCR via Tesseract 5 (tesseract4android). The `rus`/`eng` models are bundled in
 * assets and copied into filesDir on first use (Tesseract needs a real
 * `tessdata/` directory on disk). The models are LSTM-only (tessdata_fast) so the
 * engine is forced to OEM_LSTM_ONLY. Thin native glue — the OCR decision logic
 * lives in the tested OcrRealizer; a failure here returns blank so the realizer
 * can fall back to the cloud LLM. Diagnostics go to logcat under "PointOCR".
 *
 * Чтение живёт под пределом времени (#262, `readWithBudget`): живой прогон корпуса показал
 * чтения, которые не кончаются, — 12-мегапиксельный кадр, четыре полных прохода движка и ни
 * одного колпака. Теперь базовое чтение и каждая проба поворота идут под колпаком, пробы — на
 * уменьшенной копии, а строка `OCR done` печатается всегда, чем бы чтение ни кончилось.
 *
 * Мелкий кадр перед чтением **увеличивается** (#273, `readingUpscale`): декодер умеет только
 * прореживать большой кадр, и снимок, родившийся мелким, приезжал движку ниже всего, на чём
 * конвейер настраивали. Решение — чистое правило, ресайз — за швом (`preparedBitmap`), множитель
 * уезжает в `FrameTransform.upscale`, иначе адреса прочитанного поехали бы во столько же раз.
 */
class TesseractTextRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) : AtomRecognizer {

    private val clock = OcrClock { System.currentTimeMillis() }

    override suspend fun read(obj: PointObject): AtomLayer = withContext(Dispatchers.IO) {
        val budget = ReadingBudget(OCR_READ_BUDGET_MS, clock)
        if (!obj.mime.startsWith("image/")) {
            return@withContext done(AtomLayer(emptyList(), incomplete = "not an image"), budget)
        }
        val decoded = decodeBounded(obj.uri.value, OCR_MAX_PX)
        if (decoded == null) {
            Log.w(TAG, "bitmap decode failed: ${obj.mime} @ ${obj.uri.value}")
            return@withContext done(AtomLayer(emptyList(), incomplete = "decode failed"), budget)
        }
        // Мелкий кадр увеличивается ПЕРЕД чтением (#273) — единственный приём, который на замере
        // вылечил провал, ничего не отправив наружу. Решает чистое правило; крупный кадр оно не
        // трогает, и тогда `ready.frame` — тот же битмап, без второй копии в памяти.
        val ready = preparedBitmap(decoded.bitmap, knownTextHeightPx(obj))
        val bitmap = ready.frame
        // Исходная копия освобождается сразу, а не в finally: держать рядом с увеличенной ещё и
        // её значило бы платить лишние 16 МБ всё чтение, а нужна она была ровно до этой строки.
        if (bitmap !== decoded.bitmap) decoded.bitmap.recycle()
        val frame = Decoded(bitmap, decoded.sample, decoded.rotation, ready.scale)
        if (ready.upscaled) Log.i(TAG, "frame upscaled x${ready.scale} -> ${bitmap.width}x${bitmap.height}")
        // Копия для проб поворотов создаётся лениво: хорошо прочитанную страницу не крутят,
        // и платить половиной кадра памяти за каждый скриншот незачем.
        var probe: Bitmap? = null
        val tess = TessBaseAPI()
        try {
            val dataPath = TessData.ensure(context)
            // OEM 1 = LSTM_ONLY — matches the tessdata_fast (LSTM) models.
            val ok = tess.init(dataPath.absolutePath, LANG, 1)
            if (!ok) {
                Log.w(TAG, "Tesseract init failed (dataPath=${dataPath.absolutePath}, lang=$LANG)")
                return@withContext done(AtomLayer(emptyList(), incomplete = "engine init failed"), budget)
            }
            val version = runCatching { tess.version ?: "" }.getOrDefault("")
            // Ориентация — проба и измерение (#262): фото бумаги на столе EXIF не несёт, а
            // боком движок читает мусор. Крутится только слабое чтение; бюджет, колпаки и
            // выбор поворота — в readWithBudget (:core:flow), под тестами.
            val planned = readWithBudget(
                budget,
                readFull = { angle, capMs -> cappedReadAt(tess, bitmap, frame, angle, 1, version, capMs) },
                readProbe = { angle, capMs ->
                    val source = probe ?: probeSource(bitmap).also { probe = it }
                    val factor = if (source === bitmap) 1 else 2
                    cappedReadAt(tess, source, frame, angle, factor, version, capMs)
                },
            )
            if (planned.angleDegrees != 0) Log.i(TAG, "OCR orientation: +${planned.angleDegrees}°")
            dumpForAcceptance(planned.layer)
            done(planned.layer, budget)
        } catch (e: Throwable) {
            Log.w(TAG, "OCR error", e)
            done(AtomLayer(emptyList(), incomplete = "error: ${e.javaClass.simpleName}"), budget)
        } finally {
            runCatching { tess.recycle() }
            probe?.takeIf { it !== bitmap }?.recycle()
            bitmap.recycle()
        }
    }

    /**
     * Каждый выход из чтения проходит здесь: строка `OCR done` печатается **всегда** — успех,
     * таймаут, нечитаемый кадр. Чтение без этой строки снаружи неотличимо от «ещё думает»,
     * и именно так три кадра корпуса молча съели живой прогон (#262).
     */
    private fun done(layer: AtomLayer, budget: ReadingBudget): AtomLayer {
        Log.i(TAG, ocrDoneLine(layer, budget.spentMs()))
        return layer
    }

    /**
     * Одно чтение движка в заданном довороте под колпаком времени.
     *
     * Распознавание запускает `getHOCRText(0)` — **единственный** вход движка, куда его обвязка
     * передаёт монитор с отменой, то есть единственный, который умеет останавливаться по
     * `stop()` с другого потока (сам hOCR-текст не нужен и выбрасывается). Прежний порядок
     * «`getUTF8Text` запускает распознавание» останавливаться не умел — потому чтение и было
     * вечным. Текст и итератор после этого читают уже готовый результат, ничего не запуская.
     *
     * Остановленное чтение отдаёт пустой слой, а не огрызок: после отмены в странице остаются
     * слова без результата, и официальный путь Tesseract их не читает (CLI после дедлайна
     * страницу не рендерит). Правду о причине пустоты несёт пометка слоя, её ставит план.
     *
     * Сторож живёт ровно столько, сколько сам вызов движка, и **дожидается** в finally
     * (`interrupt` + `join`): `stop()` после возврата безвреден (обвязка сбрасывает флаг отмены
     * на входе каждого распознавания), а вот пережить `recycle()` сторож не имеет права —
     * незавершённый `stop()` рядом с освобождением нативных данных был бы use-after-free.
     */
    private fun cappedReadAt(
        tess: TessBaseAPI,
        source: Bitmap,
        frame: Decoded,
        extraRotation: Int,
        sampleFactor: Int,
        version: String,
        capMs: Long,
    ): CappedRead {
        val image = source.rotated(extraRotation)
        try {
            tess.setImage(image)
            val fired = AtomicBoolean(false)
            val watchdog = Thread {
                try {
                    Thread.sleep(capMs.coerceAtLeast(1))
                    fired.set(true)
                    runCatching { tess.stop() }
                } catch (_: InterruptedException) {
                    // Чтение уложилось в колпак — сторожу нечего останавливать.
                }
            }.apply {
                name = "PointOcrDeadline"
                isDaemon = true
                start()
            }
            val hocr = try {
                tess.getHOCRText(0)
            } finally {
                watchdog.interrupt()
                watchdog.join()
            }
            // Отменённое распознавание возвращает null; строка при сработавшем стороже значит,
            // что отмена опоздала к уже готовому результату — тогда он полный и читается.
            if (fired.get() && hocr == null) return CappedRead(EMPTY, cut = true)
            val toRawFrame = FrameTransform(
                sample = frame.sample * sampleFactor,
                rotationDegrees = (frame.rotation + extraRotation) % 360,
                uprightWidth = image.width,
                uprightHeight = image.height,
                // Пробы половинят уже увеличенный кадр, поэтому множители складываются, а не
                // спорят: sample растёт от пробы, upscale остаётся тем, чем растянули кадр.
                upscale = frame.upscale,
            )
            // Распознавание уже сделано вызовом выше — здесь только сборка текста и слов по
            // готовому результату, в прежнем порядке: сперва текст движка, потом итератор.
            val engineText = tess.getUTF8Text()?.trim().orEmpty()
            val atoms = words(tess, toRawFrame, version)
            return CappedRead(
                AtomLayer(atoms, readerText = engineText.ifEmpty { null }, transform = toRawFrame),
                cut = false,
            )
        } finally {
            if (image !== source) image.recycle()
        }
    }

    /**
     * Копия кадра для проб поворотов: вдвое меньше по стороне — вчетверо дешевле проход движка.
     * Пробе не нужны точные буквы, ей нужен счёт прочитанного; мелкий кадр не половинится —
     * дешёвому и так, а текст на нём от уменьшения стал бы нечитаемым.
     */
    private fun probeSource(bitmap: Bitmap): Bitmap =
        if (maxOf(bitmap.width, bitmap.height) < PROBE_HALF_MIN_EDGE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width / 2).coerceAtLeast(1),
                (bitmap.height / 2).coerceAtLeast(1),
                true,
            )
        }

    /** Копия, довёрнутая по часовой стрелке; 0° — тот же битмап, без лишней памяти. */
    private fun Bitmap.rotated(degrees: Int): Bitmap =
        if (degrees % 360 == 0) {
            this
        } else {
            Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        }

    /**
     * Слова с их местом на странице.
     *
     * Раньше здесь стоял `getUTF8Text()`, и геометрия выбрасывалась на первом же шаге — при том
     * что движок её знает. Из-за этого 14-значный трек, разорванный движком на куски, собрать
     * обратно было нечем: адреса не существовало (#257).
     *
     * Координаты приводятся к **сырому кадру** через [toRawFrame]: [decodeBounded] уменьшает
     * длинную сторону и доворачивает по EXIF, поэтому движок отдаёт места в системе изменённой
     * копии, а перечитывать сомнительное значение мы пойдём в исходный файл (ADR-0001, два
     * адресных пространства).
     */
    private fun words(tess: TessBaseAPI, toRawFrame: FrameTransform, version: String): List<Atom> {
        val iterator = tess.resultIterator ?: return emptyList()
        val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
        val atoms = mutableListOf<Atom>()
        iterator.begin()
        do {
            val text = iterator.getUTF8Text(level)?.trim().orEmpty()
            val rect = iterator.getBoundingRect(level)
            if (text.isNotEmpty() && rect != null) {
                val upright = Box(
                    rect.left.toFloat(),
                    rect.top.toFloat(),
                    rect.right.toFloat(),
                    rect.bottom.toFloat(),
                )
                atoms += Atom(
                    id = "w${atoms.size}",
                    text = text,
                    box = toRawFrame.toRaw(upright),
                    // Движок отдаёт 0..100; ноль до единицы — чтобы шкала не зависела от ридера.
                    confidence = (iterator.confidence(level) / 100f).coerceIn(0f, 1f),
                    reader = READER,
                    readerVersion = version,
                    page = 0,
                )
            }
        } while (iterator.next(level))
        return atoms
    }

    /**
     * Bounded decode so OCR of a huge photo can't OOM (#18): subsample the long edge to
     * under 2×[maxPx], then rotate upright by EXIF (see [uprightByExif]).
     */
    private fun decodeBounded(path: String, maxPx: Int): Decoded? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var edge = maxOf(bounds.outWidth, bounds.outHeight)
        while (edge / 2 >= maxPx) {
            edge /= 2
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val degrees = exifDegrees(path)
        return Decoded(uprightByExif(decoded, degrees), sample, degrees)
    }

    /**
     * Прочитанный снимок вместе с тем, что с ним по дороге сделали.
     *
     * Уменьшение и доворот раньше были невидимы снаружи, и это было терпимо, пока наружу уходил
     * только текст. Теперь наружу уходят координаты, и без этих чисел их не вернуть в систему
     * исходного файла. Третье из них — увеличение мелкого кадра (#273): оно тянет координаты в
     * другую сторону, чем прореживание, и молчаливая потеря множителя увела бы каждый адрес во
     * столько же раз.
     */
    private class Decoded(
        val bitmap: Bitmap,
        val sample: Int,
        val rotation: Int,
        val upscale: Int = 1,
    )

    private fun exifDegrees(path: String): Int {
        val orientation = runCatching {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

    /**
     * A phone photo stores its rotation in EXIF and keeps the pixels sideways; [BitmapFactory]
     * ignores the tag and hands back those sideways pixels. Tesseract has no orientation model in
     * this path, so sideways lines decode to **deterministic gibberish** (`©`, `=`, stray 1–2 char
     * fragments) — *the* reason on-device OCR of a document photo returns junk rather than the text.
     * Rotating to upright first is the real fix; the cloud fallback was only masking it.
     */
    private fun uprightByExif(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(degrees.toFloat()) }, true,
        )
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    /**
     * Канал приёмки #257: слой целиком — в файл, который забирается без рута:
     * `adb pull /sdcard/Android/data/com.point/files/atoms-last.tsv`.
     *
     * Только debug: приёмка issue требует «дословный вывод устройства, а не сочинённый текст», а
     * дословные `слово+bbox+conf` до этого файла жили лишь в памяти процесса — фикстуру с
     * геометрией снять было нечем. Перезаписывается каждым чтением: «last» и есть контракт.
     */
    private fun dumpForAcceptance(layer: AtomLayer) {
        if (!BuildConfig.DEBUG || layer.atoms.isEmpty()) return
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return
            File(dir, "atoms-last.tsv").writeText(AtomCodec.encode(layer))
            Log.i(TAG, "atoms dumped: ${layer.atoms.size} -> $dir/atoms-last.tsv")
        }.onFailure { Log.w(TAG, "atoms dump failed", it) }
    }

    private companion object {
        const val TAG = "PointOCR"
        val LANG = TessData.LANG
        const val OCR_MAX_PX = 2048 // enough for legible text; bounds memory on huge photos (#18)

        /**
         * От этой стороны и выше кадр для проб половинится. Ниже — уже не 12-мегапиксельное фото,
         * а скриншот или превью: проба и так дешёвая, а уменьшение сделало бы мелкий текст
         * нечитаемым и подорвало бы сам счёт, ради которого проба существует.
         */
        const val PROBE_HALF_MIN_EDGE = 1600

        val EMPTY = AtomLayer(emptyList())

        /** Происхождение атома (#257): имя — константа контракта, версия — у живого движка. */
        const val READER = "tesseract"
    }
}
