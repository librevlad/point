package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отказ приёма файла называет свою причину (#729).
 *
 * Живой прогон 10.08.2026: «Принять файл» отвечало «нет связи с сервером Point», хотя связь
 * была — телефон в ту же секунду опрашивал почту, а сервер отвечал 507: у аккаунта уже пять
 * открытых ящиков. Человек шёл проверять Wi-Fi там, где чинить нужно было другое.
 *
 * Сервер при этом присылал готовый человеческий текст — и текст выбрасывался.
 */
class RefusalNamesItsCauseTest {

    private val fromServer = "Больше 5 открытых ящиков приёма сразу не бывает"

    @Test
    fun `нет сети — это про сеть`() {
        assertEquals(NO_NETWORK_TEXT, dropOpenRefusal(status = 0, serverMessage = null, online = false))
    }

    @Test
    fun `слова сервера доходят до человека, а не выбрасываются`() {
        val said = dropOpenRefusal(status = 507, serverMessage = fromServer, online = true)

        assertTrue(said, fromServer in said)
    }

    @Test
    fun `упёрлись в предел — сказано, что делать`() {
        val said = dropOpenRefusal(status = 507, serverMessage = fromServer, online = true)

        assertTrue(said, "сутки" in said)
    }

    @Test
    fun `предел, нет пропуска и нет связи звучат по-разному`() {
        val full = dropOpenRefusal(507, fromServer, online = true)
        val unknown = dropOpenRefusal(401, "Это устройство не в аккаунте", online = true)
        val offline = dropOpenRefusal(0, null, online = false)

        assertNotEquals(full, unknown)
        assertNotEquals(unknown, offline)
        assertNotEquals(full, offline)
    }

    @Test
    fun `сервер промолчал — виноват сервер, а не связь человека`() {
        val said = dropOpenRefusal(status = 502, serverMessage = null, online = true)

        assertEquals(NO_SERVER_TEXT, said)
        assertTrue(said, "интернет" !in said.lowercase())
    }

    @Test
    fun `при живой связи про связь не говорим`() {
        listOf(
            dropOpenRefusal(507, fromServer, online = true),
            dropOpenRefusal(401, "Это устройство не в аккаунте", online = true),
            dropOpenRefusal(500, null, online = true),
        ).forEach { said ->
            assertTrue(said, "нет связи" !in said.lowercase())
        }
    }
}
