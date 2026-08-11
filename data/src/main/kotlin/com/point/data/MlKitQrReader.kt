package com.point.data

import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.point.core.flow.CodeKind
import com.point.core.flow.QrReader
import com.point.core.flow.ScannedCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MlKitQrReader(
    private val fallback: QrReader,
) : QrReader {

    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_ITF,
                )
                .build(),
        )
    }

    override suspend fun decode(imagePath: String): String? = scan(imagePath)?.text

    override suspend fun scan(imagePath: String): ScannedCode? = withContext(Dispatchers.IO) {
        val bitmap = decodeBoundedUpright(imagePath, MAX_PX) ?: error(UNREADABLE_IMAGE)
        val viaMlKit = run {
            try {
                scanner.process(InputImage.fromBitmap(bitmap, 0)).await()
                    .firstNotNullOfOrNull { found ->
                        found.rawValue?.takeIf(String::isNotBlank)?.let { ScannedCode(it, kindOf(found.format)) }
                    }
            } catch (_: Exception) {
                null
            } finally {
                bitmap.recycle()
            }
        }
        viaMlKit ?: fallback.scan(imagePath)
    }

    private fun kindOf(format: Int): CodeKind =
        if (format == Barcode.FORMAT_QR_CODE) CodeKind.QR else CodeKind.PRODUCT

    private companion object {

        const val MAX_PX = 3072
    }
}
