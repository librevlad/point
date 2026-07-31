package com.point.core.flow

/**
 * Обратный путь из координат прочитанного битмапа в координаты **сырого** кадра.
 *
 * Существует, потому что перед OCR снимок уменьшают: длинная сторона режется, чтобы большое фото
 * не уронило приложение по памяти (#18). Слово после этого знает своё место в уменьшенной копии,
 * а перечитывать сомнительное значение мы пойдём в исходный файл — и кроп по координатам копии
 * покажет не то место.
 *
 * Вторая половина разрыва — доворот. Телефон хранит поворот в EXIF и отдаёт пиксели боком; перед
 * OCR снимок доворачивают, иначе движок читает боковые строки как мусор. После этого координаты
 * живут в повёрнутой копии, а исходный файл остался боком.
 *
 * ADR-0001 требует держать оба пространства сразу: сырое как основное, преобразование рядом.
 *
 * @param sample во сколько раз уменьшили длинную сторону (`inSampleSize`); 1 — не уменьшали.
 * @param rotationDegrees на сколько довернули по часовой стрелке: 0, 90, 180 или 270.
 * @param uprightWidth ширина довёрнутой копии — та, в системе которой пришли координаты.
 * @param uprightHeight высота довёрнутой копии.
 */
data class FrameTransform(
    val sample: Int,
    val rotationDegrees: Int = 0,
    val uprightWidth: Int = 0,
    val uprightHeight: Int = 0,
) {

    init {
        require(sample >= 1) { "sample must be >= 1, was $sample" }
        require(rotationDegrees in ROTATIONS) { "rotation must be one of $ROTATIONS, was $rotationDegrees" }
    }

    /** Место [box] в сыром кадре: сперва отменяем доворот, затем возвращаем масштаб. */
    fun toRaw(box: Box): Box = unrotate(box).scaled()

    /**
     * Обратная дорога: место сырого [box] в довёрнутой копии. Нужна экрану выделения (#259):
     * атомы живут в сыром кадре, показывается человеку EXIF-выпрямленная копия — подсветка
     * захвата без обратного преобразования рисовалась бы мимо слов. Держится зеркалом [toRaw]:
     * `toUpright(toRaw(b)) == b`, и тест закрепляет именно круговой путь.
     */
    fun toUpright(box: Box): Box = rotate(box.unscaled())

    private fun unrotate(box: Box): Box {
        val w = uprightWidth.toFloat()
        val h = uprightHeight.toFloat()
        return when (rotationDegrees) {
            90 -> Box(box.top, w - box.right, box.bottom, w - box.left)
            180 -> Box(w - box.right, h - box.bottom, w - box.left, h - box.top)
            270 -> Box(h - box.bottom, box.left, h - box.top, box.right)
            else -> box
        }
    }

    private fun rotate(box: Box): Box {
        val w = uprightWidth.toFloat()
        val h = uprightHeight.toFloat()
        return when (rotationDegrees) {
            90 -> Box(w - box.bottom, box.left, w - box.top, box.right)
            180 -> Box(w - box.right, h - box.bottom, w - box.left, h - box.top)
            270 -> Box(box.top, h - box.right, box.bottom, h - box.left)
            else -> box
        }
    }

    private fun Box.scaled(): Box =
        Box(left * sample, top * sample, right * sample, bottom * sample)

    private fun Box.unscaled(): Box =
        Box(left / sample, top / sample, right / sample, bottom / sample)

    private companion object {
        val ROTATIONS = setOf(0, 90, 180, 270)
    }
}
