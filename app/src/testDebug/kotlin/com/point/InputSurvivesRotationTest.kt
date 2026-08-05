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
import com.point.core.flow.UserAiConfig
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

/**
 * Введённое не теряется при повороте телефона (#114).
 *
 * Поворот — это пересоздание экрана: всё, что жило на `remember`, исчезает молча. Так уходил
 * вставленный из буфера API-ключ, адрес компьютера и недописанный вопрос к AI.
 * [StateRestorationTester] делает ровно то же, что система при повороте, — и ловит это в JVM.
 */
@RunWith(RobolectricTestRunner::class)
class InputSurvivesRotationTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `набранный AI-ключ переживает поворот`() {
        var saved: UserAiConfig? = null
        val rotation = StateRestorationTester(compose)
        rotation.setContent {
            KeyScreen(
                config = UserAiConfig.DEFAULT,
                onSave = { saved = it },
                onCancel = {},
                usageEnabled = false,
                usageSummary = null,
                onToggleUsage = {},
                onToggleSound = {},
            )
        }
        // Мастер ключа живёт внутри своего раздела (#563) — сначала в него заходим, как человек.
        compose.onNodeWithText("Ключ AI").performClick()
        // Поле ключа — первое и, пока блоки свёрнуты, единственное. Модель и адрес сложены за
        // строкой «Модель и адрес» (#447), поэтому её сначала раскрываем — ровно как палец. Экран
        // длиннее окна и прокручивается, поэтому до каждого узла ещё и доезжаем.
        compose.onAllNodes(hasSetTextAction())[0].performScrollTo()
            .performTextInput("sk-очень-длинный-ключ-из-буфера")
        compose.onNodeWithText("Модель и адрес").performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextReplacement("моя-модель")

        rotation.emulateSavedInstanceStateRestore()

        // Судим по тому, что уйдёт в хранилище: тихая дорога в обход проверки (#465) вообще не
        // рисуется, пока ключ пуст, — потерянный ключ провалит этот тест и кнопкой, и значением.
        // Заодно это проверяет, что поворот не выбросил человека из открытого раздела обратно в
        // список (#563): кнопка живёт только внутри раздела ключа.
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
