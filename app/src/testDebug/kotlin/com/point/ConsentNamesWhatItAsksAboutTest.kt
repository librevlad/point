package com.point

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Экран согласия называет, о чём он спрашивает (#1269).
 *
 * Он показывается вместо объекта, а по стуку компьютера человек приходит к нему вовсе с
 * другого экрана — и видит голое «Отправить в облако?» про вещь, которой не выбирал и не
 * видит. Пояснение клалось в сообщение, но сообщение этим же экраном и подменялось.
 */
@RunWith(RobolectricTestRunner::class)
class ConsentNamesWhatItAsksAboutTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `вопрос об отправке показывает работу и объект`() {
        val about = "«Убрать фон» для компьютера · накладная.jpg"
        compose.setContent {
            PointHost(
                state = FlowUiState(cloudConsent = true, cloudAbout = about),
                onBubble = {},
                onSubmitInput = {},
                onCancelInput = {},
            )
        }

        compose.onNodeWithText(about).assertExists()
    }
}
