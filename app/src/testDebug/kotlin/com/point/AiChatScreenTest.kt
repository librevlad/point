package com.point

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.point.core.model.ChatMessage
import com.point.core.model.ChatRole
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Что человек может сделать, пока ответа ещё нет (#453).
 *
 * Кнопки отмены у разговора не было вовсе: поле ввода гасло, и единственным выходом из ожидания
 * был уход с экрана — он же и худший, потому что пришедший ответ выбрасывался молча вместе с
 * потраченной квотой. Экран этого не ловил ничем: колбэки экранов до #114 не проверял ни один тест.
 */
@RunWith(RobolectricTestRunner::class)
class AiChatScreenTest {

    @get:Rule val compose = createComposeRule()

    private val obj = PointObject("o", "text/plain", ScratchRef("/o"), ObjectState(ObjectKind.TEXT))

    @Test fun `идущий ответ останавливается прямо со строки ввода`() {
        var stopped = false
        compose.setContent {
            AiChatScreen(
                chat = ChatState(
                    obj = obj,
                    messages = listOf(ChatMessage(ChatRole.USER, "что тут написано?")),
                    pending = true,
                ),
                onSend = {},
                onClose = {},
                onCancel = { stopped = true },
            )
        }

        compose.onNodeWithContentDescription("Остановить").performClick()

        assertTrue("кнопка нарисована, но ничего не останавливает", stopped)
    }

    /**
     * #491: у разговора появился выход, и это узел, по которому человек нажимает. Молча потерянный
     * колбэк выглядел бы как «строка есть, а ответ забрать нельзя» — то есть ровно как тупик, от
     * которого срез и лечит.
     */
    @Test fun `«Забрать ответ» и правда забирает`() {
        var taken = false
        compose.setContent {
            AiChatScreen(
                chat = ChatState(
                    obj = obj,
                    messages = listOf(
                        ChatMessage(ChatRole.USER, "о чём это?"),
                        ChatMessage(ChatRole.ASSISTANT, "Это договор аренды на 11 месяцев."),
                    ),
                ),
                onSend = {},
                onClose = {},
                onTakeAnswer = { taken = true },
            )
        }

        // Строка говорит, что вернётся, — тем же языком, что и строки на экране объекта.
        compose.onNodeWithText("вернёт текст").assertExists()
        compose.onNodeWithText("Забрать ответ").performClick()

        assertTrue("строка нарисована, но ничего не забирает", taken)
    }

    @Test fun `пока забирать нечего, выхода и не предлагают`() {
        compose.setContent {
            AiChatScreen(
                chat = ChatState(obj = obj, messages = listOf(ChatMessage(ChatRole.USER, "о чём это?"))),
                onSend = {},
                onClose = {},
            )
        }

        compose.onNodeWithText("Забрать ответ").assertDoesNotExist()
    }

    @Test fun `остановленный ответ сказан словами, а не исчезнувшими точками`() {
        compose.setContent {
            AiChatScreen(
                chat = ChatState(
                    obj = obj,
                    messages = listOf(ChatMessage(ChatRole.USER, "что тут написано?")),
                    notice = "Ответ остановлен",
                ),
                onSend = {},
                onClose = {},
            )
        }

        compose.onNodeWithText("Ответ остановлен").assertExists()
        // Ждать больше нечего — кнопка снова про отправку.
        compose.onNodeWithContentDescription("Отправить").assertExists()
    }
}
