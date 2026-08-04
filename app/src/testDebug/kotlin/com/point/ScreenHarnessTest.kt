package com.point

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Проверка самого станка (#114): экран рисуется в обычном JVM-тесте, без эмулятора.
 *
 * До этого теста ни один тест проекта не создавал экрана — поэтому поворот, «назад» и потерянный
 * колбэк были невидимы для CI по построению. Этот тест ничего не утверждает о продукте; он падает
 * первым, если станок перестал работать, и тогда понятно, что сломано.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenHarnessTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `экран рисуется в JVM-тесте`() {
        compose.setContent { Text("Точка") }
        compose.onNodeWithText("Точка").assertIsDisplayed()
    }
}
