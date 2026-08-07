package com.point.core.flow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
