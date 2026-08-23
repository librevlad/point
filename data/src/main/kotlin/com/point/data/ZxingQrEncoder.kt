package com.point.data

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
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
import kotlin.math.max

class ZxingQrEncoder @Inject constructor(
    private val store: ObjectStore,
) : QrEncoder {

    override suspend fun encode(text: String): ScratchRef = withContext(Dispatchers.IO) {
        val matrix = try {
            qrBitMatrix(text)
        } catch (e: WriterException) {
            // Человеку — свои слова, вендорский текст остаётся в журнале (#686, #1084):
            // «Data too big» по-английски ничего ему не объясняет.
            Log.w(TAG, "qr encode failed for ${text.toByteArray(Charsets.UTF_8).size} bytes", e)
            throw e
        }
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
}

/**
 * Код, каким его рисует телефон.
 *
 * Отдельно от картинки, потому что именно эта строка решает, какой текст телефон действительно
 * берёт: общий потолок [com.point.core.flow.QR_MAX_BYTES] обязан совпадать с ней, иначе на самом
 * верху человек получит отказ библиотеки вместо честного предела (#1084). Совпадение проверяется
 * тестом, а не обещанием.
 *
 * Первый проход отвечает на один вопрос — сколько в коде модулей. Длину текста в версию переводит
 * сама библиотека, узнать это заранее нечем, а знать нужно: длинный текст берёт сороковую версию
 * со ста семьюдесятью семью модулями, и в прежние шестьсот сорок пикселей модуль укладывался в
 * три точки — такой код камерой уже не читается. Второй проход рисует так, чтобы на модуль
 * пришлось не меньше [MODULE_PX] пикселей — столько же, сколько на компьютере.
 */
internal fun qrBitMatrix(text: String): BitMatrix {
    val modules = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, QR_HINTS)
    val side = max(SIZE, modules.width * MODULE_PX)
    return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, side, side, QR_HINTS)
}

private val QR_HINTS = mapOf(
    EncodeHintType.CHARACTER_SET to "UTF-8",
    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    EncodeHintType.MARGIN to 2,
)

private const val SIZE = 640

/** Пикселей на модуль у длинного кода — столько же, сколько рисует компьютер (#1084). */
private const val MODULE_PX = 8

private const val TAG = "ZxingQrEncoder"
