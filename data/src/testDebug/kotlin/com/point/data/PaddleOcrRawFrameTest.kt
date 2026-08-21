package com.point.data

import com.point.core.flow.AtomCodec
import com.point.core.flow.Box
import com.point.core.flow.FrameTransform
import com.point.core.flow.sampleSizeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Слой чтения на устройстве лежит в сыром кадре (#1013).
 *
 * Найдено живой охотой: на длинном скриншоте 1080×7200 поиск считал совпадения верно, а метку
 * ставил вдвое выше — слой уходил в координатах ужатой вдвое копии и без записи перевода.
 * Числа ниже — с того самого снимка: 120 строк, искомая на 77-й.
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

    /** Строка 077 на ужатой копии — её y в слое из отчёта об ошибке. */
    private val line77OnCopy = Box(14f, 2217f, 498f, 2243f)

    private fun read(vararg lines: Pair<Box, Reading?>, on: FrameTransform = readOn) =
        layerInRawFrame(lines.toList(), on)

    @Test
    fun `строка длинного скриншота встаёт в сыром кадре, а не на ужатой копии`() {
        val layer = read(line77OnCopy to Reading("Line 077 MAGICWORD-ZEBRA order OR-01077", 0.9f))

        val atom = layer.atoms.single()
        assertEquals(readOn.toRaw(line77OnCopy), atom.box)

        // Доля высоты та же, что на копии (0,62), а не вдвое меньше (0,31): метка поиска
        // встаёт на строку 077, а не на 038, как было.
        assertEquals(line77OnCopy.centerY / readOn.uprightHeight, atom.box.centerY / rawHeight, 0.001f)
    }

    @Test
    fun `метка поиска через кадр показа попадает в прочитанную строку при любом ужатии показа`() {
        val layer = read(line77OnCopy to Reading("Line 077", 0.9f))

        // Показ ужат сильнее чтения (вчетверо): строка всё равно на своём месте этого кадра.
        val shownOn = FrameTransform(sample = 4, uprightWidth = 270, uprightHeight = 1800)

        assertEquals(Box(7f, 1108.5f, 249f, 1121.5f), shownOn.toUpright(layer.atoms.single().box))
    }

    @Test
    fun `перевод записан в слой и доезжает через файл слоя`() {
        val layer = read(line77OnCopy to Reading("Line 077", 0.9f))

        assertEquals(readOn, layer.transform)
        assertEquals(readOn, AtomCodec.decode(AtomCodec.encode(layer)).transform)
    }

    @Test
    fun `довёрнутый по EXIF снимок переводится тем же доворотом, что и выделение`() {
        val sideways = FrameTransform(sample = 1, rotationDegrees = 90, uprightWidth = 100, uprightHeight = 200)

        val layer = read(Box(10f, 20f, 30f, 40f) to Reading("слово", 0.9f), on = sideways)

        assertEquals(sideways.toRaw(Box(10f, 20f, 30f, 40f)), layer.atoms.single().box)
        assertEquals(Box(20f, 70f, 40f, 90f), layer.atoms.single().box)
    }

    @Test
    fun `сорвавшаяся или пустая строка в слой не попадает`() {
        val layer = read(
            line77OnCopy to null,
            Box(14f, 2246f, 498f, 2272f) to Reading("   ", 0f),
            Box(14f, 2860f, 498f, 2886f) to Reading("Line 099 order OR-01099", 0.8f),
        )

        assertEquals(listOf("Line 099 order OR-01099"), layer.atoms.map { it.text })
        assertNull(layer.incomplete)
    }
}
