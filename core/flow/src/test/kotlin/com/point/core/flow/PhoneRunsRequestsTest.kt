package com.point.core.flow

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Телефон разбирает просьбы компьютера (#817, шаг 1).
 *
 * Связка была односторонней: телефон просил — компьютер делал. Обратно письмо доходило до
 * ящика и гибло, потому что телефон вычищал ящик перед каждой своей отправкой.
 */
class PhoneRunsRequestsTest {

    @get:Rule val temp = TemporaryFolder()

    private fun requests(
        seenFile: File = temp.newFile(),
        run: suspend (PhoneRequests.Asked) -> PhoneRequests.Answered,
    ) = PhoneRequests(SeenLetters(seenFile), run)

    private fun letter(action: String = "call", id: String = "req-1") = PcFrame(
        mapOf(
            RelayRpc.KIND to RelayRpc.RUN,
            RelayRpc.ID to id,
            RelayRpc.RUN_ACTION to action,
            RelayRpc.RUN_NAME to "Счёт 4417",
            RelayRpc.RUN_MIME to "text/plain",
        ),
        "+380671234567".toByteArray(),
    )

    @Test
    fun `просьба компьютера делается и получает ответ`() = runTest {
        var did: String? = null
        val said = "Позвонил"
        val phone = requests { asked -> did = asked.action; PhoneRequests.Answered(done = said) }

        val reply = phone.answer("blob-1", letter())

        assertEquals("call", did)
        assertEquals(RelayRpc.REPLY, reply?.get(RelayRpc.KIND))
        assertEquals("req-1", reply?.get(RelayRpc.ID))
        assertEquals(said, reply?.get(RelayRpc.RUN_DONE))
    }

    @Test
    fun `повторно принесённое письмо не делает работу дважды`() = runTest {
        var times = 0
        val file = temp.newFile()
        val phone = requests(file) { times++; PhoneRequests.Answered(done = "Позвонил") }

        phone.answer("blob-1", letter())
        val second = phone.answer("blob-1", letter())

        assertEquals("работа сделана дважды", 1, times)
        assertEquals(PhoneRequests.ALREADY_DONE, second?.get(RelayRpc.RUN_DONE))
    }

    @Test
    fun `не вышло — компьютер узнаёт причину, а не тишину`() = runTest {
        val why = "Нет приложения для звонка"
        val phone = requests { PhoneRequests.Answered(failed = why) }

        val reply = phone.answer("blob-2", letter())

        assertEquals(why, reply?.get(RelayRpc.RUN_FAILED))
        assertNull(reply?.get(RelayRpc.RUN_DONE))
    }

    @Test
    fun `сорвавшаяся работа тоже отвечает, а не молчит`() = runTest {
        val why = "диск умер"
        val phone = requests { error(why) }

        val reply = phone.answer("blob-3", letter())

        assertEquals(why, reply?.get(RelayRpc.RUN_FAILED))
    }

    @Test
    fun `чужое письмо не трогается`() = runTest {
        val phone = requests { PhoneRequests.Answered(done = "не должно случиться") }

        val reply = phone.answer("blob-4", PcFrame(mapOf(RelayRpc.KIND to RelayRpc.REPLY), ByteArray(0)))

        assertNull("ответ на наш же вопрос приняли за просьбу", reply)
    }

    @Test
    fun `просьба без действия просьбой не считается`() = runTest {
        val phone = requests { PhoneRequests.Answered(done = "не должно случиться") }

        val reply = phone.answer("blob-5", PcFrame(mapOf(RelayRpc.KIND to RelayRpc.RUN), ByteArray(0)))

        assertNull(reply)
    }

    @Test
    fun `чистка ящика показывает письмо, а не убивает его`() {
        val source = File("src/main/kotlin/com/point/core/flow/Mailbox.kt").readText()
        val drain = source.substringAfter("fun drain(").substringBefore("\n    }")

        assertTrue("чистка снова выбрасывает всё подряд", drain.contains("keep(letter)"))
    }

    @Test
    fun `уведомление называет и работу, и объект`() {
        val notice = phoneRequestNotice("позвонить", "Счёт 4417")

        assertTrue(notice, notice.contains("позвонить"))
        assertTrue(notice, notice.contains("Счёт 4417"))
    }
}
