package com.point

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.point.core.flow.AppTarget
import com.point.core.flow.QUIET_GRACE_S
import com.point.core.flow.waitingLine
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Preview
import com.point.core.model.ScratchRef
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PortalScreensTest {

    @get:Rule val compose = createComposeRule()

    private fun host(state: FlowUiState, block: PointHostCallbacks.() -> Unit = {}): PointHostCallbacks {
        val calls = PointHostCallbacks().apply(block)
        compose.setContent {
            PointTheme(darkTheme = true) {
                PointHost(
                    state = state,
                    onBubble = {},
                    onSubmitInput = {},
                    onCancelInput = {},
                    onConfirmCloud = { calls.cloudAllowed = true },
                    onDeclineCloud = { calls.cloudDeclined = true },
                    onPickApp = { calls.pickedApp = it },
                    onConfirmPreview = { calls.previewConfirmed = true },
                    onSendChat = { calls.sent = it },
                )
            }
        }
        return calls
    }

    @Test fun `согласие на облако — соглашаются словом действия, а не «облаком вообще»`() {

        val calls = host(
            FlowUiState(
                cloudConsent = true,
                cloudTitle = "Выложить файл по ссылке?",
                cloudConfirm = "Выложить",
                cloudDestination = "Файл ляжет на сервер Point и будет доступен по ссылке сутки.",
            ),
        )

        compose.onNodeWithText("Выложить файл по ссылке?").assertExists()
        compose.onNodeWithText("Файл ляжет на сервер Point и будет доступен по ссылке сутки.").assertExists()
        compose.onNodeWithText("Выложить").performClick()

        assertTrue("строка согласия перестала разрешать", calls.cloudAllowed)
    }

    @Test fun `согласие на облако — «Не сейчас» остаётся выходом`() {
        val calls = host(FlowUiState(cloudConsent = true))

        compose.onNodeWithText("Не сейчас").performClick()

        assertTrue("отказ от облака потерян", calls.cloudDeclined)
        assertTrue("разрешения никто не давал", !calls.cloudAllowed)
    }

    @Test fun `выбор приложения — открывается именно то, по чему нажали`() {
        val calls = host(
            FlowUiState(
                appPicker = listOf(
                    AppTarget(label = "Google Диск", packageName = "com.google.android.apps.docs", activity = "a"),
                    AppTarget(label = "Telegram", packageName = "org.telegram.messenger", activity = "a"),
                ),
            ),
        )

        compose.onNodeWithText("Открыть в").assertExists()
        compose.onNodeWithText("Telegram").performClick()

        assertEquals("org.telegram.messenger", calls.pickedApp?.packageName)
    }

    @Test fun `предпросмотр — разобранное видно до необратимого шага, и подтверждение работает`() {
        val calls = host(
            FlowUiState(
                preview = Preview(
                    title = "Добавить контакт",
                    lines = listOf("Олена Ковальчук", "+380 67 123 45 67"),
                    confirmLabel = "Добавить",
                ),
            ),
        )

        compose.onNodeWithText("Добавить контакт").assertExists()
        compose.onNodeWithText("+380 67 123 45 67").assertExists()
        compose.onNodeWithText("Добавить").performClick()

        assertTrue("подтверждение предпросмотра потеряно", calls.previewConfirmed)
    }

    @Test fun `чат — вопрос-подсказка уходит дословно`() {

        val calls = host(
            FlowUiState(
                chat = ChatState(
                    obj = chatObject(),
                    suggestions = listOf("О чём этот документ?", "Какие сроки в нём названы?"),
                ),
                chatOpen = true,
            ),
        )

        compose.onNodeWithText("Какие сроки в нём названы?").performClick()

        assertEquals("Какие сроки в нём названы?", calls.sent)
    }

    /**
     * Счётчик считает работу, а не время на экране (#1128).
     *
     * Тихой местной работе даётся пара секунд не мигать порталом зря — экран поднимается
     * позже её начала. Секунды же он считал у себя с нуля: человек, прождавший пять секунд,
     * читал «Идёт 3 с», а первые секунды после подъёма — ещё и «Обрабатываю…». Ждал он всё
     * это время работу, а не экран.
     */
    @Test fun `счётчик ожидания считает от начала работы, а не от появления экрана`() {
        compose.mainClock.autoAdvance = false
        host(FlowUiState(busy = "Свожу к чёрно-белому", busySpentS = QUIET_GRACE_S))

        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithText(waitingLine(QUIET_GRACE_S + 1, network = false)).assertExists()

        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText(waitingLine(QUIET_GRACE_S + 3, network = false)).assertExists()
    }

    @Test fun `чат — ожидание ответа говорит пульсом, а не многоточием`() {
        host(
            FlowUiState(
                chat = ChatState(
                    obj = chatObject(),
                    messages = listOf(com.point.core.model.ChatMessage(com.point.core.model.ChatRole.USER, "Привет")),
                    pending = true,
                ),
                chatOpen = true,
            ),
        )

        compose.onNodeWithText("Думаю…").assertExists()

        compose.onNodeWithText("…").assertDoesNotExist()
    }

    private fun chatObject() = PointObject(
        id = "o",
        mime = "application/pdf",
        uri = ScratchRef("/scratch/договор.pdf"),
        state = ObjectState(ObjectKind.PDF),
        metadata = mapOf("name" to "договор.pdf"),
    )

    class PointHostCallbacks {
        var cloudAllowed = false
        var cloudDeclined = false
        var pickedApp: AppTarget? = null
        var previewConfirmed = false
        var sent: String? = null
    }

    @Test fun `ничего не нажимали — никто никуда не ушёл`() {

        val calls = host(FlowUiState(cloudConsent = true))

        assertTrue(!calls.cloudAllowed && !calls.cloudDeclined)
        assertNull(calls.pickedApp)
    }
}
