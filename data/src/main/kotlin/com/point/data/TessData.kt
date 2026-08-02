package com.point.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Модели Tesseract на диске: движку нужен настоящий каталог `tessdata/`, а модели живут в assets.
 *
 * Отдельным файлом, потому что читателей движка стало два — страница целиком
 * ([TesseractTextRecognizer]) и табло прибора ([TesseractMeterReader]). Копия этой процедуры во
 * втором читателе означала бы два места, где однажды разойдётся список моделей: один читает
 * `rus+eng`, второй молча остаётся с одним языком, и разбираться в этом будут по симптому.
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
