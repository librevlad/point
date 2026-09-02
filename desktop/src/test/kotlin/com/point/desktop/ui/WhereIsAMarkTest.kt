package com.point.desktop.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/**
 * Где действие исполнится — метка строки, а не её обещание (#1384).
 *
 * «на телефоне» стояло второй строкой — ровно там, где у соседей стоит «снимок уйдёт в сервис».
 * Одно говорит про цену и приватность, другое — только про то, чьими руками сделано, а весили
 * они одинаково. Разбор экрана внешним взглядом назвал это прямо.
 *
 * Проверяется не картинка, а то, что место и причина остались двумя разными вещами: раньше их
 * склеивали в одну строку через « · », и «нет ключа» отдельным текстом на экране не было.
 */
class WhereIsAMarkTest {

    @get:Rule val compose = createComposeRule()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { PointDesktopTheme { content() } }
        compose.waitForIdle()
    }

    @Test
    fun `у недоступного действия причина и место стоят порознь`() {
        show {
            MutedStation(title = "Распознать текст", where = "на телефоне", reason = "нет ключа", icon = "ocr") { }
        }

        compose.onNodeWithText("нет ключа").assertIsDisplayed()
        compose.onNodeWithText("на телефоне").assertIsDisplayed()
    }

    @Test
    fun `у чужого действия место видно меткой`() {
        show {
            Station(title = "Печать", accent = PointColors.violet, where = "на телефоне", icon = "print") { }
        }

        compose.onNodeWithText("Печать").assertIsDisplayed()
        compose.onNodeWithText("на телефоне").assertIsDisplayed()
    }
}
