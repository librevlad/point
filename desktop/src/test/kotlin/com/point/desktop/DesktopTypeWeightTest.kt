package com.point.desktop

import com.point.desktop.ui.DesktopFonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopTypeWeightTest {

    /**
     * Текст ПК рисуется тем весом, который выбрал дизайн (#626).
     *
     * Прежде ПК писал переменным Manrope, у которого ось веса по умолчанию стоит на 200, — и весь
     * текст выходил ExtraLight, пока ось не вели явно. Ubuntu из макета 19.10.2026 лежит файлом на
     * каждый вес: здесь проверяется, что файл каждой ступени и правда того веса, за который его
     * берёт окно.
     */
    @Test
    fun `каждый вес окна — файл того самого веса`() {
        assertEquals("обычный текст", 400, DesktopFonts.face(DesktopFonts.REGULAR).fontStyle.weight)
        assertEquals("имена строк", 500, DesktopFonts.face(DesktopFonts.MEDIUM).fontStyle.weight)
        assertEquals("заголовки", 700, DesktopFonts.face(DesktopFonts.BOLD).fontStyle.weight)
        assertEquals("моно-строки", 400, DesktopFonts.face(DesktopFonts.MONO).fontStyle.weight)
    }

    /** Лицензия шрифта едет вместе с ним — так требует Ubuntu Font Licence. */
    @Test
    fun `лицензия шрифта лежит рядом с файлами`() {
        val licence = DesktopFonts::class.java.getResourceAsStream("/fonts/UFL.txt")?.use { it.readBytes() }

        assertTrue("UFL.txt нет в ресурсах окна", licence != null && licence.isNotEmpty())
    }

    /** Окно пишет по-русски: шрифт обязан знать кириллицу, а не подменять её чужим. */
    @Test
    fun `шрифт окна знает кириллицу`() {
        listOf(DesktopFonts.REGULAR, DesktopFonts.MEDIUM, DesktopFonts.BOLD, DesktopFonts.MONO).forEach { file ->
            val glyphs = DesktopFonts.face(file).getStringGlyphs("Объект ЁёЇїЄєІі")
            assertTrue("в $file нет кириллицы", glyphs.none { it.toInt() == 0 })
        }
    }
}
