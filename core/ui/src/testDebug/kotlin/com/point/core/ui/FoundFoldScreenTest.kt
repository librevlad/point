package com.point.core.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.point.core.flow.KIND_EMAIL
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.model.ValueRef
import com.point.core.ui.theme.PointTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Экран длинного документа (#1015): 119 почт больше не хоронят под собой действия.
 *
 * Правило сворачивания проверено отдельно (`FoundFoldTest`); здесь важно, что экран им
 * действительно пользуется — и что раскрытие возвращает человеку всё найденное.
 */
@RunWith(RobolectricTestRunner::class)
class FoundFoldScreenTest {

    @get:Rule val compose = createComposeRule()

    private val doc = PointObject(
        id = "o",
        mime = "image/png",
        uri = ScratchRef("/scratch/long.png"),
        state = ObjectState(ObjectKind.IMAGE),
        metadata = mapOf("name" to "long.png"),
    )

    private fun email(n: Int) = PointObject(
        id = "doc:email:$n",
        mime = "text/plain",
        uri = ValueRef("tester$n@example.com"),
        state = ObjectState(KIND_EMAIL),
        metadata = mapOf(META_ENTITY_PREFIX + "email" to "tester$n@example.com"),
    )

    private fun screen(count: Int) = compose.setContent {
        PointTheme(darkTheme = true) {
            FirstScreen(
                obj = doc,
                bubbles = emptyList(),
                onBubble = {},
                found = (1..count).map(::email),
            )
        }
    }

    private val foldRow = foundGroupLabel(KIND_EMAIL, 119)

    @Test fun `сто девятнадцать почт стоят одной строкой`() {
        screen(119)

        compose.onNodeWithText(foldRow).assertExists()
        compose.onNodeWithText("tester1@example.com").assertDoesNotExist()
        compose.onNodeWithText("tester119@example.com").assertDoesNotExist()
    }

    @Test fun `свёрнутое раскрывается и отдаёт всё найденное`() {
        screen(119)

        compose.onNodeWithText(foldRow).performScrollTo().performClick()

        compose.onNodeWithText("tester1@example.com").assertExists()
        compose.onNodeWithText("tester119@example.com").assertExists()
    }

    @Test fun `раскрытое сворачивается обратно`() {
        screen(119)

        compose.onNodeWithText(foldRow).performScrollTo().performClick()
        compose.onNodeWithText(FOUND_GROUP_CLOSE).performScrollTo().performClick()

        compose.onNodeWithText("tester1@example.com").assertDoesNotExist()
    }

    @Test fun `три почты человек читает сразу, без лишнего тапа`() {
        screen(3)

        compose.onNodeWithText("tester1@example.com").assertExists()
        compose.onNodeWithText("tester3@example.com").assertExists()
        compose.onNodeWithText(FOUND_GROUP_OPEN).assertDoesNotExist()
    }
}
