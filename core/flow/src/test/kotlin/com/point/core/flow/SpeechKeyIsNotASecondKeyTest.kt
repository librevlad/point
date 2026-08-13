package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ключ расшифровки — не второй ключ, а тот же из очереди (#912).
 *
 * Владелец: «что за ключи для расшифровки речи? пользователю непонятно». На телефоне такого
 * поля нет вовсе — расшифровка берёт ключ Groq из общей очереди. Компьютер спрашивал его
 * отдельной строкой, и человек, уже вписавший Groq выше, не понимал, что у него просят ещё
 * раз и где это взять.
 */
class SpeechKeyIsNotASecondKeyTest {

    @Test
    fun `расшифровка берёт ключ из очереди, а не из своего поля`() {
        val keys = UserAiKeys.NONE.with(UserAiKey(GROQ_PROVIDER_ID, "sk-groq"))

        assertEquals("sk-groq", speechKeyFromChain(keys)?.apiKey)
    }

    @Test
    fun `когда Groq нет, годится OpenAI — их умеют оба`() {
        val keys = UserAiKeys.NONE.with(UserAiKey("openai", "sk-openai"))

        assertEquals("sk-openai", speechKeyFromChain(keys)?.apiKey)
    }

    @Test
    fun `чужой сервис расшифровкой не притворяется`() {
        val keys = UserAiKeys.NONE.with(UserAiKey("mistral", "sk-mistral"))

        assertNull("расшифровку умеют не все", speechKeyFromChain(keys))
    }

    @Test
    fun `кого просить, сказано именами, а не строкой конфига`() {
        val who = speechProviderNames()

        assertTrue(who, who.contains("Groq"))
        assertTrue(who, who.contains("OpenAI"))
    }
}
