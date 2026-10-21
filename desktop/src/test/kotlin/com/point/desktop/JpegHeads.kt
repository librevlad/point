package com.point.desktop

import java.nio.ByteBuffer

/* Заголовки снимков для тестов (#1379): JPEG от ImageIO со вставленной записью «Exif». */

/** APP1 «Exif» за SOI и JFIF — так его кладут и камеры, пишущие оба заголовка. */
internal fun spliced(jpeg: ByteArray, tiff: ByteArray): ByteArray {
    val body = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
    val length = body.size + 2
    val jfif = jpeg[3].toInt() and 0xFF == 0xE0
    val at = if (jfif) 4 + (((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF)) else 2
    return jpeg.copyOfRange(0, at) +
        byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) +
        body + jpeg.copyOfRange(at, jpeg.size)
}

/** Поворот 6 и GPS 50°27′0″ N, 30°31′12″ E. */
internal fun turnedPlacedExif(): ByteArray {
    val b = ByteBuffer.allocate(140)
    b.put("MM".toByteArray()).putShort(42).putInt(8)

    b.putShort(2)
    b.putShort(0x0112).putShort(3).putInt(1).putShort(6).putShort(0)
    b.putShort(0x8825.toShort()).putShort(4).putInt(1).putInt(38)
    b.putInt(0)

    b.putShort(4)
    b.putShort(1).putShort(2).putInt(2).put('N'.code.toByte()).put(0.toByte()).putShort(0)
    b.putShort(2).putShort(5).putInt(3).putInt(92)
    b.putShort(3).putShort(2).putInt(2).put('E'.code.toByte()).put(0.toByte()).putShort(0)
    b.putShort(4).putShort(5).putInt(3).putInt(116)
    b.putInt(0)

    for ((d, m, s) in listOf(Triple(50, 27, 0), Triple(30, 31, 12))) {
        b.putInt(d).putInt(1).putInt(m).putInt(1).putInt(s).putInt(1)
    }
    return b.array()
}
