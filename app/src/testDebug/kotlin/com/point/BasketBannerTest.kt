package com.point

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Плашка корзины на «Недавнем» (#540).
 *
 * Находка живого прогона: «×» на трёх плашках означал «скрыть», а на четвёртой — «стереть всё
 * собранное», и различить их было нечем. Тест проверяет ровно то, что стоило человеку работы:
 * ОДИН тап по разрушительной кнопке не должен ничего разрушать.
 */
@RunWith(RobolectricTestRunner::class)
class BasketBannerTest {

    @get:Rule val compose = createComposeRule()

    private fun home(onClearBasket: () -> Unit = {}, onOpenBasket: () -> Unit = {}) {
        compose.setContent {
            PointTheme(darkTheme = true) {
                HomeScreen(
                    recent = emptyList(),
                    onOpen = {},
                    onSettings = {},
                    basketCount = 3,
                    onOpenBasket = onOpenBasket,
                    onClearBasket = onClearBasket,
                )
            }
        }
    }

    @Test fun `первый тап по «Очистить» спрашивает, а не стирает`() {
        var cleared = 0
        home(onClearBasket = { cleared++ })

        compose.onNodeWithText("Очистить").performClick()

        assertEquals("собранное стёрлось от одного тапа — ровно то, на чём владелец потерял работу", 0, cleared)
        compose.onNodeWithText("Очистить корзину?").assertExists()
        compose.onNodeWithText("Собранное (3) пропадёт — вернуть будет нечем").assertExists()
    }

    @Test fun `подтверждённое очищение доходит до корзины`() {
        var cleared = 0
        home(onClearBasket = { cleared++ })

        compose.onNodeWithText("Очистить").performClick()
        compose.onNodeWithText("Очистить").performClick()

        assertEquals(1, cleared)
    }

    @Test fun `передумавший ничего не теряет`() {
        var cleared = 0
        home(onClearBasket = { cleared++ })

        compose.onNodeWithText("Очистить").performClick()
        compose.onNodeWithText("Отмена").performClick()

        assertEquals(0, cleared)
        compose.onNodeWithText("Корзина: 3").assertExists()
    }

    /** Пока висит вопрос, плашка перестаёт быть дверью: иначе «Отмена» мимо кнопки открывала бы
     *  корзину поверх незаданного вопроса. */
    @Test fun `во время вопроса плашка не открывает корзину`() {
        var opened = 0
        home(onOpenBasket = { opened++ })

        compose.onNodeWithText("Очистить").performClick()
        compose.onNodeWithText("Очистить корзину?").performClick()

        assertEquals(0, opened)
    }

    @Test fun `без вопроса плашка по-прежнему открывает собранное`() {
        var opened = 0
        home(onOpenBasket = { opened++ })

        compose.onNodeWithText("Корзина: 3").performClick()

        assertTrue("плашка перестала открывать корзину", opened == 1)
    }
}
