package com.point.data

import com.point.core.flow.Box
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Сборка строк из пятен детектора (#747).
 *
 * Числа взяты с почтовой наклейки Нова Пошта в масштабе карты вероятностей: номер
 * отправления набран широко, и детектор видит там не одну строку, а четыре пятна.
 */
class PaddleOcrRowsTest {

    @Test
    fun `номер отправления собирается в одну строку, а не в четыре обрывка`() {
        val pieces = listOf(
            Box(10f, 100f, 40f, 120f),
            Box(55f, 100f, 110f, 120f),
            Box(125f, 100f, 180f, 120f),
            Box(195f, 100f, 250f, 120f),
        )

        assertEquals(listOf(Box(10f, 100f, 250f, 120f)), textRows(pieces))
    }

    @Test
    fun `соседняя строка снизу отдельна`() {
        val pieces = listOf(Box(10f, 100f, 100f, 120f), Box(10f, 140f, 100f, 160f))

        assertEquals(2, textRows(pieces).size)
    }

    @Test
    fun `мелкая подпись не прилипает к крупному числу рядом`() {
        val big = Box(10f, 100f, 100f, 160f)
        val small = Box(120f, 140f, 200f, 158f)

        assertEquals(2, textRows(listOf(big, small)).size)
    }

    @Test
    fun `дальняя графа таблицы остаётся своей строкой`() {
        val pieces = listOf(Box(10f, 100f, 100f, 120f), Box(300f, 100f, 400f, 120f))

        assertEquals(2, textRows(pieces).size)
    }

    @Test
    fun `склейка идёт до неподвижности, а не за один проход`() {
        // Порядок нарочно обратный: цепочка собирается только повторным проходом.
        val pieces = listOf(
            Box(195f, 100f, 250f, 120f),
            Box(125f, 100f, 180f, 120f),
            Box(55f, 100f, 110f, 120f),
            Box(10f, 100f, 40f, 120f),
        )

        assertEquals(listOf(Box(10f, 100f, 250f, 120f)), textRows(pieces))
    }

    @Test
    fun `пусто на входе — пусто на выходе`() {
        assertEquals(emptyList<Box>(), textRows(emptyList()))
    }

    @Test
    fun `имя папки с библиотеками — по процессору устройства`() {
        assertEquals("arm64", nativeAbiFolder("arm64-v8a"))
        assertEquals("arm", nativeAbiFolder("armeabi-v7a"))
        assertEquals("x86_64", nativeAbiFolder("x86_64"))
    }

    @Test
    fun `неизвестный процессор не выдаётся за свой`() {
        // Пустая строка не совпадёт ни с одной настоящей папкой — значит, движок не берётся.
        assertEquals("", nativeAbiFolder(null))
        assertEquals("", nativeAbiFolder("riscv64"))
    }
}
