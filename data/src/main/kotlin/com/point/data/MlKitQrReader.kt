package com.point.data

import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.point.core.flow.QrReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MlKitQrReader(
    private val fallback: QrReader,
) : QrReader {

    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
    }

    override suspend fun decode(imagePath: String): String? = withContext(Dispatchers.IO) {
        val bitmap = decodeBoundedUpright(imagePath, MAX_PX) ?: error(UNREADABLE_IMAGE)
        val viaMlKit = run {
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

        const val MAX_PX = 3072
    }
}
