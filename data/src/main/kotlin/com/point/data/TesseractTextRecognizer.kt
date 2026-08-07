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

        val ready = preparedBitmap(decoded.bitmap, knownTextHeightPx(obj))
        val bitmap = ready.frame

        if (bitmap !== decoded.bitmap) decoded.bitmap.recycle()
        val frame = Decoded(bitmap, decoded.sample, decoded.rotation, ready.scale)
        if (ready.upscaled) Log.i(TAG, "frame upscaled x${ready.scale} -> ${bitmap.width}x${bitmap.height}")

        var probe: Bitmap? = null
        val tess = TessBaseAPI()
        try {
            val dataPath = TessData.ensure(context)

            val ok = tess.init(dataPath.absolutePath, LANG, 1)
            if (!ok) {
                Log.w(TAG, "Tesseract init failed (dataPath=${dataPath.absolutePath}, lang=$LANG)")
                return@withContext done(AtomLayer(emptyList(), incomplete = "engine init failed"), budget)
            }
            val version = runCatching { tess.version ?: "" }.getOrDefault("")

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

    private fun done(layer: AtomLayer, budget: ReadingBudget): AtomLayer {
        Log.i(TAG, ocrDoneLine(layer, budget.spentMs()))
        return layer
    }

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

            if (fired.get() && hocr == null) return CappedRead(EMPTY, cut = true)
            val toRawFrame = FrameTransform(
                sample = frame.sample * sampleFactor,
                rotationDegrees = (frame.rotation + extraRotation) % 360,
                uprightWidth = image.width,
                uprightHeight = image.height,

                upscale = frame.upscale,
            )

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

    private fun Bitmap.rotated(degrees: Int): Bitmap =
        if (degrees % 360 == 0) {
            this
        } else {
            Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        }

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

                    confidence = (iterator.confidence(level) / 100f).coerceIn(0f, 1f),
                    reader = READER,
                    readerVersion = version,
                    page = 0,
                )
            }
        } while (iterator.next(level))
        return atoms
    }

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

    private fun uprightByExif(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(degrees.toFloat()) }, true,
        )
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

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
        const val OCR_MAX_PX = 2048

        const val PROBE_HALF_MIN_EDGE = 1600

        val EMPTY = AtomLayer(emptyList())

        const val READER = "tesseract"
    }
}
