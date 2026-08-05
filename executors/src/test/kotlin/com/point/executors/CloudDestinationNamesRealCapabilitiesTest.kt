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
    fun `«Распознать в облаке» получает свой текст, а не общее умолчание`() {
        val text = cloudDestination(CloudOcrCapability.ID)
        assertNotEquals("показано умолчание вместо своего текста", fallback, text)
        assertTrue(text, text.contains("распознавания"))
    }

    /**
     * Имени того, кого выбрал Point, здесь больше нет — и это не потеря сцепки, а #560.
     *
     * Раньше тест требовал обратного: «называет первого адресата поимённо», `Mistral`, `ЕС`. На
     * телефоне это прочиталось так, как и было написано, — рассказом про нашу кухню в секунду,
     * когда человек решает, отпускать ли снимок. Владелец: «мне не интересно, что Mistral во
     * Франции и кто там вообще читает». Сцепка имён, ради которой этот файл заведён, осталась
     * целой: она проверяется тем, что у способности **свой** текст, а не умолчание.
     */
    @Test
    fun `оба вопроса про распознавание молчат о том, кого выбрал Point`() {
        listOf(OcrCapability.ID, CloudOcrCapability.ID).forEach { id ->
            val text = cloudDestination(id)
            assertTrue("${id.value}: $text", !text.contains("Mistral"))
            assertTrue("${id.value}: $text", !text.contains("Куда можно отправлять"))
            // Осталась ровно одна мысль: что уходит и что вернётся.
            assertTrue("${id.value}: $text", text.contains("уйдёт") && text.contains("вернётся"))
        }
    }
}
