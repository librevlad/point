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
import com.point.core.flow.FrameTransform
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
        val frame = decodeBounded(obj.uri.value, OCR_MAX_PX)
        if (frame == null) {
            Log.w(TAG, "bitmap decode failed: ${obj.mime} @ ${obj.uri.value}")
            return@withContext EMPTY
        }
        val bitmap = frame.bitmap
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
            val toRawFrame = FrameTransform(
                sample = frame.sample,
                rotationDegrees = frame.rotation,
                uprightWidth = bitmap.width,
                uprightHeight = bitmap.height,
            )
            // Сперва текст движка, потом итератор: распознавание запускается первым обращением,
            // и порядок гарантирует, что итератор работает по уже готовому результату.
            val engineText = tess.getUTF8Text()?.trim().orEmpty()
            val atoms = words(tess, toRawFrame)
            Log.i(TAG, "OCR done: ${atoms.size} words, ${engineText.length} chars")
            AtomLayer(atoms, readerText = engineText.ifEmpty { null })
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
     * Координаты приводятся к **сырому кадру** через [toRawFrame]: [decodeBounded] уменьшает
     * длинную сторону и доворачивает по EXIF, поэтому движок отдаёт места в системе изменённой
     * копии, а перечитывать сомнительное значение мы пойдём в исходный файл (ADR-0001, два
     * адресных пространства).
     */
    private fun words(tess: TessBaseAPI, toRawFrame: FrameTransform): List<Atom> {
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
     * только текст. Теперь наружу уходят координаты, и без этих двух чисел их не вернуть в
     * систему исходного файла.
     */
    private class Decoded(val bitmap: Bitmap, val sample: Int, val rotation: Int)

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
