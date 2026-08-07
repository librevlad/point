package com.point.data

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.point.core.flow.ObjectStore
import com.point.core.flow.QrEncoder
import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class ZxingQrEncoder @Inject constructor(
    private val store: ObjectStore,
) : QrEncoder {

    override suspend fun encode(text: String): ScratchRef = withContext(Dispatchers.IO) {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, SIZE, SIZE, hints)
        val bitmap = matrix.toBitmap()
        val ref = store.newScratchFile("png")
        File(ref.value).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        ref
    }

    private fun BitMatrix.toBitmap(): Bitmap {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) pixels[offset + x] = if (this[x, y]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            .apply { setPixels(pixels, 0, width, 0, 0, width, height) }
    }

    private companion object { const val SIZE = 640 }
}
