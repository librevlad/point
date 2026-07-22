package com.point.data

import android.content.Context
import android.graphics.BitmapFactory
import com.googlecode.tesseract.android.TessBaseAPI
import com.point.core.flow.TextRecognizer
import com.point.core.model.PointObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * On-device OCR via Tesseract (tess-two). The `rus`/`eng` models are
 * bundled in assets and copied into filesDir on first use (Tesseract needs a
 * real `tessdata/` directory on disk). Thin native glue — the OCR decision logic
 * lives in the tested OcrRealizer; a failure here returns blank so the realizer
 * can fall back to the cloud LLM.
 */
class TesseractTextRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) : TextRecognizer {

    override suspend fun recognize(obj: PointObject): String = withContext(Dispatchers.IO) {
        if (!obj.mime.startsWith("image/")) return@withContext ""
        val bitmap = BitmapFactory.decodeFile(obj.uri.value) ?: return@withContext ""
        val dataPath = ensureTessData()
        val tess = TessBaseAPI()
        try {
            if (!tess.init(dataPath.absolutePath, LANG)) return@withContext ""
            tess.setImage(bitmap)
            tess.getUTF8Text()?.trim().orEmpty()
        } catch (e: Exception) {
            ""
        } finally {
            runCatching { tess.end() }
            bitmap.recycle()
        }
    }

    /** @return the dir that CONTAINS `tessdata/` (what TessBaseAPI.init expects). */
    private fun ensureTessData(): File {
        val base = File(context.filesDir, "tesseract")
        val tessdata = File(base, "tessdata").apply { mkdirs() }
        for (name in MODELS) {
            val out = File(tessdata, name)
            if (out.exists() && out.length() > 0) continue
            context.assets.open("tessdata/$name").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        return base
    }

    private companion object {
        const val LANG = "rus+eng"
        val MODELS = listOf("rus.traineddata", "eng.traineddata")
    }
}
