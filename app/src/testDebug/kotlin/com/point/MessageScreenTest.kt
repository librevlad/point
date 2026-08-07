package com.point

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.point.core.ui.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `у сообщения без объекта есть кнопка выхода, и она зовёт выход`() {
        var left = false
        compose.setContent {
            PointHost(
                state = FlowUiState(message = "Ключ AI сохранён", messageOutcome = Outcome.DONE),
                onBubble = {},
                onSubmitInput = {},
                onCancelInput = {},
                onDismissMessage = { left = true },
            )
        }
        compose.onNodeWithText("Ключ AI сохранён").assertExists()
        compose.onNodeWithText("Готово").performClick()
        assertTrue("кнопка выхода нарисована, но никуда не ведёт", left)
    }

    @Test fun `у отказа выход назван своим словом`() {
        compose.setContent {
            PointHost(
                state = FlowUiState(message = "Объект недоступен", messageOutcome = Outcome.FAILED),
                onBubble = {},
                onSubmitInput = {},
                onCancelInput = {},
            )
        }

        compose.onNodeWithText("Понятно").assertExists()
        compose.onNodeWithText("Готово").assertDoesNotExist()
    }

    @Test fun `слово на выходе зависит от исхода`() {
        assertEquals("Готово", messageExitLabel(Outcome.DONE))
        assertEquals("Готово", messageExitLabel(Outcome.NONE))
        assertEquals("Понятно", messageExitLabel(Outcome.FAILED))
    }

    @Test fun `под отказом про ключ стоит предложение, и оно ведёт к экрану ключей`() {
        var opened = false
        compose.setContent {
            PointHost(
                state = FlowUiState(
                    message = "AI недоступен — задайте свой ключ",
                    messageOutcome = Outcome.FAILED,
                ),
                onBubble = {},
                onSubmitInput = {},
                onCancelInput = {},
                onOpenKeySettings = { opened = true },
            )
        }
        compose.onNodeWithText("AI недоступен — задайте свой ключ").assertExists()

        compose.onNodeWithText("Задать свой ключ AI").performClick()

        assertTrue("предложение нарисовано, но никуда не ведёт", opened)
    }

    @Test fun `у постороннего отказа предложения про ключ нет`() {
        compose.setContent {
            PointHost(
                state = FlowUiState(message = "Объект недоступен", messageOutcome = Outcome.FAILED),
                onBubble = {},
                onSubmitInput = {},
                onCancelInput = {},
            )
        }
        compose.onNodeWithText("Задать свой ключ AI").assertDoesNotExist()
    }
}
