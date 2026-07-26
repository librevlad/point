package com.point.desktop.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage

/** The pairing payload as a QR the phone can photograph — zxing core, no Android. */
fun qrImage(payload: String, size: Int = 240): ImageBitmap {
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until size) {
        for (y in 0 until size) {
            image.setRGB(x, y, if (matrix.get(x, y)) 0x000000 else 0xFFFFFF)
        }
    }
    return image.toComposeImageBitmap()
}
