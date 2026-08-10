package com.point.data

import com.point.core.flow.NetworkAvailability
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

        assertNull("без сети ссылки быть не должно", result)
        assertEquals("офлайн до пароля устройства дело не должно доходить", 0, passCalls)
    }
}
