package com.point.core.flow

/**
 * Замазать на снимке то, что человек не хотел отдавать (#549).
 *
 * Содержимое именно **заменяется**, а не закрывается слоем сверху: блок пикселей становится
 * своим средним цветом, и из результата уже нечего восстанавливать. Слой поверх выглядел бы
 * так же, но снимался бы одной командой — это обещание безопасности, которого он не даёт.
 *
 * Чистая логика над пикселями: ни Android, ни файлов. Декодированием и записью занимается
 * исполнитель, решает — этот код.
 */
object Redaction {

    /** Сколько блоков ложится на короткую сторону замазанного места: мельче — читаемее. */
    const val BLOCKS_ACROSS = 3

    /**
     * Пиксели [pixels] (ARGB, построчно) на месте каждой из [places] заменяются блочным
     * средним. Координаты мест — в пикселях изображения; выход за края обрезается.
     */
    fun hide(pixels: IntArray, width: Int, height: Int, places: List<Box>) {
        places.forEach { place -> hideOne(pixels, width, height, place) }
    }

    private fun hideOne(pixels: IntArray, width: Int, height: Int, place: Box) {
        val left = place.left.toInt().coerceIn(0, width)
        val right = kotlin.math.ceil(place.right).toInt().coerceIn(0, width)
        val top = place.top.toInt().coerceIn(0, height)
        val bottom = kotlin.math.ceil(place.bottom).toInt().coerceIn(0, height)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return

        // Не меньше двух пикселей: блок в один пиксель ничего не заменяет, и узкая полоска
        // осталась бы читаемой ровно там, где человек её и замазывал.
        val block = maxOf(2, minOf(w, h) / BLOCKS_ACROSS)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                flatten(pixels, width, x, y, minOf(x + block, right), minOf(y + block, bottom))
                x += block
            }
            y += block
        }
    }

    private fun flatten(pixels: IntArray, width: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        var a = 0L
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val p = pixels[y * width + x]
                a += (p ushr 24) and 0xFF
                r += (p ushr 16) and 0xFF
                g += (p ushr 8) and 0xFF
                b += p and 0xFF
                n++
            }
        }
        if (n == 0) return
        val flat = (((a / n).toInt() and 0xFF) shl 24) or
            (((r / n).toInt() and 0xFF) shl 16) or
            (((g / n).toInt() and 0xFF) shl 8) or
            ((b / n).toInt() and 0xFF)
        for (y in y0 until y1) {
            for (x in x0 until x1) pixels[y * width + x] = flat
        }
    }
}

/**
 * Исполнитель замазывания: читает снимок, заменяет содержимое показанных мест и отдаёт
 * новый. Исходник не трогается — Point рождает новый объект, а не переписывает принесённое.
 */
interface ImageRedactor {

    suspend fun hide(imagePath: String, places: List<Box>): EvidenceImage?
}
