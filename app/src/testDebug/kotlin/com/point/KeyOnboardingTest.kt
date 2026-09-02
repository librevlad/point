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
import com.point.core.flow.AiFact
import com.point.core.flow.KeyProbe
import com.point.core.flow.KeyVerdict
import com.point.core.flow.UserAiKey
import com.point.core.flow.UserAiKeys
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

    private val openRouter = AI_PROVIDERS.first()
    private val savedKey = UserAiKeys.NONE.with(UserAiKey(openRouter.id, "sk-or-v1-abcdef123456", model = "gemma"))

    private fun keyScreen(
        keys: UserAiKeys = UserAiKeys.NONE,
        builtIn: Set<String> = emptySet(),
        facts: Map<String, AiFact> = emptyMap(),
        note: String? = null,
        errand: KeyErrand? = null,
        checking: String? = null,
        verdict: KeyVerdict? = null,
        verdictFor: String? = openRouter.id,
        onCheck: (UserAiKey) -> Unit = {},
        onCheckAll: () -> Unit = {},
        onSave: (UserAiKey) -> Unit = {},
        onCancel: () -> Unit = {},
        onPasteKey: () -> String? = { null },
        onForgetKey: (String) -> Unit = {},

        openService: String? = openRouter.name,
    ) {
        compose.setContent {
            KeyScreen(
                screen = aiKeysScreenOf(keys = keys, builtIn = builtIn, facts = facts),
                onSave = onSave,
                onCancel = onCancel,
                note = note,
                errand = errand,
                checking = checking,
                verdict = verdict,
                verdictFor = verdictFor,
                onCheck = onCheck,
                onCheckAll = onCheckAll,
                onPasteKey = onPasteKey,
                onForgetKey = onForgetKey,
            )
        }

        if (note == null && errand == null && verdict == null && checking == null) {
            compose.onNodeWithText("Ключи AI").performClick()
        }
        if (openService != null) {
            compose.onNodeWithText(openService, substring = true).performScrollTo().performClick()
        }
    }

    @Test fun `экран говорит, зачем нужен ключ, а не только просит его`() {
        keyScreen(openService = null)

        // Наверху — две мысли: очередь и необязательность ключа. Подробное объяснение
        // (какие действия просят модель) ждёт за «Как это работает» (#902).
        compose.onNodeWithText("не обязателен", substring = true).assertExists()
        compose.onNodeWithText("Как это работает").performScrollTo().performClick()
        compose.onNodeWithText("Понять", substring = true).assertExists()
        compose.onNodeWithText("Проверить все").performScrollTo().assertIsDisplayed()
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
        var checked: UserAiKey? = null
        keyScreen(
            onCheck = { checked = it },
            onPasteKey = { "sk-or-v1-0123456789abcdefghij" },
        )

        compose.onNodeWithText("Вставить из буфера").performScrollTo().performClick()
        compose.onNodeWithText("Проверить и включить").performScrollTo().performClick()

        assertEquals("sk-or-v1-0123456789abcdefghij", checked?.apiKey)
        assertEquals("ключ ушёл проверяться не за тот сервис", openRouter.id, checked?.providerId)
    }

    @Test fun `в буфере не ключ — Point говорит об этом, а не молчит`() {
        keyScreen(onPasteKey = { "какой-то скопированный абзац текста" })

        compose.onNodeWithText("Вставить из буфера").performScrollTo().performClick()

        compose.onNodeWithText("В буфере нет ключа", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `«Проверить и включить» отдаёт наверх ровно набранное`() {
        var checked: UserAiKey? = null
        keyScreen(onCheck = { checked = it })

        compose.onAllNodes(hasSetTextAction()).onFirst().performScrollTo().performTextInput("sk-набранный")
        compose.onNodeWithText("Проверить и включить").performScrollTo().performClick()

        assertEquals(UserAiKey(openRouter.id, "sk-набранный"), checked)
    }

    @Test fun `без ключа проверять нечего — кнопка погашена`() {
        var checked: UserAiKey? = null
        keyScreen(onCheck = { checked = it })

        compose.onNodeWithText("Проверить и включить").performScrollTo().assertIsNotEnabled()
        assertNull(checked)
    }

    @Test fun `идущая проверка всех говорит о себе и не запускается второй раз`() {
        var asked = 0
        keyScreen(checking = CHECK_ALL_SERVICES, onCheckAll = { asked++ }, openService = null)

        compose.onNodeWithText("Проверяю…").performScrollTo().assertIsNotEnabled()
        assertEquals(0, asked)
    }

    @Test fun `«работает» человек видит словами сервиса`() {
        keyScreen(keys = savedKey, verdict = KeyVerdict.Works("Готово"))

        compose.onNodeWithText("Работает", substring = true).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("сервис ответил: «Готово»", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `отказ называет причину и что с ней делать`() {
        keyScreen(
            keys = savedKey,
            verdict = KeyVerdict.Refused("Ключ не подошёл", "Скопируйте ключ целиком, без пробелов."),
        )

        compose.onNodeWithText("Ключ не подошёл").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Скопируйте ключ целиком", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `приговор стоит у того сервиса, который проверяли`() {
        keyScreen(
            keys = savedKey,
            verdict = KeyVerdict.Refused("Ключ не подошёл", "Скопируйте ключ целиком, без пробелов."),
            verdictFor = "groq",
            openService = openRouter.name,
        )

        compose.onNodeWithText("Ключ не подошёл").assertDoesNotExist()
    }

    @Test fun `экран, открытый с отказа, повторяет его причину`() {

        keyScreen(note = "AI недоступен — задайте свой ключ", openService = null)

        compose.onNodeWithText("AI недоступен", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `совет про исчерпанную квоту не обещает переключения на второй ключ`() {
        keyScreen(keys = savedKey, verdict = keyVerdict(KeyProbe(status = 429)))

        compose.onNodeWithText("квота", substring = true).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("переключится", substring = true).assertDoesNotExist()
        compose.onNodeWithText("второй ключ", substring = true).assertDoesNotExist()
    }

    @Test fun `заданный ключ можно удалить, и поле пустеет сразу`() {
        var forgotten: String? = null
        keyScreen(keys = savedKey, onForgetKey = { forgotten = it })

        compose.onNodeWithText("Удалить ключ").performScrollTo().performClick()

        assertEquals("ключ не ушёл с устройства", openRouter.id, forgotten)
    }

    @Test fun `удалять нечего, пока ключ не задан`() {
        keyScreen()

        compose.onNodeWithText("Удалить ключ").assertDoesNotExist()
    }

    @Test fun `у каждого сервиса своё поле ключа, а не одно на всех`() {
        keyScreen(keys = savedKey, openService = "Groq")

        compose.onNodeWithText("Ключ Groq").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ключ ${openRouter.name}").assertDoesNotExist()
    }

    private val errand = KeyErrand(action = "Понять", objectName = "чек.jpg")

    @Test fun `пришедший с действия сразу оказывается в разделе ключей`() {
        keyScreen(errand = errand, openService = null)

        compose.onNodeWithText("Проверить все").performScrollTo().assertIsDisplayed()
    }

    @Test fun `экран, открытый действием, называет это действие по имени`() {
        keyScreen(errand = errand, openService = null)

        compose.onNodeWithText("«Понять» ждёт ключа", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `экран, открытый действием, обещает возврат к объекту`() {
        keyScreen(errand = errand, openService = null)

        compose.onNodeWithText("вернётесь к своему объекту", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `после «работает» дверь обратно названа именем объекта`() {
        keyScreen(keys = savedKey, errand = errand, verdict = KeyVerdict.Works("Готово"), openService = null)

        compose.onNodeWithText("Вернуться к «чек.jpg»").performScrollTo().assertIsDisplayed()
    }

    @Test fun `дверь обратно не обещает выполнить действие сама`() {
        keyScreen(keys = savedKey, errand = errand, verdict = KeyVerdict.Works("Готово"), openService = null)

        compose.onNodeWithText("Нажать на него Point за вас не станет", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `дверь обратно ведёт тем же выходом, что и «Готово»`() {
        var left = 0
        keyScreen(
            keys = savedKey,
            errand = errand,
            verdict = KeyVerdict.Works("Готово"),
            onCancel = { left++ },
            openService = null,
        )

        compose.onNodeWithText("Вернуться к «чек.jpg»").performScrollTo().performClick()

        assertTrue("строка есть, а выхода за ней нет", left == 1)
    }

    @Test fun `до проверки двери обратно нет`() {
        keyScreen(errand = errand, openService = null)

        compose.onNodeWithText("Вернуться к", substring = true).assertDoesNotExist()
    }

    @Test fun `без поручения экран не зовёт ни к какому объекту`() {
        keyScreen(keys = savedKey, verdict = KeyVerdict.Works("Готово"), openService = null)

        compose.onNodeWithText("Вернуться к", substring = true).assertDoesNotExist()
    }
}
