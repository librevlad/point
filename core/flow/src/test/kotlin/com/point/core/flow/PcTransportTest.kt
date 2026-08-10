package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PcTransportTest {

    @Test
    fun `нет сети — телефон говорит это своими словами, а не про сервер`() {
        val text = pcUnreachableText(PcUnreachable.NO_NETWORK)

        assertEquals(NO_NETWORK_TEXT, text)
        assertNotEquals(pcUnreachableText(PcUnreachable.SERVER_SILENT), text)
    }

    @Test
    fun `у каждой причины свои слова`() {
        val texts = PcUnreachable.entries.map { pcUnreachableText(it) }

        assertEquals("причина потерялась в одинаковых словах", texts.size, texts.toSet().size)
    }
}
