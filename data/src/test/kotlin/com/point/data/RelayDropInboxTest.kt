package com.point.data

import com.point.core.flow.DropInboxBox
import com.point.core.flow.DropWait
import com.point.core.flow.NO_NETWORK_TEXT
import com.point.core.flow.NetworkAvailability
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayDropInboxTest {

    // Адрес нарочно недостижим (порт 1) — без сети до него не должно дойти вообще,
    // никакая настоящая попытка соединения тут не нужна и не случится (#690, #691).
    private fun inbox(network: NetworkAvailability) =
        RelayDropInbox("https://127.0.0.1:1", { "pass" }, network)

    @Test
    fun `на телефоне нет сети — ссылку не готовим, соединение не открываем`() = runTest {
        val box = inbox(NetworkAvailability { false }).open()

        assertNull("без сети ссылки быть не должно", box)
    }

    @Test
    fun `на телефоне нет сети — ожидание файла говорит об этом честно`() = runTest {
        val outcome = inbox(NetworkAvailability { false })
            .await(DropInboxBox("box", "https://x/u/box")) { "unused" }

        assertEquals(DropWait.Failed(NO_NETWORK_TEXT), outcome)
    }
}
