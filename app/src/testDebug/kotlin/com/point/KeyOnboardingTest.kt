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
import com.point.core.flow.KeyVerdict
import com.point.core.flow.UserAiConfig
import com.point.core.model.HistoryEntry
import com.point.core.model.ObjectKind
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Путь человека до работающего ключа (#465), проверенный на настоящем экране.
 *
 * Каждый тест здесь — одна из четырёх претензий владельца: Point молчит о том, что ключ нужен;
 * вставку из буфера приходится делать руками; «Сохранить» ничего не проверяет; отказ говорит
 * «ошибка» вместо причины.
 */
@RunWith(RobolectricTestRunner::class)
class KeyOnboardingTest {

    @get:Rule val compose = createComposeRule()

    private fun keyScreen(
        config: UserAiConfig = UserAiConfig.DEFAULT,
        note: String? = null,
        checking: Boolean = false,
        verdict: KeyVerdict? = null,
        onCheck: (UserAiConfig) -> Unit = {},
        onSave: (UserAiConfig) -> Unit = {},
        onPasteKey: () -> String? = { null },
    ) = compose.setContent {
        KeyScreen(
            config = config,
            onSave = onSave,
            onCancel = {},
            usageEnabled = false,
            usageSummary = null,
            onToggleUsage = {},
            note = note,
            checking = checking,
            verdict = verdict,
            onCheck = onCheck,
            onPasteKey = onPasteKey,
        )
    }

    @Test fun `экран говорит, зачем нужен ключ, а не только просит его`() {
        keyScreen()
        // Ровно та претензия владельца: «ни слова о том, что это вообще нужно сделать».
        compose.onNodeWithText("Понять", substring = true).assertExists()
        // Лейбл секции говорит разрядкой заглавными — это и есть его текст на экране.
        compose.onNodeWithText("ШАГ 1 · ОТКУДА ВЗЯТЬ КЛЮЧ").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ШАГ 3 · ПРОВЕРЬТЕ, ЧТО РАБОТАЕТ").performScrollTo().assertIsDisplayed()
    }

    /**
     * Один объект в «Недавнем» вместо пустого экрана: пустой рисует бренд-портал с бесконечной
     * анимацией, и тест на нём не дождался бы покоя никогда. Приглашение стоит выше списка, так
     * что проверяемое от этого не меняется.
     */
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
        compose.onNodeWithText("Подключите AI", substring = true).assertIsDisplayed()
    }

    @Test fun `с ключом «Недавнее» молчит — звать больше некуда`() {
        compose.setContent {
            HomeScreen(recent = oneRecent, onOpen = {}, onSettings = {}, aiKeySet = true)
        }
        compose.onNodeWithText("Подключите AI", substring = true).assertDoesNotExist()
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

        assertEquals(UserAiConfig("sk-набранный", "https://api.example/v1", "модель"), checked)
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
        // Ключ уже сохранён проверкой — уходить с экрана нечего «отменять».
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
        // Отказ приводит сюда предложением (#452), а экран досказывает его там, где его можно
        // устранить (#467): человек не должен помнить, ради чего он сюда шёл.
        keyScreen(note = "AI недоступен — задайте свой ключ")

        compose.onNodeWithText("AI недоступен", substring = true).performScrollTo().assertIsDisplayed()
    }
}
