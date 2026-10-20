package com.point

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

    @Test fun `ключ из буфера встаёт в поле и проверяется одним тапом`() {
        var checked: UserAiKey? = null
        keyScreen(
            onCheck = { checked = it },
            onPasteKey = { "sk-or-v1-0123456789abcdefghij" },
        )

        compose.onNodeWithText("Вставить из буфера").performScrollTo().performClick()

        // Вставить и отдельно нажать «Проверить» — два шага там, где человек хотел один.
        assertEquals("sk-or-v1-0123456789abcdefghij", checked?.apiKey)
        assertEquals("ключ ушёл проверяться не за тот сервис", openRouter.id, checked?.providerId)
    }

    @Test fun `ключ из буфера сверху сам встаёт к своему сервису`() {
        var checked: UserAiKey? = null
        keyScreen(
            onCheck = { checked = it },
            onPasteKey = { "gsk_0123456789abcdefghij" },
            openService = null,
        )

        compose.onNodeWithText(com.point.core.flow.PASTE_ANY_KEY).performScrollTo().performClick()

        assertEquals("искать строку сервиса пришлось бы человеку", "groq", checked?.providerId)
        assertEquals("gsk_0123456789abcdefghij", checked?.apiKey)
        compose.onNodeWithText("Ключ Groq").performScrollTo().assertIsDisplayed()
    }

    @Test fun `ключ без приметы — Point спрашивает, чей он, и проверяет только там`() {
        var checked: UserAiKey? = null
        keyScreen(
            onCheck = { checked = it },
            onPasteKey = { "0123456789abcdef0123456789abcdef" },
            openService = null,
        )

        compose.onNodeWithText(com.point.core.flow.PASTE_ANY_KEY).performScrollTo().performClick()

        compose.onNodeWithText(com.point.core.flow.WHOSE_KEY_TITLE).performScrollTo().assertIsDisplayed()
        assertNull("ключ ушёл на пробу наугад — чужому сервису", checked)

        // Первая — кнопка в вопросе «чей?»: строка сервиса стоит ниже, в очереди.
        compose.onAllNodesWithText("Mistral").onFirst().performScrollTo().performClick()

        assertEquals("mistral", checked?.providerId)
        compose.onNodeWithText(com.point.core.flow.WHOSE_KEY_TITLE).assertDoesNotExist()
    }

    @Test fun `в буфере сверху не ключ — Point говорит об этом`() {
        var checked: UserAiKey? = null
        keyScreen(onCheck = { checked = it }, onPasteKey = { "просто абзац текста" }, openService = null)

        compose.onNodeWithText(com.point.core.flow.PASTE_ANY_KEY).performScrollTo().performClick()

        compose.onNodeWithText("В буфере нет ключа", substring = true).performScrollTo().assertIsDisplayed()
        assertNull(checked)
    }

    @Test fun `ключ чужого сервиса в строке называет своего хозяина, а не «не подошёл»`() {
        var checked: UserAiKey? = null
        keyScreen(onCheck = { checked = it }, onPasteKey = { "gsk_0123456789abcdefghij" })

        compose.onNodeWithText("Вставить из буфера").performScrollTo().performClick()

        compose.onNodeWithText("Похоже, это ключ Groq", substring = true).performScrollTo().assertIsDisplayed()
        assertNull("ключ Groq ушёл бы на пробу к OpenRouter", checked)

        compose.onNodeWithText("Проверить как ключ Groq").performScrollTo().performClick()

        assertEquals("groq", checked?.providerId)
    }

    @Test fun `вставленный ключ можно увидеть буквами`() {
        keyScreen(keys = savedKey)
        fun shown() = compose.onAllNodes(hasSetTextAction())[0].fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text

        assertTrue("ключ виден без просьбы", shown().all { it == '•' })
        compose.onNodeWithText("Показать").performScrollTo().performClick()

        assertEquals("sk-or-v1-abcdef123456", shown())
    }

    @Test fun `Workers AI без номера аккаунта проверять не по чему`() {
        var checked: UserAiKey? = null
        keyScreen(onCheck = { checked = it }, openService = "Cloudflare")

        compose.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextInput("cf-token-0123456789")
        compose.onNodeWithText("Проверить и включить").performScrollTo().assertIsNotEnabled()

        compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextInput("0123abcd")
        compose.onNodeWithText("Проверить и включить").performScrollTo().performClick()

        assertEquals("https://api.cloudflare.com/client/v4/accounts/0123abcd/ai/v1", checked?.baseUrl)
    }

    @Test fun `в закрытой строке сервиса без ключа видна цена`() {
        keyScreen(openService = null)

        assertTrue(
            "куда идти за ключом, не видно, не раскрыв все двенадцать строк",
            compose.onAllNodes(androidx.compose.ui.test.hasText(com.point.core.flow.AI_PAID_OFFER)).fetchSemanticsNodes().isNotEmpty(),
        )
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

        compose.onNodeWithText("квота на сейчас", substring = true).performScrollTo().assertIsDisplayed()

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

    @Test fun `пришедшему с действия Point называет, куда идти за ключом`() {
        keyScreen(errand = errand, openService = null)

        val first = com.point.core.flow.keyErrandService(UserAiKeys.NONE)!!
        compose.onNodeWithText("Открыть сайт ${first.name}").performScrollTo().assertIsDisplayed()
    }

    @Test fun `за ключом для записи Point посылает туда, где её расшифруют`() {
        keyScreen(errand = errand.copy(kind = ObjectKind.AUDIO), openService = null)

        compose.onNodeWithText("Открыть сайт Groq").performScrollTo().assertIsDisplayed()
    }

    @Test fun `дверь обратно стоит под приговором раскрытого сервиса — одна`() {
        keyScreen(
            keys = savedKey,
            errand = errand,
            verdict = KeyVerdict.Works("Готово"),
            openService = openRouter.name,
        )

        // Одна дверь: найдись их две, onNodeWithText упал бы.
        compose.onNodeWithText("Вернуться к «чек.jpg»").performScrollTo().assertIsDisplayed()
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
