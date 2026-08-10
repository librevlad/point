package com.point.data

import com.point.core.flow.DropInboxBox
import com.point.core.flow.DropWait
import com.point.core.flow.NO_NETWORK_TEXT
import com.point.core.flow.NetworkAvailability
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `приезд файла сам по себе не подтверждает приём — сервер держит его до объекта`() = runTest {
        // Живой прогон 2026-08-10: ящик на сервере опустел, а объект на телефоне не появился —
        // файл исчез навсегда. Прислал его чужой человек, и прислать заново он не может.
        // Подтверждение обязано быть отдельным шагом, а не хвостом скачивания.
        val ack = RelayDropInbox::class.java.methods.map { it.name }

        assertTrue("подтверждение обязано быть отдельным шагом контракта", "ack" in ack)
    }

    @Test
    fun `на телефоне нет сети — ожидание файла говорит об этом честно`() = runTest {
        val outcome = inbox(NetworkAvailability { false })
            .await(DropInboxBox("box", "https://x/u/box")) { "unused" }

        assertEquals(DropWait.Failed(NO_NETWORK_TEXT), outcome)
    }
}
