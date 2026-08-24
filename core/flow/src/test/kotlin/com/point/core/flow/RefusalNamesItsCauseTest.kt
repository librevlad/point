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

    /**
     * Сервер ответил отказом и текста не прислал (#1077).
     *
     * Тело карточки, владелец 18.08.2026: «Сервер Point не ответил» уместно только когда ответа
     * действительно не было. Прежде 502 без текста звался молчанием сервера — неправда: разговор
     * состоялся. Человек шёл проверять связь и сервер вместо того, чтобы повторить позже.
     */
    @Test
    fun `сервер ответил отказом без текста — это не молчание сервера`() {
        val said = dropOpenRefusal(status = 502, serverMessage = null, online = true)

        assertEquals(SERVER_REFUSED_TEXT, said)
        assertNotEquals("разговор состоялся — молчанием его звать нельзя", NO_SERVER_TEXT, said)
        assertTrue(said, "интернет" !in said.lowercase())
    }

    /** Ответ пришёл неразборным (`responseCode` = -1) — это тоже не молчание сервера (#1077). */
    @Test
    fun `ответ неразборный — сказано про непонятный ответ, а не про молчание`() {
        val said = dropOpenRefusal(status = -1, serverMessage = null, online = true)

        assertEquals(ODD_ANSWER_TEXT, said)
        assertNotEquals(NO_SERVER_TEXT, said)
    }

    /**
     * Молчание сервера осталось при своём имени — там, где разговор и правда не состоялся
     * (#1077): вызов сорвался `IOException`-ом, наружу ничего не дошло.
     */
    @Test
    fun `молчание сервера зовётся молчанием там, где разговора не было`() {
        assertEquals(NO_SERVER_TEXT, dropCallBroke(java.net.ConnectException("refused")))
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
