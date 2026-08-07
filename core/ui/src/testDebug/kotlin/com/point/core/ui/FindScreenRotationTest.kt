package com.point.core.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class FindScreenRotationTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `набранный запрос переживает поворот`() {
        val rotation = StateRestorationTester(compose)
        rotation.setContent {
            FindScreen(
                image = ImageBitmap(8, 8),
                highlights = emptyList(),
                status = "Найдено: 3",
                onQuery = {},
                onClose = {},
            )
        }
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("накладная")

        rotation.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("накладная").assertExists()

        compose.onNodeWithText("Найдено: 3").assertExists()
    }
}
