package com.point.core.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Focus виден на экране объекта и снимается одним движением (#757, вариант A).
 *
 * После `✓` в Focus экран объекта выглядел ровно как до него: ни отметки, ни превью области,
 * ни способа снять. Человек показывал область — и не знал, применилось ли это; дальше «Понять»
 * читало только область, не сказав об этом ни слова. Знание, меняющее поведение всех следующих
 * действий, не может быть невидимым.
 */
@RunWith(RobolectricTestRunner::class)
class FocusIsVisibleAndRemovableTest {

    @get:Rule val compose = createComposeRule()

    private val shot = PointObject(
        id = "shot",
        mime = "image/jpeg",
        uri = ScratchRef("/scratch/снимок.jpg"),
        state = ObjectState(ObjectKind.IMAGE),
        metadata = mapOf("name" to "снимок.jpg"),
    )

    private val understand = Bubble(
        icon = "ai",
        title = "Понять",
        capabilityId = CapabilityId("understand"),
        expectedNextState = ObjectState(ObjectKind.IMAGE),
    )

    private var dropped = 0

    private fun screen(focused: Boolean) {
        compose.setContent {
            PointTheme {
                FirstScreen(
                    obj = shot,
                    bubbles = listOf(understand),
                    onBubble = {},
                    focused = focused,
                    onClearFocus = { dropped++ },
                )
            }
        }
    }

    @Test
    fun `с показанной областью видно, что Point смотрит в неё`() {
        screen(focused = true)

        compose.onNodeWithText(FOCUS_HERE).assertExists()
    }

    @Test
    fun `без Focus про область на экране ничего не сказано`() {
        screen(focused = false)

        compose.onNodeWithText(FOCUS_HERE).assertDoesNotExist()
    }

    @Test
    fun `фокус снимается одним движением, без выхода с экрана`() {
        screen(focused = true)

        compose.onNodeWithContentDescription(FOCUS_DROP).performClick()

        assertEquals(1, dropped)
    }
}
