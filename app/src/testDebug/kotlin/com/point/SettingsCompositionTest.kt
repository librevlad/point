package com.point

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.point.core.flow.AI_PROVIDERS
import com.point.core.flow.AiFact
import com.point.core.flow.AiOutcome
import com.point.core.flow.UserAiKey
import com.point.core.flow.UserAiKeys
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
    private val groq = AI_PROVIDERS.first { it.id == "groq" }
    private val saved = UserAiKeys.NONE.with(UserAiKey(openRouter.id, "sk-or-v1-abcdef123456", model = "gemma"))

    private fun screen(
        keys: UserAiKeys = UserAiKeys.NONE,
        builtIn: Set<String> = emptySet(),
        facts: Map<String, AiFact> = emptyMap(),
        onOpenUrl: (String) -> Unit = {},
        onCheck: (UserAiKey) -> Unit = {},
        onCheckAll: () -> Unit = {},
        openKey: Boolean = true,
    ) {
        compose.setContent {
            PointTheme(darkTheme = true) {
                KeyScreen(
                    screen = aiKeysScreenOf(keys = keys, builtIn = builtIn, facts = facts),
                    onSave = {},
                    onCancel = {},
                    onOpenUrl = onOpenUrl,
                    onCheck = onCheck,
                    onCheckAll = onCheckAll,
                )
            }
        }
        if (openKey) compose.onNodeWithText("Ключи AI").performClick()
    }

    @Test fun `в разделе стоят все известные сервисы, а не только те, где есть ключ`() {
        screen(keys = saved)

        AI_PROVIDERS.forEach { compose.onNodeWithText(it.name).performScrollTo().assertIsDisplayed() }
    }

    @Test fun `сервисы идут в том порядке, в каком Point к ним обращается`() {
        screen()

        val order = AI_PROVIDERS.map { it.name }
        order.zipWithNext { above, below ->
            compose.onNodeWithText(above).performScrollTo()
            compose.onNodeWithText(below).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun `строка сервиса говорит, что он умеет`() {
        screen()

        compose.onNodeWithText(openRouter.what, substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `поля ключа нет, пока человек не открыл строку сервиса`() {
        screen(keys = saved)

        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)

        compose.onNodeWithText(openRouter.name).performScrollTo().performClick()

        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
    }

    @Test fun `модель и адрес свёрнуты внутри строки сервиса`() {
        screen(keys = saved)
        compose.onNodeWithText(openRouter.name).performScrollTo().performClick()

        compose.onNodeWithText("Модель и адрес").performScrollTo().performClick()

        compose.onAllNodes(hasSetTextAction()).assertCountEquals(3)
    }

    @Test fun `ссылка на страницу сервиса называет его и ведёт туда`() {
        var opened: String? = null
        screen(keys = saved, onOpenUrl = { opened = it })
        compose.onNodeWithText(openRouter.name).performScrollTo().performClick()

        compose.onNodeWithText("Открыть сайт ${openRouter.name}").performScrollTo().performClick()

        assertEquals(openRouter.keyUrl, opened)
    }

    @Test fun `свой ключ виден хвостом, не открывая ключа целиком`() {
        screen(keys = saved)

        compose.onNodeWithText("ваш ключ", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("sk-o…3456", substring = true).assertExists()
    }

    @Test fun `сервис на ключе Point не выдаёт его за ключ человека`() {
        screen(builtIn = setOf(groq.id))

        compose.onNodeWithText("работает на ключе Point", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `сервис без ключа честно говорит, что молчит`() {
        screen()

        compose.onAllNodesWithText("этот сервис молчит", substring = true).onFirst()
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `«Проверить все» стоит на экране и ждёт тапа человека`() {
        var asked = false
        screen(onCheckAll = { asked = true })

        compose.onNodeWithText("Проверить все").performScrollTo().performClick()

        assertEquals(true, asked)
    }

    @Test fun `пока не проверяли — так и написано`() {
        screen()

        compose.onNodeWithText("Ещё не проверяли", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `последний факт стоит в строке своего сервиса`() {
        val now = System.currentTimeMillis()
        screen(
            builtIn = setOf(groq.id),
            facts = mapOf(groq.id to AiFact(AiOutcome.LIMIT, now - 3 * 60_000)),
        )

        compose.onNodeWithText("лимит исчерпан 3 минуты назад", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `у сервиса без обращений факта нет, и он этим не притворяется`() {
        screen()

        compose.onAllNodesWithText("ещё не обращались", substring = true).onFirst()
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `про «работает» строка сервиса молчит — это знает только сам сервис`() {
        screen(keys = saved)

        compose.onNodeWithText("Работает", substring = true).assertDoesNotExist()
    }

    @Test fun `бывший склад разбит на названные разделы`() {
        screen(keys = saved, openKey = false)

        compose.onNodeWithText("Отправка и приватность").assertIsDisplayed()
        compose.onNodeWithText("Звук действий").assertIsDisplayed()

        compose.onNodeWithText("Звук действий").performClick()
        compose.onNodeWithText("Поведение Point").assertIsDisplayed()
    }

    @Test fun `экран, открытый с чужого сервиса, не путает поля ключей`() {
        var checked: UserAiKey? = null
        screen(keys = saved, onCheck = { checked = it })

        compose.onNodeWithText(groq.name).performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction()).onFirst().performScrollTo()
        compose.onNodeWithText("Проверить и включить").performScrollTo()

        compose.onNodeWithText("Ключ ${groq.name}").performScrollTo().assertIsDisplayed()
        assertEquals(null, checked)
    }
}
