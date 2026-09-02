package com.point.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Решение владельца 2026-08-09: «компакт-окно из трея в правой части экрана…
 * высветилась часть окна (не системное уведомление), клик — вылезло окошко».
 * Геометрия — чистая функция от рабочей области экрана (панель задач уже вычтена).
 */
class CompactPlacementTest {

    // Рабочая область 1920×1040 (панель задач 40 внизу уже вычтена системой).
    private val work = ScreenArea(x = 0, y = 0, width = 1920, height = 1040)

    @Test
    fun `компакт прижат к правому нижнему углу рабочей области с полем`() {
        val b = compactBounds(work)

        assertEquals(1920 - COMPACT_WIDTH - SCREEN_MARGIN, b.x)
        assertEquals(1040 - COMPACT_HEIGHT - SCREEN_MARGIN, b.y)
        assertEquals(COMPACT_WIDTH, b.width)
        assertEquals(COMPACT_HEIGHT, b.height)
    }

    @Test
    fun `peek-плашка встаёт туда же, где появится компакт, — клик не прыгает по экрану`() {
        val p = peekBounds(work)

        assertEquals(1920 - PEEK_WIDTH - SCREEN_MARGIN, p.x)
        assertEquals(1040 - PEEK_HEIGHT - SCREEN_MARGIN, p.y)
    }

    @Test
    fun `на маленьком экране компакт не выходит за верх рабочей области`() {
        val small = ScreenArea(0, 0, 1280, 560)

        val b = compactBounds(small)

        assertEquals(SCREEN_MARGIN, b.y)
        assertEquals(560 - SCREEN_MARGIN * 2, b.height)
    }

    @Test
    fun `вторая половина экрана слева (мультимонитор) учитывает смещение области`() {
        val second = ScreenArea(x = -1920, y = 0, width = 1920, height = 1040)

        val b = compactBounds(second)

        assertEquals(-1920 + 1920 - COMPACT_WIDTH - SCREEN_MARGIN, b.x)
    }
}
