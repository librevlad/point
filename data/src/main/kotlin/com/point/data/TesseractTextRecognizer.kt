package com.point.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import com.point.core.flow.TextRecognizer
import com.point.core.model.PointObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * On-device OCR via Tesseract 5 (tesseract4android). The `rus`/`eng` models are bundled in
 * assets and copied into filesDir on first use (Tesseract needs a real
 * `tessdata/` directory on disk). The models are LSTM-only (tessdata_fast) so the
 * engine is forced to OEM_LSTM_ONLY. Thin native glue — the OCR decision logic
 * lives in the tested OcrRealizer; a failure here returns blank so the realizer
 * can fall back to the cloud LLM. Diagnostics go to logcat under "PointOCR".
 */
class TesseractTextRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) : TextRecognizer {

    override suspend fun recognize(obj: PointObject): String = withContext(Dispatchers.IO) {
        if (!obj.mime.startsWith("image/")) return@withContext ""
        val bitmap = BitmapFactory.decodeFile(obj.uri.value)
        if (bitmap == null) {
            Log.w(TAG, "bitmap decode failed: ${obj.mime} @ ${obj.uri.value}")
            return@withContext ""
        }
        val tess = TessBaseAPI()
        try {
            val dataPath = ensureTessData()
            // OEM 1 = LSTM_ONLY — matches the tessdata_fast (LSTM) models.
            val ok = tess.init(dataPath.absolutePath, LANG, 1)
            if (!ok) {
                Log.w(TAG, "Tesseract init failed (dataPath=${dataPath.absolutePath}, lang=$LANG)")
                return@withContext ""
            }
            tess.setImage(bitmap)
            val text = tess.getUTF8Text()?.trim().orEmpty()
            Log.i(TAG, "OCR done: ${text.length} chars recognised")
            text
        } catch (e: Throwable) {
            Log.w(TAG, "OCR error", e)
            ""
        } finally {
            runCatching { tess.recycle() }
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
            Log.i(TAG, "copied model $name (${out.length()} bytes)")
        }
        return base
    }

    private companion object {
        const val TAG = "PointOCR"
        const val LANG = "rus+eng"
        val MODELS = listOf("rus.traineddata", "eng.traineddata")
    }
}
