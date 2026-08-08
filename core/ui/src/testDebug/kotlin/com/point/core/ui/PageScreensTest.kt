package com.point.core.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class PageScreensTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `выделение — «Взять» зовёт действие, когда есть что брать`() {
        var taken = false
        compose.setContent {
            SelectionScreen(
                image = ImageBitmap(8, 8),
                highlights = emptyList(),
                capturedText = "Відділення №9, вул. Хрещатик, 1",
                onSelect = {},
                onTake = { taken = true },
                onFocus = {},
                onClose = {},
            )
        }

        compose.onNodeWithText("Відділення №9, вул. Хрещатик, 1").assertExists()
        compose.onNodeWithText("Взять").performClick()

        assertTrue("«Взять» перестало брать после переезда в дизайн-систему", taken)
    }

    @Test fun `выделение — «Смотреть сюда» это Focus, а не crop`() {
        var focused = false
        var taken = false
        compose.setContent {
            SelectionScreen(
                image = ImageBitmap(8, 8),
                highlights = emptyList(),
                capturedText = "+380671234567",
                onSelect = {},
                onTake = { taken = true },
                onFocus = { focused = true },
                onClose = {},
            )
        }

        compose.onNodeWithText("Смотреть сюда").performClick()

        assertTrue("жест области должен уметь становиться Focus", focused)
        assertFalse("Focus не создаёт объект-вырезку", taken)
    }

    @Test fun `выделение — пока пальцем ничего не обведено, брать нечем`() {
        var taken = false
        compose.setContent {
            SelectionScreen(
                image = ImageBitmap(8, 8),
                highlights = emptyList(),
                capturedText = null,
                onSelect = {},
                onTake = { taken = true },
                onFocus = {},
                onClose = {},
            )
        }

        compose.onNodeWithText("Обведите нужное на странице").assertExists()
        compose.onNodeWithText("Взять").performClick()

        assertFalse("притушенная строка всё-таки взяла пустое выделение", taken)
    }

    @Test fun `выделение — фрагмент изображения называется своим словом`() {

        compose.setContent {
            SelectionScreen(
                image = ImageBitmap(8, 8),
                highlights = emptyList(),
                capturedText = "",
                onSelect = {},
                onTake = {},
                onFocus = {},
                onClose = {},
            )
        }

        compose.onNodeWithText("Взять фрагмент").assertExists()
    }

    @Test fun `поиск — экран наконец называет себя`() {

        compose.setContent {
            FindScreen(
                image = ImageBitmap(8, 8),
                highlights = emptyList(),
                status = "Найдено: 3",
                onQuery = {},
                onClose = {},
            )
        }

        compose.onNodeWithText("НАЙТИ В ДОКУМЕНТЕ").assertExists()
        compose.onNodeWithText("Найдено: 3").assertExists()
    }

    @Test fun `поиск — «Закрыть» остаётся выходом`() {
        var closed = false
        compose.setContent {
            FindScreen(
                image = ImageBitmap(8, 8),
                highlights = emptyList(),
                status = null,
                onQuery = {},
                onClose = { closed = true },
            )
        }

        compose.onNodeWithText("Закрыть").performClick()

        assertTrue("выход с экрана поиска потерян", closed)
    }
}
