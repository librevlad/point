package com.point.desktop.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.point.core.flow.AiKeyCheck
import com.point.core.flow.KeyProbe
import com.point.core.flow.UserAiConfig
import com.point.core.flow.UserAiKey
import com.point.core.flow.UserAiKeys
import com.point.desktop.PcConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Ключи на компьютере — та же дорога, что на телефоне.
 *
 * Прежде компьютер сохранял ключ до проверки, а после отказа стирал его (#1080): сорвавшаяся
 * связь или исчерпанный лимит стирали и верный ключ. Ключ из буфера человек вставлял в строку
 * сервиса, которую сначала искал среди двенадцати.
 */
class KeysOnPcTest {

    @get:Rule val compose = createComposeRule()

    private class FakeCheck(var probe: KeyProbe = KeyProbe(status = 200, reply = "Готово")) : AiKeyCheck {
        var asked: UserAiConfig? = null
        override suspend fun check(config: UserAiConfig): KeyProbe {
            asked = config
            return probe
        }
    }

    private var saved: PcConfig? = null

    private fun keys(check: FakeCheck, config: PcConfig = PcConfig(name = "ПК"), clipboard: String? = null) {
        compose.setContent {
            SettingsKeys(config = config, onSave = { saved = it }, keyCheck = check, readClipboard = { clipboard })
        }
    }

    @Test
    fun `ключ из буфера сам встаёт к своему сервису и сохраняется после ответа`() {
        val check = FakeCheck()
        keys(check, clipboard = "gsk_0123456789abcdefghij")

        compose.onNodeWithText(com.point.core.flow.PASTE_ANY_KEY).performClick()
        compose.waitForIdle()

        assertEquals("https://api.groq.com/openai/v1", check.asked?.baseUrl)
        assertEquals("gsk_0123456789abcdefghij", saved?.aiKeys?.keyFor("groq"))
    }

    @Test
    fun `ключ, который сервис не пустил, своим не становится`() {
        val check = FakeCheck(KeyProbe(status = 401))
        keys(check, clipboard = "gsk_0123456789abcdefghij")

        compose.onNodeWithText(com.point.core.flow.PASTE_ANY_KEY).performClick()
        compose.waitForIdle()

        assertNull("отвергнутый ключ встал в «Ваши ключи»", saved)
        compose.onNodeWithText("Ключ не подошёл").assertExists()
    }

    @Test
    fun `сорвавшаяся проверка не стирает вписанный ключ`() {
        val mine = UserAiKeys.NONE.with(UserAiKey("groq", "gsk_старый-рабочий-ключ"))
        val check = FakeCheck(KeyProbe(status = 429))
        keys(check, config = PcConfig(name = "ПК", aiKeys = mine), clipboard = "gsk_новый-ключ-0123456789")

        compose.onNodeWithText(com.point.core.flow.PASTE_ANY_KEY).performClick()
        compose.waitForIdle()

        assertNull("лимит исчерпан — а ключ стёрт", saved)
        compose.onNodeWithText("квота", substring = true).assertExists()
    }

    @Test
    fun `ключ без приметы — компьютер спрашивает, чей он`() {
        val check = FakeCheck()
        keys(check, clipboard = "0123456789abcdef0123456789abcdef")

        compose.onNodeWithText(com.point.core.flow.PASTE_ANY_KEY).performClick()
        compose.onNodeWithText(com.point.core.flow.WHOSE_KEY_TITLE).assertExists()
        assertNull("ключ ушёл на пробу наугад", check.asked)

        // Первая — кнопка в вопросе «чей?»: строка сервиса стоит ниже, в очереди.
        compose.onAllNodesWithText("Mistral").onFirst().performClick()
        compose.waitForIdle()

        assertEquals("https://api.mistral.ai/v1", check.asked?.baseUrl)
    }

    @Test
    fun `проверить ключи можно и на компьютере`() {
        val mine = UserAiKeys.NONE.with(UserAiKey("groq", "gsk_0123456789abcdefghij"))
        val check = FakeCheck()
        keys(check, config = PcConfig(name = "ПК", aiKeys = mine))

        compose.onNodeWithText("Проверить все").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Отвечают все 1", substring = true).assertExists()
    }
}
