package com.point.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Модели Tesseract на диске: движку нужен настоящий каталог `tessdata/`, а модели живут в assets.
 *
 * Отдельным файлом это стало, когда читателей движка было два — страница целиком
 * ([TesseractTextRecognizer]) и табло прибора. Второго больше нет (#396: кнопку чтения показания
 * убрали, замена — #426), читатель снова один, и файл остаётся ровно затем же: список моделей
 * живёт в одном месте, а не расходится молча по копиям процедуры.
 */
internal object TessData {

    const val LANG = "rus+eng"

    private const val TAG = "PointOCR"
    private val MODELS = listOf("rus.traineddata", "eng.traineddata")

    /** @return каталог, КОТОРЫЙ СОДЕРЖИТ `tessdata/` — именно его ждёт `TessBaseAPI.init`. */
    fun ensure(context: Context): File {
        val base = File(context.filesDir, "tesseract")
        val tessdata = File(base, "tessdata").apply { mkdirs() }
        for (name in MODELS) {
            val out = File(tessdata, name)
            if (out.exists() && out.length() > 0) continue
            context.assets.open("tessdata/$name").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            Log.i(TAG, "copied model $name (${out.length()} bytes)")
        }
        return base
    }
}
