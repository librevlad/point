package com.point.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RoundPreviewTest {

    @Test
    fun `квадратный кадр входит в круг целиком`() {
        assertEquals(PreviewCrop(0, 0, 100), centerSquareCrop(100, 100))
    }

    @Test
    fun `широкий кадр берётся из центра, а не от левого края`() {
        val crop = centerSquareCrop(400, 200)
        assertEquals(PreviewCrop(100, 0, 200), crop)
    }

    @Test
    fun `высокий кадр берётся из центра, а не сверху`() {
        val crop = centerSquareCrop(200, 600)
        assertEquals(PreviewCrop(0, 200, 200), crop)
    }

    @Test
    fun `сторона круга равна меньшей стороне кадра — пропорции не искажаются`() {
        assertEquals(120, centerSquareCrop(1920, 120).side)
        assertEquals(90, centerSquareCrop(90, 4000).side)
        assertEquals(64, centerSquareCrop(64, 64).side)
    }

    @Test
    fun `круг заполняется целиком — пустых полей по краям не остаётся`() {
        val width = 1000
        val height = 400
        val crop = centerSquareCrop(width, height)

        assertEquals(height, crop.side)
        assertTrue(crop.left + crop.side <= width)
        assertTrue(crop.top + crop.side <= height)
    }

    @Test
    fun `нечётный остаток не сдвигает кадр больше чем на пиксель`() {
        val crop = centerSquareCrop(101, 50)

        assertEquals(50, crop.side)
        val right = 101 - crop.left - crop.side
        assertTrue(abs(crop.left - right) <= 1)
    }

    @Test
    fun `кадр без пикселей не ломает расчёт`() {
        assertEquals(PreviewCrop(0, 0, 0), centerSquareCrop(0, 0))
        assertEquals(0, centerSquareCrop(300, 0).side)
    }
}
