package com.point

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.point.core.flow.AI_PROVIDERS
import com.point.core.flow.UserAiConfig
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SettingsCompositionTest {

    @get:Rule val compose = createComposeRule()

    private val openRouter = AI_PROVIDERS.first()
    private val saved = UserAiConfig("sk-or-v1-abcdef123456", openRouter.baseUrl, "gemma")

    private fun screen(
        config: UserAiConfig = UserAiConfig.DEFAULT,
        onOpenUrl: (String) -> Unit = {},
        onCheck: (UserAiConfig) -> Unit = {},
        openKey: Boolean = true,
    ) {
        compose.setContent {
            PointTheme(darkTheme = true) {
                KeyScreen(
                    config = config,
                    onSave = {},
                    onCancel = {},
                    usageEnabled = false,
                    usageSummary = null,
                    onToggleUsage = {},
                    onOpenUrl = onOpenUrl,
                    onCheck = onCheck,
                )
            }
        }
        if (openKey) compose.onNodeWithText("Ключ AI").performClick()
    }

    @Test fun `поле ключа видно сразу, без прокрутки`() {
        screen()

        compose.onAllNodes(hasSetTextAction()).onFirst().assertIsDisplayed()
    }

    @Test fun `сервисы свёрнуты в строку и раскрываются тапом`() {
        screen(config = saved)

        compose.onNodeWithText("Сервис").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(openRouter.what, substring = true).assertExists()
        compose.onNodeWithText("Groq").assertDoesNotExist()

        compose.onNodeWithText("Сервис").performScrollTo().performClick()

        compose.onNodeWithText("Groq").assertExists()
    }

    @Test fun `модель и адрес свёрнуты, но их значения видны строкой`() {
        screen(config = saved)

        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithText("gemma", substring = true).assertExists()

        compose.onNodeWithText("Модель и адрес").performScrollTo().performClick()

        compose.onAllNodes(hasSetTextAction()).assertCountEquals(3)
    }

    @Test fun `ссылка на страницу сервиса называет его и ведёт туда`() {
        var opened: String? = null
        screen(config = saved, onOpenUrl = { opened = it })

        compose.onNodeWithText("Открыть сайт ${openRouter.name}").performScrollTo().performClick()

        assertEquals(openRouter.keyUrl, opened)
    }

    @Test fun `без выбранного сервиса ссылка не врёт, а зовёт выбрать`() {
        var opened: String? = null
        screen(config = UserAiConfig("", "https://мой.прокси/v1", "м"), onOpenUrl = { opened = it })

        compose.onNodeWithText("Сначала выберите сервис").performScrollTo().performClick()

        assertEquals(null, opened)
        compose.onNodeWithText("Groq").assertExists()
    }

    @Test fun `заданный ключ виден хвостом, не открывая ключа целиком`() {

        screen(config = saved, openKey = false)

        compose.onNodeWithText("Ключ на устройстве", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("sk-o…3456", substring = true).assertExists()
    }

    @Test fun `набранный, но не сохранённый ключ так и называется`() {
        screen(config = UserAiConfig("", openRouter.baseUrl, "gemma"))
        compose.onNodeWithText("Ключа пока нет", substring = true).assertExists()

        compose.onAllNodes(hasSetTextAction()).onFirst().performScrollTo().performTextInput("sk-новый-ключ-1234")

        compose.onNodeWithText("ещё не сохранён", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `про «работает» строка состояния молчит — это знает только сервис`() {
        screen(config = saved)

        compose.onNodeWithText("Работает", substring = true).assertDoesNotExist()
    }

    @Test fun `бывший склад разбит на названные разделы`() {
        screen(config = saved, openKey = false)

        compose.onNodeWithText("Отправка и приватность").assertIsDisplayed()
        compose.onNodeWithText("Звук действий").assertIsDisplayed()

        compose.onNodeWithText("Звук действий").performClick()
        compose.onNodeWithText("Приложение").assertIsDisplayed()
    }
}
