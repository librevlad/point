package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Язык, которым два чтения одной ячейки договариваются о том, что́ в них шум, а что значение (#493).
 *
 * Примеры — дословные: слева всё, что телефонный движок отдал в файл владельца.
 */
class ReadingNoiseTest {

    @Test
    fun `края слова, не являющиеся буквой или цифрой, — обрамление движка`() {
        assertEquals("6", trimReadingNoise("[6"))
        assertEquals("8", trimReadingNoise("_8."))
        assertEquals("солдат", trimReadingNoise("солдат'"))
        assertEquals("А4152", trimReadingNoise("А4152_"))
        assertEquals("31.07.2026", trimReadingNoise("(31.07.2026"))
    }

    @Test
    fun `разделитель внутри числа — структура значения, а не шум`() {
        assertEquals("31.07.2026", trimReadingNoise("31.07.2026"))
        assertEquals("1,375", trimReadingNoise("1,375"))
    }

    @Test
    fun `одно значение с разным обрамлением спором не считается`() {
        assertTrue(differsOnlyInNoise("[6", "6."))
        assertTrue(differsOnlyInNoise("_8.", "8."))
        assertTrue(differsOnlyInNoise("солдат'", "солдат"))
        assertTrue(differsOnlyInNoise("(31.07.2026", "31.07.2026"))
    }

    /** Пропавшая точка в дате — другое число, а не снятое обрамление: свёртка это различает. */
    @Test
    fun `съеденный разделитель числа обрамлением не считается`() {
        assertFalse(differsOnlyInNoise("31.07.2026", "31072026"))
        assertFalse(differsOnlyInNoise("1,375", "1375"))
    }

    @Test
    fun `движок ничего не дорисовал — символы остаются его`() {
        assertEquals(
            "20 4514 9154 9395",
            cleanerReading("20 4514 9154 9395", "20 4514-9154-9395"),
        )
    }

    @Test
    fun `движок обрамил слово — оформление берёт тот, кто читает лучше`() {
        assertEquals("6.", cleanerReading("[6", "6."))
        assertEquals("7.", cleanerReading("7,", "7."))
        assertEquals("солдат", cleanerReading("солдат'", "солдат"))
    }

    @Test
    fun `латиница вместо кириллицы — один и тот же номер`() {
        assertTrue(differsOnlyInAlphabet("A0998", "А0998"))
        assertTrue(differsOnlyInAlphabet("Cyмa", "Сума"))
    }

    @Test
    fun `разные глифы алфавитом не складываются — это настоящий спор`() {
        assertFalse(differsOnlyInAlphabet("Karycra", "Капуста"))
        assertFalse(differsOnlyInAlphabet("А0998", "А0999"))
    }

    @Test
    fun `цифры чтения — то, что модель не вправе изменить молча`() {
        assertEquals("310720261", digitsOf("АА 31.07 20261"))
        assertEquals("", digitsOf("солдат'"))
    }
}
