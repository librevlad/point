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
import com.point.core.flow.KeyCheck
import com.point.core.flow.UserAiConfig
import com.point.core.flow.keyFingerprint
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Три жалобы владельца на настройки (#447) — тремя тестами, чтобы они не вернулись.
 *
 * «в настройках неинтуитивные кнопки, что делает кнопка Взять ключ — непонятно, задан он или нет —
 * непонятно. до полей ввода надо скроллить». Каждая из трёх была невидима для CI: экран собирался
 * из правильных кирпичей, просто не в том порядке и не теми словами, а порядок и слова тестов не
 * имели вовсе.
 *
 * Размер окна назван вслух ([Config]): «видно без прокрутки» — утверждение про экран телефона, и
 * без заданного окна оно означало бы «видно на том, что подсунул Robolectric».
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private val openRouter = AI_PROVIDERS.first()
    private val saved = UserAiConfig("sk-or-v1-abcdef123456", openRouter.baseUrl, "gemma")

    private fun screen(
        config: UserAiConfig = UserAiConfig.DEFAULT,
        check: KeyCheck = KeyCheck.Untested,
        onSave: (UserAiConfig) -> Unit = {},
        onCheck: (UserAiConfig) -> Unit = {},
        onOpenUrl: (String) -> Unit = {},
    ) = compose.setContent {
        PointTheme(darkTheme = true) {
            KeyScreen(
                config = config,
                onSave = onSave,
                onCancel = {},
                usageEnabled = false,
                usageSummary = null,
                onToggleUsage = {},
                onOpenUrl = onOpenUrl,
                keyCheck = check,
                onCheckKey = onCheck,
            )
        }
    }

    // --- «до полей ввода надо скроллить» ---

    @Test fun `поле ключа видно сразу, без прокрутки`() {
        screen()

        // Без `performScrollTo` намеренно: тест провалится ровно тогда, когда поле снова уедет за
        // край — например, если сверху опять вырастет список провайдеров.
        compose.onAllNodes(hasSetTextAction()).onFirst().assertIsDisplayed()
        compose.onNodeWithText("Сохранить").assertIsDisplayed()
    }

    @Test fun `модель и адрес свёрнуты, но их значения видны строкой`() {
        screen(config = saved)

        // Свёрнутый блок не прячет значения — он прячет правку: поле ввода одно, а «gemma» видно.
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithText("gemma", substring = true).assertExists()

        compose.onNodeWithText("Модель и адрес").performScrollTo().performClick()

        compose.onAllNodes(hasSetTextAction()).assertCountEquals(3)
    }

    // --- «задан он или нет — непонятно» ---

    @Test fun `пустой ключ назван словами, а не числом точек`() {
        screen(config = UserAiConfig("", openRouter.baseUrl, "gemma"))

        compose.onNodeWithText("Ключа нет").assertIsDisplayed()
    }

    @Test fun `сохранённый ключ узнаётся по хвосту, и он не выдаётся за проверенный`() {
        screen(config = saved)

        compose.onNodeWithText("Ключ сохранён").assertIsDisplayed()
        // Именно маска, а не «3456»: хвост есть и в самом поле, и совпадение с ним ничего бы не
        // доказало — карточка обязана показать ключ закрытым.
        compose.onNodeWithText("sk-o…3456", substring = true).assertExists()
        compose.onNodeWithText("ещё не проверен", substring = true).assertExists()
    }

    @Test fun `отказ провайдера показан его словами`() {
        screen(
            config = saved,
            check = KeyCheck.Rejected("Groq не принял ключ (401). Ответ: Invalid API Key", keyFingerprint(saved)),
        )

        compose.onNodeWithText("Ключ не принят").assertIsDisplayed()
        compose.onNodeWithText("Invalid API Key", substring = true).assertExists()
    }

    @Test fun `правка ключа снимает отметку «работает»`() {
        screen(config = saved, check = KeyCheck.Works("gemma", 900, keyFingerprint(saved)))
        compose.onNodeWithText("Ключ работает").assertIsDisplayed()

        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("7")

        // Зелёная отметка над уже изменённым ключом — ложь, неотличимая от правды.
        compose.onNodeWithText("Ключ работает").assertDoesNotExist()
        compose.onNodeWithText("Ключ введён, но не сохранён").assertExists()
    }

    @Test fun `проверка спрашивает провайдера про то, что сейчас в полях`() {
        var asked: UserAiConfig? = null
        screen(config = saved, onCheck = { asked = it })

        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("7")
        compose.onNodeWithText("Проверить ключ").performScrollTo().performClick()

        // Курсор в пустом поле стоит в начале, поэтому дописанное встаёт слева — важно здесь не
        // место символа, а что провайдера спрашивают про НАБРАННОЕ, а не про сохранённое.
        assertEquals("7" + saved.apiKey, asked?.apiKey)
    }

    // --- «что делает кнопка Взять ключ — непонятно» ---

    @Test fun `ссылка на сайт называет провайдера и ведёт на его страницу`() {
        var opened: String? = null
        screen(config = saved, onOpenUrl = { opened = it })

        // Кнопки «Взять ключ» внутри строки провайдера больше нет: она читалась как выбор.
        compose.onNodeWithText("Открыть сайт ${openRouter.name}").performScrollTo().performClick()

        assertEquals(openRouter.keyUrl, opened)
    }

    @Test fun `выбор провайдера свёрнут в одну строку и раскрывается тапом`() {
        screen(config = saved)

        compose.onNodeWithText("Groq").assertDoesNotExist()
        compose.onNodeWithText("Провайдер").performScrollTo().performClick()

        compose.onNodeWithText("Groq").assertExists()
    }

    @Test fun `выход называет цену выхода`() {
        screen(config = saved)
        compose.onNodeWithText("Закрыть").performScrollTo().assertExists()

        compose.onAllNodes(hasSetTextAction()).onFirst().performScrollTo().performTextInput("7")

        compose.onNodeWithText("Закрыть без сохранения").performScrollTo().assertExists()
    }
}
