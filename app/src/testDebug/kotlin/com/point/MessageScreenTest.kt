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
}
