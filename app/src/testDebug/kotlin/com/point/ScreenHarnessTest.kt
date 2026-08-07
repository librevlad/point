package com.point

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScreenHarnessTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `экран рисуется в JVM-тесте`() {
        compose.setContent { Text("Точка") }
        compose.onNodeWithText("Точка").assertIsDisplayed()
    }
}
