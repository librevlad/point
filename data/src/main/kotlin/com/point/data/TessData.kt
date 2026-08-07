package com.point.data

import android.content.Context
import android.util.Log
import java.io.File

internal object TessData {

    const val LANG = "rus+eng"

    private const val TAG = "PointOCR"
    private val MODELS = listOf("rus.traineddata", "eng.traineddata")

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
