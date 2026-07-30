package com.point.data

import com.point.core.flow.ClipFail
import com.point.core.flow.ClipPush
import com.point.core.flow.ClipboardPayload
import com.point.core.flow.PcPairing
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Пути релей-клиента, различимые без живого сервера; сетевые пути кроет RelayClipPollerLiveTest. */
class RelayPcClipboardSyncTest {

    // Порт 1 на loopback: соединение мгновенно отваливается, если код всё же дойдёт до сети.
    private val pairing = PcPairing("h", 1, "token", relay = "https://127.0.0.1:1")

    /**
     * #272, флагманский кейс: блоб больше лимита релея отвергается ДО сети. relay.py отвечает 413
     * по одному Content-Length, не читая тело, — streaming-write клиента умирал на RST задолго до
     * чтения кода ответа, и «слишком большой» превращался в ложный «Компьютер недоступен».
     */
    @Test
    fun `an over-cap payload fails as TOO_BIG before touching the network`() = runTest {
        val big = ClipboardPayload("image/png", "huge.png", ByteArray(51 * 1024 * 1024))

        val result = RelayPcClipboardSync("secret").push(pairing, big)

        assertEquals(ClipPush.Failed(ClipFail.TOO_BIG), result)
    }
}
