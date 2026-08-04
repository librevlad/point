package com.point.executors

import com.point.core.flow.cloudDestination
import com.point.core.model.CapabilityId
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сцепка имён: текст согласия обязан находить **настоящие** возможности, а не похожие строки.
 *
 * Живой прецедент, который этот тест закрывает. В `cloudDestination` стояли `"cloud-ocr"` и
 * `"cloud-ocr-direct"`, а объявлены в приложении `"ocr"` и `"ocr-cloud"`. Ветка не срабатывала
 * никогда: человеку, тапнувшему «Распознать в облаке», показывали умолчание про «сервер
 * AI-провайдера». Соседний тест в `:core:flow` был зелёным, потому что сам передавал те же
 * несуществующие имена — сверять строку со строкой значит проверять свою же опечатку.
 *
 * Отсюда и место теста: только в `:executors` видны сами константы, и только здесь опечатка в
 * имени не может пережить сборку.
 */
class CloudDestinationNamesRealCapabilitiesTest {

    private val fallback = cloudDestination(CapabilityId("что-то, чего нет"))

    @Test
    fun `«Распознать текст» говорит про распознавание, а не про AI вообще`() {
        val text = cloudDestination(OcrCapability.ID)
        assertNotEquals("показано умолчание вместо своего текста", fallback, text)
        assertTrue(text, text.contains("распознавания"))
    }

    @Test
    fun `«Распознать в облаке» называет первого адресата поимённо`() {
        val text = cloudDestination(CloudOcrCapability.ID)
        assertNotEquals("показано умолчание вместо своего текста", fallback, text)
        // Куда именно уходит снимок — Mistral, Франция (ЕС). Обещание, которое мы держим:
        // это первый в очереди, и он же единственный на уровне «только Европа».
        assertTrue(text, text.contains("Mistral"))
        assertTrue(text, text.contains("ЕС"))
    }

    @Test
    fun `человеку сказано, что решение за ним, и где это настраивается`() {
        listOf(OcrCapability.ID, CloudOcrCapability.ID).forEach { id ->
            val text = cloudDestination(id)
            assertTrue("${id.value}: $text", text.contains("согласия"))
            assertTrue("${id.value}: $text", text.contains("Куда можно отправлять"))
        }
    }
}
