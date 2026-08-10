package com.point.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.point.core.flow.QrReader
import com.point.core.flow.readerFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ZxingQrReader @Inject constructor() : QrReader {

    override suspend fun decode(imagePath: String): String? = withContext(Dispatchers.IO) {
        val bitmap = decodeBoundedUpright(imagePath, MAX_PX) ?: error(UNREADABLE_IMAGE)

        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val binary = BinaryBitmap(HybridBinarizer(source))
            val hints = mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            )
            runCatching { MultiFormatReader().decode(binary, hints).text }.getOrNull()
        } finally {
            bitmap.recycle()
        }
    }

    private companion object { const val MAX_PX = 2048 }
}

// Тот же факт, что и у распознавания текста (#686): «файл не открылся» — одними
// словами, чтобы дедуп в failedNote() схлопывал совпавшие причины, а не печатал
// одну беду дважды.
internal val UNREADABLE_IMAGE = readerFailure(null)
