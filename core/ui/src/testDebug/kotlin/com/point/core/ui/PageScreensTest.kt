package com.point.core.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
