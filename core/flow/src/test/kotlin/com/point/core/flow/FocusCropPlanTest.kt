package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Читать показанное, а не страницу целиком (#426). Замер 04.08.2026: показания приборов — ноль
 * из трёх, и провал опаснее отказа — модель уверенно отдаёт числа с шильдика. Причина не в
 * качестве чтения, а в вопросе: страница целиком — неправильный вопрос, когда нужное занимает
 * проценты кадра.
 */
class FocusCropPlanTest {

    private val page = Box(0f, 0f, 3000f, 4000f)

    @Test
    fun `область берётся с запасом вокруг показанного`() {
        val plan = focusCropPlan(Box(1000f, 2000f, 1400f, 2100f), page)!!

        assertTrue("запас не добавлен слева", plan.crop.left < 1000f)
        assertTrue("запас не добавлен справа", plan.crop.right > 1400f)
        assertTrue("запас не добавлен сверху", plan.crop.top < 2000f)
    }

    @Test
    fun `область среднего размера доводится ровно до читаемой`() {
        val plan = focusCropPlan(Box(1000f, 2000f, 1600f, 2150f), page)!!

        assertEquals(MIN_READABLE_SIDE, plan.scale * plan.crop.width(), 2f)
    }

    @Test
    fun `совсем мелкое увеличивается до предела, а не до бесконечности`() {
        val plan = focusCropPlan(Box(1000f, 2000f, 1060f, 2015f), page)!!

        assertEquals("мелкое обязано быть увеличено вчетверо", 4f, plan.scale, 0.01f)
    }

    @Test
    fun `крупная область не раздувается зря`() {
        val plan = focusCropPlan(Box(0f, 0f, 2400f, 3000f), page)!!

        assertEquals("крупное увеличивать незачем", 1f, plan.scale, 0.01f)
    }

    @Test
    fun `запас не выводит за края страницы`() {
        val plan = focusCropPlan(Box(0f, 0f, 200f, 60f), page)!!

        assertEquals(0f, plan.crop.left, 0.01f)
        assertEquals(0f, plan.crop.top, 0.01f)
    }

    @Test
    fun `область во весь кадр вырезать незачем`() {
        assertNull("вырезать целую страницу — та же страница", focusCropPlan(page, page))
    }

    @Test
    fun `пустая область не даёт плана`() {
        assertNull(focusCropPlan(Box(100f, 100f, 100f, 100f), page))
    }
}
