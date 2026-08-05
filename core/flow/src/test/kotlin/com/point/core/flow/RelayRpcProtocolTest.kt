package com.point.core.flow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чей ответ мы приняли — единственное место, где канал может начать врать.
 *
 * В ящик устройства может прилететь протухший ответ на прошлый запрос или просто мусор.
 * Принять первое попавшееся — значит показать человеку чужой результат как свой (#272).
 */
class RelayRpcProtocolTest {

    private fun reply(id: String?, kind: String = RelayRpc.REPLY): Map<String, String> =
        buildMap {
            put(RelayRpc.KIND, kind)
            id?.let { put(RelayRpc.ID, it) }
        }

    @Test
    fun `свой ответ принимается`() {
        assertTrue(isOurReply(reply("req-1"), requestId = "req-1"))
    }

    @Test
    fun `чужой ответ пропускается — он адресован другому запросу`() {
        assertFalse(isOurReply(reply("req-2"), requestId = "req-1"))
    }

    @Test
    fun `ответ без идентификатора принимается — это компьютер старой сборки`() {
        // Иначе «новый телефон + старый ПК» брикует связь навсегда: свежий ответ отбрасывался бы
        // до самого дедлайна.
        assertTrue(isOurReply(reply(null), requestId = "req-1"))
    }

    @Test
    fun `не ответ, а чей-то запрос — не наш`() {
        assertFalse(isOurReply(reply("req-1", kind = RelayRpc.CAPS), requestId = "req-1"))
    }

    @Test
    fun `пустая мета — не ответ`() {
        assertFalse(isOurReply(emptyMap(), requestId = "req-1"))
    }
}
