package com.point

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.point.core.flow.AI_PROVIDERS
import com.point.core.flow.UserAiKey
import com.point.core.model.ChatMessage
import com.point.core.model.ChatRole
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InputSurvivesRotationTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `набранный AI-ключ переживает поворот`() {
        var saved: UserAiKey? = null
        val rotation = StateRestorationTester(compose)
        rotation.setContent {
            KeyScreen(
                screen = aiKeysScreenOf(),
                onSave = { saved = it },
                onCancel = {},
                onToggleSound = {},
            )
        }

        compose.onNodeWithText("Ключи AI").performClick()
        compose.onNodeWithText(AI_PROVIDERS.first().name, substring = true).performScrollTo().performClick()

        compose.onAllNodes(hasSetTextAction())[0].performScrollTo()
            .performTextInput("sk-очень-длинный-ключ-из-буфера")
        compose.onNodeWithText("Модель и адрес").performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextReplacement("моя-модель")

        rotation.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Сохранить без проверки").performScrollTo().assertIsEnabled().performClick()
        assertEquals("sk-очень-длинный-ключ-из-буфера", saved?.apiKey)
        assertEquals("моя-модель", saved?.model)
    }

    @Test fun `недописанный вопрос к AI переживает поворот`() {
        val rotation = StateRestorationTester(compose)
        rotation.setContent {
            AiChatScreen(
                chat = ChatState(
                    obj = PointObject("o", "text/plain", ScratchRef("/o"), ObjectState(ObjectKind.TEXT)),
                    messages = listOf(ChatMessage(ChatRole.ASSISTANT, "Слушаю")),
                ),
                onSend = {},
                onClose = {},
            )
        }
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("а что тут по срокам")

        rotation.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("а что тут по срокам").assertExists()
    }
}
