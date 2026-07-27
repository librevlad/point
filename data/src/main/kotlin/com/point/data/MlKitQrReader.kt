package com.point.data

import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.point.core.flow.QrReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * On-device QR read via ML Kit Barcode Scanning (real-device feedback: ZXing's whole-image
 * decode misses a small/angled QR inside a photo; ML Kit localises it at multiple scales).
 * ZXing stays as the fallback so nothing that already worked regresses. Bundled model — no
 * download, works offline immediately. Constructed via @Provides in DataModule so Dagger's
 * KSP aggregation never resolves the ML Kit AAR types (same fix as the entity extractor).
 */
class MlKitQrReader(
    private val fallback: QrReader,
) : QrReader {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )

    override suspend fun decode(imagePath: String): String? = withContext(Dispatchers.IO) {
        val bitmap = decodeBoundedUpright(imagePath, MAX_PX)
        val viaMlKit = if (bitmap == null) {
            null
        } else {
            try {
                scanner.process(InputImage.fromBitmap(bitmap, 0)).await()
                    .firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }
            } catch (_: Exception) {
                null
            } finally {
                bitmap.recycle()
            }
        }
        viaMlKit ?: fallback.decode(imagePath)
    }

    private companion object {
        // A little more resolution than ZXing's default — ML Kit locates a small QR better
        // with more pixels, and its finder is scale-robust so the cost stays bounded.
        const val MAX_PX = 3072
    }
}
