package com.point.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import com.point.core.flow.Atom
import com.point.core.flow.AtomLayer
import com.point.core.flow.AtomRecognizer
import com.point.core.flow.Box
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
) : AtomRecognizer {

    override suspend fun read(obj: PointObject): AtomLayer = withContext(Dispatchers.IO) {
        if (!obj.mime.startsWith("image/")) return@withContext EMPTY
        val bitmap = decodeBounded(obj.uri.value, OCR_MAX_PX)
        if (bitmap == null) {
            Log.w(TAG, "bitmap decode failed: ${obj.mime} @ ${obj.uri.value}")
            return@withContext EMPTY
        }
        val tess = TessBaseAPI()
        try {
            val dataPath = ensureTessData()
            // OEM 1 = LSTM_ONLY — matches the tessdata_fast (LSTM) models.
            val ok = tess.init(dataPath.absolutePath, LANG, 1)
            if (!ok) {
                Log.w(TAG, "Tesseract init failed (dataPath=${dataPath.absolutePath}, lang=$LANG)")
                return@withContext EMPTY
            }
            tess.setImage(bitmap)
            val atoms = words(tess)
            Log.i(TAG, "OCR done: ${atoms.size} words recognised")
            AtomLayer(atoms)
        } catch (e: Throwable) {
            Log.w(TAG, "OCR error", e)
            EMPTY
        } finally {
            runCatching { tess.recycle() }
            bitmap.recycle()
        }
    }

    /**
     * Слова с их местом на странице.
     *
     * Раньше здесь стоял `getUTF8Text()`, и геометрия выбрасывалась на первом же шаге — при том
     * что движок её знает. Из-за этого 14-значный трек, разорванный движком на куски, собрать
     * обратно было нечем: адреса не существовало (#257).
     *
     * **Координаты пока в системе распознанного битмапа**, а не исходного кадра: [decodeBounded]
     * уменьшает длинную сторону и доворачивает по EXIF. Преобразование обратно в сырой кадр
     * (требование ADR-0001 о двух адресных пространствах) ещё не сохраняется — это следующий
     * цикл, и до него кроп по атому нельзя брать из оригинального файла.
     */
    private fun words(tess: TessBaseAPI): List<Atom> {
        val iterator = tess.resultIterator ?: return emptyList()
        val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
        val atoms = mutableListOf<Atom>()
        iterator.begin()
        do {
            val text = iterator.getUTF8Text(level)?.trim().orEmpty()
            val rect = iterator.getBoundingRect(level)
            if (text.isNotEmpty() && rect != null) {
                atoms += Atom(
                    id = "w${atoms.size}",
                    text = text,
                    box = Box(
                        rect.left.toFloat(),
                        rect.top.toFloat(),
                        rect.right.toFloat(),
                        rect.bottom.toFloat(),
                    ),
                )
            }
        } while (iterator.next(level))
        return atoms
    }

    /**
     * Bounded decode so OCR of a huge photo can't OOM (#18): subsample the long edge to
     * under 2×[maxPx], then rotate upright by EXIF (see [uprightByExif]).
     */
    private fun decodeBounded(path: String, maxPx: Int): Bitmap? {
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
        return uprightByExif(decoded, path)
    }

    /**
     * A phone photo stores its rotation in EXIF and keeps the pixels sideways; [BitmapFactory]
     * ignores the tag and hands back those sideways pixels. Tesseract has no orientation model in
     * this path, so sideways lines decode to **deterministic gibberish** (`©`, `=`, stray 1–2 char
     * fragments) — *the* reason on-device OCR of a document photo returns junk rather than the text.
     * Rotating to upright first is the real fix; the cloud fallback was only masking it.
     */
    private fun uprightByExif(bitmap: Bitmap, path: String): Bitmap {
        val orientation = runCatching {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true,
        )
        if (rotated != bitmap) bitmap.recycle()
        return rotated
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
        const val OCR_MAX_PX = 2048 // enough for legible text; bounds memory on huge photos (#18)
        val MODELS = listOf("rus.traineddata", "eng.traineddata")
        val EMPTY = AtomLayer(emptyList())
    }
}
