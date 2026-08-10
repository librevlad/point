package com.point.desktop

import com.point.desktop.ui.manropeFace
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopTypeWeightTest {

    @Test
    fun `шрифт ПК рисуется тем весом, который выбрал дизайн, а не осью по умолчанию (#626)`() {
        // Manrope — variable-шрифт с осью 200…800, и её значение по умолчанию — 200.
        // Пока ПК не вёл ось, весь его текст выходил ExtraLight: это и есть «слишком тонкие
        // шрифты на ПК». Телефон уводил ось всегда (core:ui Type.kt) — оттого и расхождение.
        assertEquals("основной текст обязан быть обычного веса", 400, manropeFace(400).fontStyle.weight)
        assertEquals("мелкое — на ступень плотнее", 500, manropeFace(500).fontStyle.weight)
    }
}
