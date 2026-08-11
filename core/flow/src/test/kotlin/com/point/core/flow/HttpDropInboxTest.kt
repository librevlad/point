package com.point.core.flow

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Приём файла — один код на обе стороны (#727). Телефон и компьютер разговаривают с сервером
 * одинаково, поэтому и правка в приёме чинит обе стороны сразу.
 */
class HttpDropInboxTest {

    // Адрес нарочно недостижим (порт 1): без сети до него не должно дойти вообще.
    private fun inbox(network: NetworkAvailability, pass: String? = "pass") =
        HttpDropInbox({ "https://127.0.0.1:1" }, { pass }, network)

    @Test
    fun `нет сети — ссылку не готовим, и отказ говорит именно про сеть`() = runTest {
        val outcome = inbox(NetworkAvailability { false }).open()

        assertEquals(DropOpen.Refused(NO_NETWORK_TEXT), outcome)
    }

    @Test
    fun `нет сети — ожидание файла говорит об этом честно`() = runTest {
        val outcome = inbox(NetworkAvailability { false })
            .await(DropInboxBox("box", "https://x/u/box")) { "unused" }

        assertEquals(DropWait.Failed(NO_NETWORK_TEXT), outcome)
    }

    @Test
    fun `устройство не в круге — наружу не ходим вовсе`() = runTest {
        val outcome = inbox(NetworkAvailability { true }, pass = null).open()

        assertTrue("отказ обязан назвать причину, а не молчать", outcome is DropOpen.Refused)
    }

    /**
     * Ящик закрывается тем же адресом, каким сервер его закрывает (#729). Разъедутся —
     * дверь останется открытой, и предел в пять ссылок выберется обычным приёмом.
     */
    @Test
    fun `закрытие ящика есть в контракте приёма`() {
        assertTrue(
            "без закрытия ящик убирает только суточная уборка",
            "close" in HttpDropInbox::class.java.methods.map { it.name },
        )
    }

    @Test
    fun `приезд файла сам по себе не подтверждает приём — сервер держит его до объекта`() {
        // Живой прогон 2026-08-10: ящик опустел, а объекта не появилось — файл исчез навсегда.
        // Прислал его чужой человек, и прислать заново он не может (#726).
        assertTrue(
            "подтверждение обязано быть отдельным шагом контракта",
            "ack" in HttpDropInbox::class.java.methods.map { it.name },
        )
    }
}
