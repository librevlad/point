package com.point.core.flow

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Три беды приёма файла говорят тремя разными словами (#797, решение владельца 11.08.2026:
 * «дать каждой беде имя»).
 *
 * Живой прогон 11.08.2026: «Принять файл» дважды ответило «Сервер Point не ответил» при живом
 * сервере (`/health` → 200) и телефоне в круге устройств («на связи»). Одна фраза покрывала
 * три разных положения — какая ветка сработала, установить было нечем: ни человеку, ни нам.
 */
class EveryRefusalHasItsOwnNameTest {

    private fun inbox(url: String?, pass: String?) = HttpDropInbox(
        serverUrl = { url },
        pass = { pass },
        network = NetworkAvailability { true },
    )

    private fun refusalOf(url: String?, pass: String?): String {
        val answer = runBlocking { inbox(url, pass).open() }

        return (answer as DropOpen.Refused).reason
    }

    @Test
    fun `нет адреса сервера — своё имя`() {
        assertEquals(NO_SERVER_ADDRESS_TEXT, refusalOf(url = null, pass = "pass"))
    }

    @Test
    fun `нет пропуска — человеку сказано про аккаунт, а не про сервер`() {
        val said = refusalOf(url = "https://point.example", pass = null)

        assertEquals(NOT_IN_ACCOUNT_TEXT, said)
        assertTrue("про сервер тут ни слова", "ервер" !in said)
    }

    @Test
    fun `три беды — три разных текста`() {
        val names = setOf(NO_SERVER_ADDRESS_TEXT, NOT_IN_ACCOUNT_TEXT, ODD_ANSWER_TEXT, NO_LINK_TEXT, NO_SERVER_TEXT)

        assertEquals("одинаковых имён нет", 5, names.size)
    }

    @Test
    fun `нет сети — по-прежнему про сеть, а не про аккаунт`() {
        val offline = HttpDropInbox(
            serverUrl = { "https://point.example" },
            pass = { "pass" },
            network = NetworkAvailability { false },
        )

        val answer = runBlocking { offline.open() }

        assertEquals(NO_NETWORK_TEXT, (answer as DropOpen.Refused).reason)
    }
}
