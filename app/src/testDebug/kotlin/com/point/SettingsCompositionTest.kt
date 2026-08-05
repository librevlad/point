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

/**
 * Композиция настроек как целого (#447) — три претензии владельца, каждая своим тестом.
 *
 * «в настройках неинтуитивные кнопки, что делает кнопка Взять ключ — непонятно, задан он или нет —
 * непонятно. до полей ввода надо скроллить». Шаги и живая проверка (#465) сняли половину; здесь
 * проверяется то, что чинится только порядком и составом блоков — и потому было невидимо для CI:
 * экран собирался из правильных кирпичей, просто не в том порядке.
 *
 * Размер окна назван вслух ([Config]): «видно без прокрутки» — утверждение про экран телефона, и
 * без заданного окна оно означало бы «видно на том, что подсунул Robolectric».
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SettingsCompositionTest {

    @get:Rule val compose = createComposeRule()

    private val openRouter = AI_PROVIDERS.first()
    private val saved = UserAiConfig("sk-or-v1-abcdef123456", openRouter.baseUrl, "gemma")

    /**
     * Экран с открытым разделом ключа — там, где эти три претензии и живут.
     *
     * С #563 общий экран настроек стал списком разделов, и мастер ключа человек открывает строкой
     * «Ключ AI». Проверяемое от этого не изменилось: состав и порядок блоков внутри мастера — то,
     * что чинится только композицией и потому было невидимо для CI.
     */
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

    // --- «до полей ввода надо скроллить» ---

    @Test fun `поле ключа видно сразу, без прокрутки`() {
        screen()

        // Без `performScrollTo` намеренно: тест провалится ровно тогда, когда поле снова уедет за
        // край — например, если наверх опять вырастет список из семи сервисов.
        compose.onAllNodes(hasSetTextAction()).onFirst().assertIsDisplayed()
    }

    @Test fun `сервисы свёрнуты в строку и раскрываются тапом`() {
        screen(config = saved)

        // Свёрнутая строка называет выбранный сервис его же словами — остальных шести нет.
        compose.onNodeWithText("Сервис").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(openRouter.what, substring = true).assertExists()
        compose.onNodeWithText("Groq").assertDoesNotExist()

        compose.onNodeWithText("Сервис").performScrollTo().performClick()

        compose.onNodeWithText("Groq").assertExists()
    }

    @Test fun `модель и адрес свёрнуты, но их значения видны строкой`() {
        screen(config = saved)

        // Свёрнутый блок не прячет значения — он прячет правку: поле ввода одно, а «gemma» видно.
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithText("gemma", substring = true).assertExists()

        compose.onNodeWithText("Модель и адрес").performScrollTo().performClick()

        compose.onAllNodes(hasSetTextAction()).assertCountEquals(3)
    }

    // --- «что делает кнопка Взять ключ — непонятно» ---

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

        // Тап раскрывает список, а не открывает браузер: открывать нечего.
        assertEquals(null, opened)
        compose.onNodeWithText("Groq").assertExists()
    }

    // --- «задан он или нет — непонятно» ---

    @Test fun `заданный ключ виден хвостом, не открывая ключа целиком`() {
        // Без открытия раздела: с #563 та же строка стоит на общем экране — задан ли ключ, человек
        // узнаёт, не открыв вообще ничего.
        screen(config = saved, openKey = false)

        compose.onNodeWithText("Ключ на устройстве", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("sk-o…3456", substring = true).assertExists()
    }

    @Test fun `набранный, но не сохранённый ключ так и называется`() {
        screen(config = UserAiConfig("", openRouter.baseUrl, "gemma"))
        compose.onNodeWithText("Ключа пока нет", substring = true).assertExists()

        compose.onAllNodes(hasSetTextAction()).onFirst().performScrollTo().performTextInput("sk-новый-ключ-1234")

        // Человек, вставивший ключ и закрывший экран, терял его молча — теперь это сказано.
        compose.onNodeWithText("ещё не сохранён", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `про «работает» строка состояния молчит — это знает только сервис`() {
        screen(config = saved)

        compose.onNodeWithText("Работает", substring = true).assertDoesNotExist()
    }

    // --- склад получил имена, а с #563 — и свои разделы ---

    @Test fun `бывший склад разбит на названные разделы`() {
        screen(config = saved, openKey = false)

        // Прежде это были лейблы групп в хвосте одного полотна; теперь — строки списка, каждая со
        // своим разделом. Имена те же: слова не переписывались, переехало место.
        compose.onNodeWithText("Отправка и приватность").assertIsDisplayed()
        compose.onNodeWithText("Звук действий").assertIsDisplayed()

        compose.onNodeWithText("Звук действий").performClick()
        compose.onNodeWithText("Приложение").assertIsDisplayed()
    }
}
