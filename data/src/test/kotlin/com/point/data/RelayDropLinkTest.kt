package com.point.data

import com.point.core.flow.NetworkAvailability
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class RelayDropLinkTest {

    @Test
    fun `на телефоне нет сети — до пароля устройства не доходим, соединение не открываем`() = runTest {
        var passCalls = 0

        // Адрес нарочно недостижим — если бы гейт не сработал, дело всё равно не
        // должно было дойти даже до чтения пароля устройства, не то что до сети
        // (#690, #691).
        val result = RelayDropLink(
            "https://127.0.0.1:1",
            { passCalls++; "pass" },
            NetworkAvailability { false },
        ).give("/nonexistent", "x.txt", "text/plain")

        assertTrue("без сети ссылки быть не должно", result is com.point.core.flow.DropOutcome.Refused)
        assertEquals(
            "отказ не назвал причину, которую человек может устранить",
            com.point.core.flow.NO_NETWORK_TEXT,
            (result as com.point.core.flow.DropOutcome.Refused).why,
        )
        assertTrue(
            "отказ перечисляет догадки вместо причины",
            !result.why.contains(" или ") && !result.why.contains("учтите"),
        )
        assertEquals("офлайн до пароля устройства дело не должно доходить", 0, passCalls)
    }
    /**
     * Каждая причина отказа доезжает своей (#1284).
     *
     * Пять разных условий уходили одним `null`, и человек читал перечисление догадок: «нет
     * связи с сервером или файл слишком большой» — при живом интернете и файле в 880 раз
     * меньше предела. Список — не причина: из него нечего выбрать и нечего сделать.
     */
    @Test
    fun `нет аккаунта — сказано про аккаунт, а не про сеть и размер`() = runTest {
        val result = RelayDropLink("https://point.leerio.app", { null }, NetworkAvailability { true })
            .give("/nonexistent", "x.txt", "text/plain")

        assertEquals(
            com.point.core.flow.capabilities.NEEDS_ACCOUNT_FOR_LINK,
            (result as com.point.core.flow.DropOutcome.Refused).why,
        )
    }

    @Test
    fun `файла нет на диске — сказано про файл`() = runTest {
        val result = RelayDropLink("https://point.leerio.app", { "pass" }, NetworkAvailability { true })
            .give("/точно-нет-такого-файла", "x.txt", "text/plain")

        assertEquals(
            com.point.core.flow.NO_FILE_FOR_LINK,
            (result as com.point.core.flow.DropOutcome.Refused).why,
        )
    }

    /** Предел назван числом — и назван до того, как байты куда-то поехали. */
    @Test
    fun `файл тяжелее предела — назван его вес и предел, до отправки`() = runTest {
        val heavy = java.io.File.createTempFile("heavy-", ".bin").apply {
            deleteOnExit()
            java.io.RandomAccessFile(this, "rw").use { it.setLength(com.point.core.flow.MAX_DROP_BYTES + 1) }
        }

        val result = RelayDropLink("https://127.0.0.1:1", { "pass" }, NetworkAvailability { true })
            .give(heavy.absolutePath, "x.bin", "application/octet-stream")

        val why = (result as com.point.core.flow.DropOutcome.Refused).why
        assertTrue("предел не назван числом: $why", why.contains("МБ"))
        assertTrue("вес файла не назван: $why", why.contains("51"))
    }

}
