package com.point

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.point.core.flow.AI_PROVIDERS
import com.point.core.flow.KeyProbe
import com.point.core.flow.KeyVerdict
import com.point.core.flow.UserAiConfig
import com.point.core.flow.keyVerdict
import com.point.core.model.HistoryEntry
import com.point.core.model.ObjectKind
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeyOnboardingTest {

    @get:Rule val compose = createComposeRule()

    private fun keyScreen(
        config: UserAiConfig = UserAiConfig.DEFAULT,
        note: String? = null,
        errand: KeyErrand? = null,
        checking: Boolean = false,
        verdict: KeyVerdict? = null,
        onCheck: (UserAiConfig) -> Unit = {},
        onSave: (UserAiConfig) -> Unit = {},
        onCancel: () -> Unit = {},
        onPasteKey: () -> String? = { null },
        onForgetKey: () -> Unit = {},
    ) {
        compose.setContent {
            KeyScreen(
                config = config,
                onSave = onSave,
                onCancel = onCancel,
                usageEnabled = false,
                usageSummary = null,
                onToggleUsage = {},
                note = note,
                errand = errand,
                checking = checking,
                verdict = verdict,
                onCheck = onCheck,
                onPasteKey = onPasteKey,
                onForgetKey = onForgetKey,
            )
        }

        if (note == null && errand == null && verdict == null && !checking) {
            compose.onNodeWithText("Ключ AI").performClick()
        }
    }

    @Test fun `экран говорит, зачем нужен ключ, а не только просит его`() {
        keyScreen()

        compose.onNodeWithText("Понять", substring = true).assertExists()

        compose.onNodeWithText("ШАГ 1 · ОТКУДА ВЗЯТЬ КЛЮЧ").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ШАГ 3 · ПРОВЕРЬТЕ, ЧТО РАБОТАЕТ").performScrollTo().assertIsDisplayed()
    }

    private val oneRecent = listOf(
        HistoryEntry(
            id = "1",
            mime = "text/plain",
            kind = ObjectKind.TEXT,
            name = "заметка.txt",
            epochMillis = 1_700_000_000_000,
            ref = ScratchRef("/tmp/заметка.txt"),
        ),
    )

    @Test fun `«Недавнее» зовёт подключить AI, пока ключа нет`() {
        compose.setContent {
            HomeScreen(recent = oneRecent, onOpen = {}, onSettings = {}, aiKeySet = false)
        }
        compose.onNodeWithText("Подключить AI", substring = true).assertIsDisplayed()
    }

    @Test fun `с ключом «Недавнее» молчит — звать больше некуда`() {
        compose.setContent {
            HomeScreen(recent = oneRecent, onOpen = {}, onSettings = {}, aiKeySet = true)
        }
        compose.onNodeWithText("Подключить AI", substring = true).assertDoesNotExist()
    }

    @Test fun `ключ из буфера встаёт в поле одним тапом`() {
        var checked: UserAiConfig? = null
        keyScreen(
            config = UserAiConfig("", "https://api.example/v1", "m"),
            onCheck = { checked = it },
            onPasteKey = { "sk-or-v1-0123456789abcdefghij" },
        )

        compose.onNodeWithText("Вставить из буфера").performScrollTo().performClick()
        compose.onNodeWithText("Проверить и включить").performScrollTo().performClick()

        assertEquals("sk-or-v1-0123456789abcdefghij", checked?.apiKey)
    }

    @Test fun `в буфере не ключ — Point говорит об этом, а не молчит`() {
        keyScreen(
            config = UserAiConfig("", "https://api.example/v1", "m"),
            onPasteKey = { "какой-то скопированный абзац текста" },
        )

        compose.onNodeWithText("Вставить из буфера").performScrollTo().performClick()

        compose.onNodeWithText("В буфере нет ключа", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `«Проверить и включить» отдаёт наверх ровно набранное`() {
        var checked: UserAiConfig? = null
        keyScreen(config = UserAiConfig("", "https://api.example/v1", "модель"), onCheck = { checked = it })

        compose.onAllNodes(hasSetTextAction()).onFirst().performScrollTo().performTextInput("sk-набранный")
        compose.onNodeWithText("Проверить и включить").performScrollTo().performClick()

        assertEquals(
            UserAiConfig("sk-набранный", "https://api.example/v1", "модель"),
            checked?.copy(savedAt = 0L),
        )
        assertTrue("ключ сохранён без метки времени", (checked?.savedAt ?: 0L) > 0L)
    }

    @Test fun `без ключа проверять нечего — кнопка погашена`() {
        var checked: UserAiConfig? = null
        keyScreen(config = UserAiConfig("", "https://api.example/v1", "m"), onCheck = { checked = it })

        compose.onNodeWithText("Проверить и включить").performScrollTo().assertIsNotEnabled()
        assertNull(checked)
    }

    @Test fun `идущая проверка говорит о себе и не запускается второй раз`() {
        var checked: UserAiConfig? = null
        keyScreen(
            config = UserAiConfig("sk-1", "https://api.example/v1", "m"),
            checking = true,
            onCheck = { checked = it },
        )

        compose.onNodeWithText("Проверяю…").performScrollTo().assertIsNotEnabled()
        assertNull(checked)
    }

    @Test fun `«работает» человек видит словами сервиса`() {
        keyScreen(
            config = UserAiConfig("sk-1", "https://api.example/v1", "m"),
            verdict = KeyVerdict.Works("Готово"),
        )

        compose.onNodeWithText("Работает", substring = true).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Готово").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Сохранить без проверки").assertDoesNotExist()
    }

    @Test fun `отказ называет причину и что с ней делать`() {
        keyScreen(
            config = UserAiConfig("не-тот", "https://api.example/v1", "m"),
            verdict = KeyVerdict.Refused("Ключ не подошёл", "Скопируйте ключ целиком, без пробелов."),
        )

        compose.onNodeWithText("Ключ не подошёл").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Скопируйте ключ целиком", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `экран, открытый с отказа, повторяет его причину`() {

        keyScreen(note = "AI недоступен — задайте свой ключ")

        compose.onNodeWithText("AI недоступен", substring = true).performScrollTo().assertIsDisplayed()
    }

    private val openRouter = AI_PROVIDERS.first()
    private val savedKey = UserAiConfig("sk-or-v1-abcdef123456", openRouter.baseUrl, "gemma")

    @Test fun `совет про исчерпанную квоту не обещает переключения на второй ключ`() {
        keyScreen(config = savedKey, verdict = keyVerdict(KeyProbe(status = 429)))

        compose.onNodeWithText("квота", substring = true).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("переключится", substring = true).assertDoesNotExist()
        compose.onNodeWithText("второй ключ", substring = true).assertDoesNotExist()
    }

    @Test fun `заданный ключ можно забыть, и поле пустеет сразу`() {
        var forgotten = false
        keyScreen(config = savedKey, onForgetKey = { forgotten = true })

        compose.onNodeWithText("Забыть ключ").performScrollTo().performClick()

        assertTrue("ключ не ушёл с устройства", forgotten)

        compose.onNodeWithText("Ключа пока нет", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `забывать нечего, пока ключ не задан`() {
        keyScreen(config = UserAiConfig("", openRouter.baseUrl, "gemma"))

        compose.onNodeWithText("Забыть ключ").assertDoesNotExist()
    }

    @Test fun `выбор другого сервиса очищает поле ключа`() {
        keyScreen(config = savedKey)
        compose.onNodeWithText("Ключ на устройстве", substring = true).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Сервис").performScrollTo().performClick()
        compose.onNodeWithText("Groq").performScrollTo().performClick()

        compose.onNodeWithText("Ключа пока нет", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("sk-o…3456", substring = true).assertDoesNotExist()
    }

    @Test fun `повторный выбор того же сервиса ключ не трогает`() {
        keyScreen(config = savedKey)

        compose.onNodeWithText("Сервис").performScrollTo().performClick()
        compose.onNodeWithText(openRouter.name).performScrollTo().performClick()

        compose.onNodeWithText("Ключ на устройстве", substring = true).performScrollTo().assertIsDisplayed()
    }

    private val errand = KeyErrand(action = "Понять", objectName = "чек.jpg")

    @Test fun `пришедший с действия сразу оказывается в разделе ключа`() {
        keyScreen(errand = errand)

        compose.onNodeWithText("ШАГ 1 · ОТКУДА ВЗЯТЬ КЛЮЧ").performScrollTo().assertIsDisplayed()
    }

    @Test fun `экран, открытый действием, называет это действие по имени`() {
        keyScreen(errand = errand)

        compose.onNodeWithText("«Понять» ждёт ключа", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `экран, открытый действием, обещает возврат к объекту`() {
        keyScreen(errand = errand)

        compose.onNodeWithText("вернётесь к своему объекту", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `после «работает» дверь обратно названа именем объекта`() {
        keyScreen(config = savedKey, errand = errand, verdict = KeyVerdict.Works("Готово"))

        compose.onNodeWithText("Вернуться к «чек.jpg»").performScrollTo().assertIsDisplayed()
    }

    @Test fun `дверь обратно не обещает выполнить действие сама`() {
        keyScreen(config = savedKey, errand = errand, verdict = KeyVerdict.Works("Готово"))

        compose.onNodeWithText("Тапнуть по нему Point за вас не станет", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `дверь обратно ведёт тем же выходом, что и «Готово»`() {
        var left = 0
        keyScreen(config = savedKey, errand = errand, verdict = KeyVerdict.Works("Готово"), onCancel = { left++ })

        compose.onNodeWithText("Вернуться к «чек.jpg»").performScrollTo().performClick()

        assertEquals("строка есть, а выхода за ней нет", 1, left)
    }

    @Test fun `до проверки двери обратно нет`() {
        keyScreen(errand = errand)

        compose.onNodeWithText("Вернуться к", substring = true).assertDoesNotExist()
    }

    @Test fun `без поручения экран не зовёт ни к какому объекту`() {
        keyScreen(config = savedKey, verdict = KeyVerdict.Works("Готово"))

        compose.onNodeWithText("Вернуться к", substring = true).assertDoesNotExist()
    }
}
