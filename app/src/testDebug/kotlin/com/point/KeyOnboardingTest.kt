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
        onForgetKey: () -> Unit = {},
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
            onForgetKey = onForgetKey,
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

    // --- Экран закрытого релиза: обещания, путь назад и чужой ключ в поле ---

    private val openRouter = AI_PROVIDERS.first()
    private val savedKey = UserAiConfig("sk-or-v1-abcdef123456", openRouter.baseUrl, "gemma")

    /**
     * Кончившаяся квота не обещает с экрана того, чего Point не делает (#535).
     *
     * Приговор берётся настоящий — тот самый [keyVerdict], что показывают человеку, — а не
     * сочинённый для теста: иначе проверялось бы, что экран умеет рисовать любые слова, а не что
     * человек читает правду.
     */
    @Test fun `совет про исчерпанную квоту не обещает переключения на второй ключ`() {
        keyScreen(config = savedKey, verdict = keyVerdict(KeyProbe(status = 429)))

        compose.onNodeWithText("квота", substring = true).performScrollTo().assertIsDisplayed()
        // Слота под второй ключ на этом экране ровно один — поле выше. Обещать очередь провайдеров
        // значит отправить человека заводить второй аккаунт впустую.
        compose.onNodeWithText("переключится", substring = true).assertDoesNotExist()
        compose.onNodeWithText("второй ключ", substring = true).assertDoesNotExist()
    }

    /**
     * «Забыть ключ» (#536): путь обратно, которого не было вовсе.
     *
     * `UserKeyStore.clear()` был написан и не звался ни с одного экрана — отключить AI человек мог
     * только переустановкой приложения.
     */
    @Test fun `заданный ключ можно забыть, и поле пустеет сразу`() {
        var forgotten = false
        keyScreen(config = savedKey, onForgetKey = { forgotten = true })

        compose.onNodeWithText("Забыть ключ").performScrollTo().performClick()

        assertTrue("ключ не ушёл с устройства", forgotten)
        // Стёртое человек ВИДИТ: строка состояния под полем говорит то же, что при пустом ключе.
        compose.onNodeWithText("Ключа пока нет", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `забывать нечего, пока ключ не задан`() {
        keyScreen(config = UserAiConfig("", openRouter.baseUrl, "gemma"))

        // Набранное, но не сохранённое стирается самим полем; строка «Забыть» обещала бы человеку
        // действие над тем, чего на устройстве нет.
        compose.onNodeWithText("Забыть ключ").assertDoesNotExist()
    }

    /**
     * Смена сервиса уносит чужой ключ (#537).
     *
     * Прежде в поле оставался ключ прежнего сервиса, «Проверить» честно отвечало «Ключ не подошёл»
     * — и человек читал это как поломку продукта, не сделав ничего неправильного.
     */
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

        // Человек не менял сервис — он подтвердил выбранный. Стирать ему ключ за это не за что.
        compose.onNodeWithText("Ключ на устройстве", substring = true).performScrollTo().assertIsDisplayed()
    }
}
