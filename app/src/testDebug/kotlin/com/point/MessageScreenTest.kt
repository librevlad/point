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

/**
 * Экран, на котором есть сообщение и нет объекта (#114), — и выход с него.
 *
 * Сюда приезжают три двери: «Ключ AI сохранён», «Объект недоступен» из истории и чужой QR
 * `point-pc://`. Раньше на этом экране не было ни одной кнопки, а «назад» закрывал Point: удача
 * заканчивалась выходом из приложения.
 */
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
        // «Готово» над отказом поздравляло бы человека с неудачей.
        compose.onNodeWithText("Понятно").assertExists()
        compose.onNodeWithText("Готово").assertDoesNotExist()
    }

    @Test fun `слово на выходе зависит от исхода`() {
        assertEquals("Готово", messageExitLabel(Outcome.DONE))
        assertEquals("Готово", messageExitLabel(Outcome.NONE))
        assertEquals("Понятно", messageExitLabel(Outcome.FAILED))
    }

    /**
     * #452: экран ключей — предложение, а не подмена ответа. Причина остаётся на экране, а рядом
     * с ней стоит строка, по которой человек идёт за ключом сам.
     */
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

    /** Предложить ключ там, где он ни при чём, — выдумать человеку причину. */
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
