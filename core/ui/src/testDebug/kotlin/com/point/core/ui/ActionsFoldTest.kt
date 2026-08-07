package com.point.core.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.ui.theme.PointTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActionsFoldTest {

    @get:Rule val compose = createComposeRule()

    private val obj = PointObject(
        id = "o",
        mime = "image/jpeg",
        uri = ScratchRef("/scratch/снимок.jpg"),
        state = ObjectState(ObjectKind.IMAGE),
        metadata = mapOf("name" to "снимок.jpg"),
    )

    private fun bubble(title: String, intent: Intent) = Bubble(
        icon = "ocr",
        title = title,
        capabilityId = CapabilityId(title),
        expectedNextState = ObjectState(ObjectKind.TEXT),
        intent = intent,
    )

    private val bubbles =
        (1..8).map { bubble("Извлечение $it", Intent.UNDERSTAND) } +
            listOf(bubble("Поделиться", Intent.SEND), bubble("Сохранить", Intent.SEND))

    private fun screen() = compose.setContent {
        PointTheme(darkTheme = true) {
            FirstScreen(obj = obj, bubbles = bubbles, onBubble = {})
        }
    }

    @Test fun `видно верхние действия группы, остальные — за «Показать ещё N»`() {
        screen()

        compose.onNodeWithText("Извлечение 1").assertExists()
        compose.onNodeWithText("Извлечение 3").assertExists()
        compose.onNodeWithText("Извлечение 4").assertDoesNotExist()
        compose.onNodeWithText("Показать ещё 5").assertExists()
    }

    @Test fun `число в подписи называет ровно то, что спрятано`() {

        screen()

        compose.onNodeWithText("Показать ещё 5").performScrollTo().performClick()

        (1..8).forEach { compose.onNodeWithText("Извлечение $it").assertExists() }
    }

    @Test fun `раскрытое сворачивается обратно`() {

        screen()

        compose.onNodeWithText("Показать ещё 5").performScrollTo().performClick()
        compose.onNodeWithText("Свернуть").performScrollTo().performClick()

        compose.onNodeWithText("Извлечение 8").assertDoesNotExist()
        compose.onNodeWithText("Показать ещё 5").assertExists()
    }

    @Test fun `короткая группа сворачивать нечего и не предлагает`() {

        screen()

        compose.onNodeWithText("Поделиться").assertExists()
        compose.onNodeWithText("Сохранить").assertExists()
        compose.onNodeWithText("Показать ещё 0").assertDoesNotExist()
    }

    @Test fun `главное действие остаётся первым и видимым`() {

        screen()

        compose.onNodeWithText("Извлечение 1").assertExists()
    }
}
