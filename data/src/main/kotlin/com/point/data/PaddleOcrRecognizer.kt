package com.point.data

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.point.core.flow.Atom
import com.point.core.flow.AtomLayer
import com.point.core.flow.AtomRecognizer
import com.point.core.flow.Box
import com.point.core.flow.readerFailure
import com.point.core.model.PointObject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
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

    override suspend fun read(obj: PointObject): AtomLayer = withContext(Dispatchers.IO) {
        if (!obj.mime.startsWith("image/")) {
            return@withContext AtomLayer(emptyList(), incomplete = "not an image")
        }
        val source = decodeBoundedUpright(obj.uri.value, MAX_PX)
            ?: return@withContext AtomLayer(emptyList(), incomplete = readerFailure(null))

        try {
            val lines = runCatching { detect(source) }.getOrElse {
                return@withContext AtomLayer(emptyList(), incomplete = it.message ?: "detect failed")
            }
            val atoms = lines.mapIndexedNotNull { index, box ->
                val text = runCatching { readLine(source, box) }.getOrNull()?.takeIf { it.isNotBlank() }
                text?.let { Atom(id = "ppocr-$index", text = it, box = box, reader = READER, readerVersion = VERSION) }
            }
            AtomLayer(atoms)
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
        return boxesOf(out, source.width.toFloat() / width, source.height.toFloat() / height)
    }

    /**
     * Строки как прямоугольники: карта вероятностей → связные пятна → их границы.
     *
     * Разметка волной по строкам, а не рекурсией: у наклейки с мелким шрифтом пятен сотни, и
     * рекурсия по пикселям кладёт стек.
     */
    private fun boxesOf(map: Array<FloatArray>, scaleX: Float, scaleY: Float): List<Box> {
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
                if (pixels < MIN_PIXELS) continue

                // Небольшой запас: DB даёт ядро строки, буквы по краям чуть шире.
                val padX = ((right - left) * PAD_SHARE).toInt() + 1
                val padY = ((bottom - top) * PAD_SHARE).toInt() + 1
                found += Box(
                    ((left - padX).coerceAtLeast(0)) * scaleX,
                    ((top - padY).coerceAtLeast(0)) * scaleY,
                    ((right + padX).coerceAtMost(width - 1)) * scaleX,
                    ((bottom + padY).coerceAtMost(height - 1)) * scaleY,
                )
            }
        }
        return found.sortedWith(compareBy({ it.top }, { it.left }))
    }

    /** Распознаватель CTC: кусок строки → текст. */
    private fun readLine(source: Bitmap, box: Box): String {
        val left = box.left.toInt().coerceIn(0, source.width - 1)
        val top = box.top.toInt().coerceIn(0, source.height - 1)
        val width = (box.right - box.left).toInt().coerceIn(1, source.width - left)
        val height = (box.bottom - box.top).toInt().coerceIn(1, source.height - top)
        if (width < MIN_LINE_PX || height < MIN_LINE_PX) return ""

        val crop = Bitmap.createBitmap(source, left, top, width, height)
        val scaled = (REC_HEIGHT * width / height.toFloat()).toInt().coerceIn(REC_HEIGHT, REC_MAX_WIDTH)
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

    /** CTC: подряд идущие одинаковые знаки схлопываются, нулевой класс — пропуск. */
    private fun decode(logits: Array<FloatArray>): String {
        val text = StringBuilder()
        var previous = -1
        logits.forEach { step ->
            var best = 0
            var bestValue = step[0]
            for (i in step.indices) {
                if (step[i] > bestValue) { bestValue = step[i]; best = i }
            }
            if (best != 0 && best != previous && bestValue >= MIN_CONFIDENCE) {
                alphabet.getOrNull(best - 1)?.let(text::append)
            }
            previous = best
        }
        return text.toString().trim()
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

        const val READER = "ppocr"

        const val VERSION = "v5-mobile-cyrillic"

        const val MAX_PX = 2048

        const val DET_SIDE = 960

        const val DET_THRESHOLD = 0.3f

        const val MIN_PIXELS = 12

        const val PAD_SHARE = 0.12f

        const val REC_HEIGHT = 48

        const val REC_MAX_WIDTH = 1600

        const val MIN_LINE_PX = 4

        const val MIN_CONFIDENCE = 0.15f

        val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)

        val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        val REC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)

        val REC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)
    }
}
