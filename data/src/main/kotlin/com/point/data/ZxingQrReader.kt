package com.point.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.point.core.flow.CodeKind
import com.point.core.flow.QrReader
import com.point.core.flow.ScannedCode
import com.point.core.flow.readerFailure
import com.point.core.model.ObjectKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ZxingQrReader @Inject constructor() : QrReader {

    override suspend fun decode(imagePath: String): String? = scan(imagePath)?.text

    override suspend fun scan(imagePath: String): ScannedCode? = withContext(Dispatchers.IO) {
        val bitmap = decodeBoundedUpright(imagePath, MAX_PX) ?: com.point.core.flow.ownWords(UNREADABLE_IMAGE)

        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val binary = BinaryBitmap(HybridBinarizer(source))
            val hints = mapOf(
                DecodeHintType.POSSIBLE_FORMATS to (listOf(BarcodeFormat.QR_CODE) + PRODUCT_FORMATS),
                DecodeHintType.TRY_HARDER to true,
            )
            runCatching { MultiFormatReader().decode(binary, hints) }.getOrNull()?.let { found ->
                ScannedCode(
                    found.text,
                    if (found.barcodeFormat in PRODUCT_FORMATS) CodeKind.PRODUCT else CodeKind.QR,
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {

        const val MAX_PX = 2048

        /**
         * Коды товаров и книг (#445). ISBN — это тот же EAN-13, отдельного формата у него нет.
         * Список ровно такой: читатель уже построен, менялся только он.
         */
        val PRODUCT_FORMATS = listOf(
            BarcodeFormat.EAN_13,
            BarcodeFormat.EAN_8,
            BarcodeFormat.UPC_A,
            BarcodeFormat.UPC_E,
            BarcodeFormat.CODE_128,
            BarcodeFormat.ITF,
        )
    }
}

// Тот же факт, что и у распознавания текста (#686): «файл не открылся» — одними
// словами, чтобы дедуп в failedNote() схлопывал совпавшие причины, а не печатал
// одну беду дважды. Читатель кодов получает путь к снимку, а не объект, поэтому вид
// здесь задан контрактом — изображение (#1033). Сигнал назван: байты не разобрались в
// снимок, и это про сам объект — молчанием такое не передать (#1258).
internal val UNREADABLE_IMAGE = readerFailure(com.point.core.flow.READER_NOT_DECODED, ObjectKind.IMAGE)
