package com.point.data

import com.point.core.flow.AtomCodec
import com.point.core.flow.Box
import com.point.core.flow.FrameTransform
import com.point.core.flow.findOnPage
import com.point.core.flow.sampleSizeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Слой чтения на устройстве лежит в сыром кадре (#1013).
 *
 * Найдено живой охотой: на длинном скриншоте 1080×7200 поиск считал совпадения верно, а метку
 * ставил вдвое выше — слой уходил в координатах ужатой вдвое копии и без записи перевода.
 * Числа ниже — не сочинённые: это слой, снятый с устройства после правки, поделённый обратно
 * на ужатие, то есть ровно то, что распознаватель видит на копии кадра.
 */
class PaddleOcrRawFrameTest {

    private val rawWidth = 1080
    private val rawHeight = 7200

    /** Длинный скриншот владельца: читается ужатым вдвое — тем же правилом, что и показывается. */
    private val readOn = FrameTransform(
        sample = sampleSizeFor(rawWidth, rawHeight, 2048),
        uprightWidth = 540,
        uprightHeight = 3600,
    )

    /** Строки 098–100 на ужатой копии — как их отдал распознаватель на телефоне. */
    private val line98OnCopy = Box(4.21875f, 2910.37525f, 448.59375f, 2931.18725f)
    private val line99OnCopy = Box(7.03125f, 2941.31275f, 448.59375f, 2962.12475f)
    private val line100OnCopy = Box(9.84375f, 2969.43775f, 454.21875f, 2990.24975f)

    private fun read(vararg lines: Pair<Box, Reading?>, on: FrameTransform = readOn) =
        layerInRawFrame(lines.toList(), on)

    @Test
    fun `строка длинного скриншота встаёт в сыром кадре, а не на ужатой копии`() {
        val layer = read(line99OnCopy to Reading("Line 099 order OR-01099 sum 199.99", 0.9f))

        // Числа названы, а не пересчитаны тем же переводом: строка, стоявшая на копии между
        // y = 2941 и y = 2962, в кадре 1080×7200 лежит вдвое ниже — так она и записана в
        // слое, снятом с устройства.
        assertEquals(Box(14.0625f, 5882.6255f, 897.1875f, 5924.2495f), layer.atoms.single().box)

        // То же самое глазами человека: строка 099 из 120 напечатана на 82 % высоты снимка —
        // там теперь и лежит её атом. Прежде слой отдавал 41 %, то есть место строки 049.
        assertEquals(0.8199f, layer.atoms.single().box.centerY / rawHeight, 0.001f)
    }

    /**
     * Путь человека целиком: «Найти в документе» → `OR-01099` → метка на кадре показа.
     *
     * Метка считается тем же переводом, что выделение и замазывание: слой отдаёт место в сыром
     * кадре, кадр показа переводит его в свои координаты. Поэтому доля высоты — одна и та же,
     * как бы сильно показ ни ужимал страницу.
     */
    @Test
    fun `поиск ставит метку на найденную строку, а не вдвое выше — при любом ужатии показа`() {
        val layer = read(
            line98OnCopy to Reading("Line 098 order OR-01098 sum 198.98", 0.9f),
            line99OnCopy to Reading("Line 099 order OR-01099 sum 199.99", 0.9f),
            line100OnCopy to Reading("Line 100 order OR-01100 sum 200.00", 0.9f),
        )

        val found = layer.findOnPage("OR-01099").single()

        // Кадр показа тот же, что кадр чтения (оба ужимают до 2048): метка садится ровно туда,
        // где распознаватель увидел строку 099. Прежде слой уже был в этих координатах, показ
        // ужимал их ещё раз, и метка вставала вдвое выше — на строке 049.
        val shown = FrameTransform(sample = 2, uprightWidth = 540, uprightHeight = 3600)
        assertEquals(Box(7.03125f, 2941.31275f, 448.59375f, 2962.12475f), shown.toUpright(found.region))
        assertEquals(0.8199f, shown.toUpright(found.region).centerY / shown.uprightHeight, 0.001f)

        // Показ ужат вчетверо — числа другие, доля высоты та же: строка 099, а не 049.
        val shownSmaller = FrameTransform(sample = 4, uprightWidth = 270, uprightHeight = 1800)
        assertEquals(
            0.8199f,
            shownSmaller.toUpright(found.region).centerY / shownSmaller.uprightHeight,
            0.001f,
        )
    }

    @Test
    fun `перевод записан в слой и доезжает через файл слоя`() {
        val layer = read(line99OnCopy to Reading("Line 099", 0.9f))

        assertEquals(readOn, layer.transform)
        assertEquals(readOn, AtomCodec.decode(AtomCodec.encode(layer)).transform)
    }

    @Test
    fun `довёрнутый по EXIF снимок переводится тем же доворотом, что и выделение`() {
        val sideways = FrameTransform(sample = 1, rotationDegrees = 90, uprightWidth = 100, uprightHeight = 200)

        val layer = read(Box(10f, 20f, 30f, 40f) to Reading("слово", 0.9f), on = sideways)

        assertEquals(Box(20f, 70f, 40f, 90f), layer.atoms.single().box)
    }

    @Test
    fun `сорвавшаяся или пустая строка в слой не попадает`() {
        val layer = read(
            line98OnCopy to null,
            line100OnCopy to Reading("   ", 0f),
            line99OnCopy to Reading("Line 099 order OR-01099", 0.8f),
        )

        assertEquals(listOf("Line 099 order OR-01099"), layer.atoms.map { it.text })
        assertNull(layer.incomplete)
    }
}
