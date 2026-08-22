package com.point.data

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.point.core.flow.Atom
import com.point.core.flow.AtomLayer
import com.point.core.flow.AtomRecognizer
import com.point.core.flow.Box
import com.point.core.flow.FrameTransform
import com.point.core.flow.atomLabel
import com.point.core.flow.readerFailure
import com.point.core.model.PointObject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Чтение кириллицы на устройстве — PP-OCRv5 (#747).
 *
 * Почему понадобился: на почтовой наклейке Нова Пошта прежний движок отдавал «ОДЕСА ПОСИЛ
 * КОВИИ Я» и «ЫТЛГОРОД-ДНТСТРОВСЬКИЙ» — по такому чтению «Понять» строило что угодно, вплоть
 * до даты, принятой за номер карты. Дело не в модели поверх, а в том, что читать было нечего.
 *
 * Две сети: детектор находит строки, распознаватель читает каждую. Обе — мобильные, локальные
 * и бесплатные: снимок никуда не уходит. Словарь — украинско-русская кириллица плюс латиница
 * и цифры (850 знаков), поэтому «Бритівка» остаётся «Бритівкою», а не превращается в «Бритву».
 */
@Singleton
class PaddleOcrRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) : AtomRecognizer {

    private val env by lazy { OrtEnvironment.getEnvironment() }

    private val detector by lazy { session("ppocr/det.onnx") }

    private val reader by lazy { session("ppocr/rec.onnx") }

    private val alphabet: List<String> by lazy {
        context.assets.open("ppocr/rec_keys.txt").bufferedReader().readLines()
    }

    /**
     * Пробел — отдельный класс распознавателя, идущий сразу за словарём.
     *
     * Пока его не отдавали, слова слипались: «ОДЕСАПОСИЛКОВИЙ», «067.6360560». Читалось
     * при этом верно — терялись именно границы слов, а по ним потом ищут телефон и адрес.
     */
    private val spaceClass: Int get() = alphabet.size + 1

    /**
     * Родная ли машина для движка.
     *
     * Если библиотеку для чужого процессора запускают через трансляцию, движок падает ещё
     * при загрузке — в собственном определении возможностей CPU — и уносит весь процесс:
     * такой обвал не поймать ни `runCatching`, ни чем-либо ещё. Поэтому берёмся за чтение
     * только там, где библиотека своя; иначе читает запасной движок.
     */
    private val runsNatively: Boolean by lazy {
        nativeAbiFolder(Build.SUPPORTED_ABIS.firstOrNull()) ==
            File(context.applicationInfo.nativeLibraryDir).name
    }

    override suspend fun read(obj: PointObject): AtomLayer = withContext(Dispatchers.IO) {
        if (!obj.mime.startsWith("image/")) {
            return@withContext AtomLayer(emptyList(), incomplete = "not an image")
        }
        if (!runsNatively) {
            return@withContext AtomLayer(emptyList(), incomplete = FOREIGN_ABI)
        }
        // Кадр для чтения — тот же декодер и тот же перевод координат, что у выделения и
        // замазывания (#1013): ужатие снимка до MAX_PX считается в одном месте и приходит
        // сюда готовым FrameTransform, а не угадывается заново.
        val frame = decodeSelectionFrame(obj.uri.value, MAX_PX)
            ?: return@withContext AtomLayer(emptyList(), incomplete = readerFailure(null))
        val source = frame.bitmap

        try {
            val lines = runCatching { detect(source) }.getOrElse {
                return@withContext AtomLayer(emptyList(), incomplete = it.message ?: "detect failed")
            }
            layerInRawFrame(
                lines.map { box -> box to runCatching { readLine(source, box) }.getOrNull() },
                frame.transform,
            )
        } finally {
            source.recycle()
        }
    }

    /** Детектор DB: карта вероятностей текста → прямоугольники строк. */
    private fun detect(source: Bitmap): List<Box> {
        val scale = minOf(1.0f, DET_SIDE.toFloat() / maxOf(source.width, source.height))
        val width = ((source.width * scale).toInt() / 32).coerceAtLeast(1) * 32
        val height = ((source.height * scale).toInt() / 32).coerceAtLeast(1) * 32
        val small = Bitmap.createScaledBitmap(source, width, height, true)
        val input = normalized(small, DET_MEAN, DET_STD)
        small.recycle()

        val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
        val out = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            detector.run(mapOf(detector.inputNames.first() to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                (result[0].value as Array<Array<Array<FloatArray>>>)[0][0]
            }
        }
        return textRows(blobs(out))
            .map { grown(it, out.firstOrNull()?.size ?: 0, out.size) }
            .map { it.scaled(source.width.toFloat() / width, source.height.toFloat() / height) }
            .sortedWith(compareBy({ it.top }, { it.left }))
    }

    /**
     * Пятна текста: карта вероятностей → связные области → их границы.
     *
     * Разметка волной, а не рекурсией: у наклейки с мелким шрифтом пятен сотни, и рекурсия
     * по пикселям кладёт стек.
     */
    private fun blobs(map: Array<FloatArray>): List<Box> {
        val height = map.size
        val width = map.firstOrNull()?.size ?: return emptyList()
        val seen = Array(height) { BooleanArray(width) }
        val found = mutableListOf<Box>()
        val queue = ArrayDeque<Int>()

        for (y in 0 until height) {
            for (x in 0 until width) {
                if (seen[y][x] || map[y][x] < DET_THRESHOLD) continue
                var left = x
                var right = x
                var top = y
                var bottom = y
                var pixels = 0
                seen[y][x] = true
                queue.addLast(y * width + x)
                while (queue.isNotEmpty()) {
                    val at = queue.removeFirst()
                    val ay = at / width
                    val ax = at % width
                    pixels++
                    left = minOf(left, ax); right = maxOf(right, ax)
                    top = minOf(top, ay); bottom = maxOf(bottom, ay)
                    for (dy in -1..1) for (dx in -1..1) {
                        val ny = ay + dy
                        val nx = ax + dx
                        if (ny in 0 until height && nx in 0 until width &&
                            !seen[ny][nx] && map[ny][nx] >= DET_THRESHOLD
                        ) {
                            seen[ny][nx] = true
                            queue.addLast(ny * width + nx)
                        }
                    }
                }
                if (pixels >= MIN_PIXELS) {
                    found += Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
                }
            }
        }
        return found
    }

    /**
     * Запас вокруг найденной строки.
     *
     * DB обучен на суженной разметке и отдаёт ядро строки — у́же и ниже самих букв. Без
     * запаса верх и низ букв срезало, и распознаватель видел обрубки: «БІЛГОРОД» читалось
     * как «БИЛГОРОД», «БРИТІВКА» — как «БРИТИВКА», а последний знак строки пропадал совсем.
     * Запас считается от высоты строки, чтобы одинаково работать и на заголовке, и на мелком.
     */
    private fun grown(box: Box, width: Int, height: Int): Box {
        val padX = box.height * PAD_X + 1f
        val padY = box.height * PAD_Y + 1f
        return Box(
            (box.left - padX).coerceAtLeast(0f),
            (box.top - padY).coerceAtLeast(0f),
            (box.right + padX).coerceAtMost(width - 1f),
            (box.bottom + padY).coerceAtMost(height - 1f),
        )
    }

    private fun Box.scaled(x: Float, y: Float) = Box(left * x, top * y, right * x, bottom * y)

    /** Распознаватель CTC: кусок строки → текст. */
    private fun readLine(source: Bitmap, box: Box): Reading {
        val left = box.left.toInt().coerceIn(0, source.width - 1)
        val top = box.top.toInt().coerceIn(0, source.height - 1)
        val width = (box.right - box.left).toInt().coerceIn(1, source.width - left)
        val height = (box.bottom - box.top).toInt().coerceIn(1, source.height - top)
        if (width < MIN_LINE_PX || height < MIN_LINE_PX) return Reading("", 0f)

        val crop = Bitmap.createBitmap(source, left, top, width, height)
        val scaled = (REC_HEIGHT * width / height.toFloat()).toInt().coerceIn(MIN_REC_WIDTH, REC_MAX_WIDTH)
        val line = Bitmap.createScaledBitmap(crop, scaled, REC_HEIGHT, true)
        crop.recycle()

        val input = normalized(line, REC_MEAN, REC_STD)
        line.recycle()
        val shape = longArrayOf(1, 3, REC_HEIGHT.toLong(), scaled.toLong())
        val logits = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            reader.run(mapOf(reader.inputNames.first() to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                (result[0].value as Array<Array<FloatArray>>)[0]
            }
        }
        return decode(logits)
    }

    /**
     * CTC: подряд идущие одинаковые знаки схлопываются, нулевой класс — пропуск.
     *
     * Слабый знак не выбрасывается: сомнение — это уверенность строки, а не молчание.
     * По ней [com.point.core.flow.weaklyRead] и решает, честно ли называть это чтением.
     */
    private fun decode(logits: Array<FloatArray>): Reading {
        val text = StringBuilder()
        var previous = -1
        var sum = 0f
        var count = 0
        logits.forEach { step ->
            var best = 0
            var bestValue = step[0]
            for (i in step.indices) {
                if (step[i] > bestValue) { bestValue = step[i]; best = i }
            }
            if (best != 0 && best != previous) {
                if (best == spaceClass) text.append(' ') else alphabet.getOrNull(best - 1)?.let(text::append)
                sum += bestValue
                count++
            }
            previous = best
        }
        return Reading(text.toString().trim(), if (count == 0) 0f else sum / count)
    }

    private fun normalized(bitmap: Bitmap, mean: FloatArray, std: FloatArray): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = FloatArray(3 * width * height)
        val plane = width * height
        for (i in pixels.indices) {
            val p = pixels[i]
            out[i] = (((p shr 16 and 0xFF) / 255f) - mean[0]) / std[0]
            out[plane + i] = (((p shr 8 and 0xFF) / 255f) - mean[1]) / std[1]
            out[2 * plane + i] = (((p and 0xFF) / 255f) - mean[2]) / std[2]
        }
        return out
    }

    private fun session(asset: String): OrtSession {
        val bytes = context.assets.open(asset).use { it.readBytes() }
        return env.createSession(bytes, OrtSession.SessionOptions())
    }

    private companion object {

        const val MAX_PX = 2048

        const val DET_SIDE = 1280

        const val DET_THRESHOLD = 0.3f

        const val MIN_PIXELS = 12

        const val PAD_X = 0.5f

        const val PAD_Y = 0.4f

        const val REC_HEIGHT = 48

        const val REC_MAX_WIDTH = 1600

        // Ниже этого распознавателю нечего свернуть: он сжимает ширину в восемь раз.
        const val MIN_REC_WIDTH = 12

        const val MIN_LINE_PX = 4

        const val FOREIGN_ABI = "ppocr build does not match device abi"

        val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)

        val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        val REC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)

        val REC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)
    }
}

/** Что распознаватель прочёл на одной строке кадра и насколько уверенно. */
internal data class Reading(val text: String, val confidence: Float)

/**
 * Слой чтения живёт в сыром кадре — как у всех читателей (#1013).
 *
 * Детектор и распознаватель видят ужатую копию снимка: длинный скриншот 1080×7200 ложится на
 * неё вдвое меньше. Прежде слой уходил с координатами этой копии и без записи перевода, и
 * всё, что рисует строку поверх кадра, промахивалось: метка поиска вставала вдвое выше
 * найденной строки. Перевод один — тот же FrameTransform, что декодер отдаёт выделению и
 * замазыванию: каждая строка переводится им в сырой кадр, и он же записывается в слой,
 * чтобы строки и дальше собирались в стоячем кадре.
 *
 * Перевод отвечает за место, а не за дробность: единица этого слоя — целая строка, и у
 * значения внутри строки своего атома по-прежнему нет, поэтому область такого значения не
 * выставляется. Это второе проявление #1013 и отдельный от перевода вопрос — сколько знает
 * слой, а не где он лежит.
 *
 * Строка, на которой распознаватель сорвался или промолчал, в слой не попадает.
 */
internal fun layerInRawFrame(lines: List<Pair<Box, Reading?>>, transform: FrameTransform): AtomLayer =
    AtomLayer(
        lines.mapIndexedNotNull { index, (box, reading) ->
            reading?.takeIf { it.text.isNotBlank() }?.let {
                Atom(
                    // Метка слова у всех читателей одна и та же (w47): по ней модель ссылается на
                    // слово страницы, а Point её снимает перед показом человеку. Своя форма
                    // «ppocr-24» правилу снятия незнакома — и метки уезжали на экран.
                    id = atomLabel(index),
                    text = it.text,
                    box = transform.toRaw(box),
                    confidence = it.confidence,
                    reader = READER,
                    readerVersion = VERSION,
                )
            }
        },
        transform = transform,
    )

/**
 * Одна строка — один кусок: пятна, стоящие на общей строке рядом, склеиваются.
 *
 * Номер отправления набран широко, и детектор отдаёт его четырьмя пятнами: «59», «0017»,
 * «2462», «6327». Порознь они и уходили дальше порознь — отсюда «номер обрезан» в #747:
 * следующий шаг брал первый кусок и терял хвост.
 *
 * Склейка идёт до неподвижности: за один проход к «59 0017» ещё не примыкает «2462».
 */
internal fun textRows(blobs: List<Box>): List<Box> {
    var current = blobs
    while (true) {
        val merged = mergedOnce(current)
        if (merged.size == current.size) return merged
        current = merged
    }
}

private fun mergedOnce(blobs: List<Box>): List<Box> {
    val sorted = blobs.sortedWith(compareBy({ it.top }, { it.left }))
    val taken = BooleanArray(sorted.size)
    val out = mutableListOf<Box>()
    for (i in sorted.indices) {
        if (taken[i]) continue
        var box = sorted[i]
        taken[i] = true
        for (j in sorted.indices) {
            if (taken[j] || !sameRow(box, sorted[j])) continue
            box = box.union(sorted[j])
            taken[j] = true
        }
        out += box
    }
    return out
}

/**
 * Общая строка: куски перекрываются по высоте, сравнимы ростом и стоят рядом.
 *
 * Рост важен наравне с соседством: иначе к крупной «3.00» прилипает мелкое «(об'єм)»
 * под ней, а к строке адреса — соседняя графа таблицы.
 */
private fun sameRow(a: Box, b: Box): Boolean {
    val shorter = minOf(a.height, b.height)
    val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
    if (overlap < ROW_OVERLAP * shorter) return false
    if (abs(a.height - b.height) > ROW_SPREAD * maxOf(a.height, b.height)) return false
    return maxOf(a.left, b.left) - minOf(a.right, b.right) <= ROW_GAP * shorter
}

/**
 * Как Android называет папку с библиотеками для этого процессора.
 *
 * Совпала с той, откуда взяты библиотеки, — машина своя; не совпала — работает трансляция.
 */
internal fun nativeAbiFolder(abi: String?): String = when (abi) {
    "arm64-v8a" -> "arm64"
    "armeabi-v7a", "armeabi" -> "arm"
    "x86_64" -> "x86_64"
    "x86" -> "x86"
    else -> ""
}

private const val READER = "ppocr"

private const val VERSION = "v5-mobile-cyrillic"

private const val ROW_OVERLAP = 0.5f

private const val ROW_SPREAD = 0.6f

private const val ROW_GAP = 0.8f
