package com.point.executors

import com.point.core.flow.CloudOcrCapability

import com.point.core.flow.capabilities.OcrCapability
import com.point.core.flow.cloudDestination
import com.point.core.model.CapabilityId
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudDestinationNamesRealCapabilitiesTest {

    private val fallback = cloudDestination(CapabilityId("что-то, чего нет"))

    @Test
    fun `«Распознать текст» говорит про распознавание, а не про AI вообще`() {
        val text = cloudDestination(OcrCapability.ID)
        assertNotEquals("показано умолчание вместо своего текста", fallback, text)
        assertTrue(text, text.contains("распознавания"))
    }

    @Test
    fun `«Распознать в облаке» получает свой текст, а не общее умолчание`() {
        val text = cloudDestination(CloudOcrCapability.ID)
        assertNotEquals("показано умолчание вместо своего текста", fallback, text)
        assertTrue(text, text.contains("распознавания"))
    }

    @Test
    fun `оба вопроса про распознавание молчат о том, кого выбрал Point`() {
        listOf(OcrCapability.ID, CloudOcrCapability.ID).forEach { id ->
            val text = cloudDestination(id)
            assertTrue("${id.value}: $text", !text.contains("Mistral"))
            assertTrue("${id.value}: $text", !text.contains("Куда можно отправлять"))

            assertTrue("${id.value}: $text", text.contains("уйдёт") && text.contains("вернётся"))
        }
    }
}
