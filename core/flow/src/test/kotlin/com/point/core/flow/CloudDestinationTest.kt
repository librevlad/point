package com.point.core.flow

import com.point.core.model.CapabilityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Согласие спрашивают, называя настоящего адресата.
 *
 * Диалог говорил про «сервер AI-провайдера» одинаково для всего сетевого. На «Дать ссылку» это
 * прямая неправда: файл уезжает на релей Point и лежит по ссылке сутки. Просить доверия, называя
 * не того, кому отдаёшь файл, — худший способ его потратить.
 */
class CloudDestinationTest {

    @Test
    fun `ссылка честно говорит, что файл будет доступен по ней и сколько`() {
        val text = cloudDestination(CapabilityId("drop-link"))
        assertTrue(text, text.contains("ссылке"))
        assertTrue("не сказано, сколько живёт: $text", text.contains("суток"))
        assertTrue("назван чужой адресат: $text", !text.contains("AI-провайдера"))
    }

    /**
     * Имена здесь — **настоящие идентификаторы возможностей** (`OcrCapability.ID`,
     * `CloudOcrCapability.ID`), а не удобные для теста. Прежняя редакция передавала «cloud-ocr»,
     * которого в приложении нет, и потому зелёный тест сосуществовал с мёртвой веткой: человеку
     * показывали умолчание про «сервер AI-провайдера». Сцепку имён стережёт
     * `CloudDestinationNamesRealCapabilitiesTest` в `:executors`, где эти константы видны.
     */
    @Test
    fun `облачное распознавание говорит про распознавание, а не про AI вообще`() {
        listOf("ocr", "ocr-cloud").forEach { id ->
            val text = cloudDestination(CapabilityId(id))
            assertTrue("«$id»: $text", text.contains("распознавания"))
            assertTrue("«$id»: не назван первый адресат — $text", text.contains("Mistral"))
            assertTrue("«$id»: не сказано, что это Европа — $text", text.contains("ЕС"))
            assertTrue("«$id»: показано умолчание вместо своего текста — $text", !text.contains("AI-провайдера"))
        }
    }

    /**
     * Адресат AI-ветки назван поимённо, потому что Point его знает (#538).
     *
     * «Сервер AI-провайдера» — это класс адресатов, а не адресат. Человек выбрал сервис на экране
     * ключа своими руками, вписал его ключ — и в момент, когда у него спрашивают согласие отдать
     * объект, Point отвечал так, будто не знает куда. Приватность в проекте — граница важнее
     * удобства, и «видно, КУДА уходит объект» держится именно здесь.
     */
    @Test
    fun `AI-ветка называет выбранный сервис по имени`() {
        val text = cloudDestination(CapabilityId("ai"), aiService = "OpenRouter")
        assertTrue("адресат не назван: $text", text.contains("OpenRouter"))
        assertTrue("имя есть, а класс адресатов остался: $text", !text.contains("AI-провайдера"))
        assertTrue("человеку не сказали, что решение за ним: $text", text.contains("согласия"))
    }

    /** Имя приходит из каталога, и там есть не все: у своего прокси имени нет — врать нечем. */
    @Test
    fun `без известного имени остаётся честное умолчание про AI-провайдера`() {
        val text = cloudDestination(CapabilityId("ai"))
        assertTrue(text, text.contains("AI-провайдера"))
    }

    /** Имя AI-сервиса не имеет права подменить адресата у тех веток, у кого он свой. */
    @Test
    fun `ссылка и распознавание не начинают называть AI-сервис`() {
        listOf("drop-link", "ocr", "ocr-cloud").forEach { id ->
            val text = cloudDestination(CapabilityId(id), aiService = "OpenRouter")
            assertTrue("«$id» назвал чужого адресата: $text", !text.contains("OpenRouter"))
        }
    }

    @Test
    fun `в каждом случае сказано, что без согласия ничего не уходит`() {
        listOf("drop-link", "ocr", "ocr-cloud", "ai", "translate").forEach { id ->
            val text = cloudDestination(CapabilityId(id))
            assertTrue(
                "«$id»: человеку не сказали, что решение за ним — $text",
                text.contains("согласия") || text.contains("ссылке"),
            )
        }
    }

    // --- Разные обещания — разные «да» (#114) ---

    /**
     * «Показать модели» и «выложить в открытый доступ» — не одно согласие.
     *
     * Один флаг на всё означал: человек, разрешивший «Понять», тем же тапом навсегда разрешил
     * класть свои файлы на сервер открытыми. Разница между обещаниями живёт здесь, в чистой
     * функции, а не в памяти того, кто писал экран.
     */
    @Test
    fun `выкладывание по ссылке — отдельное обещание, и оно не запоминается`() {
        assertEquals(CloudScope.PUBLIC_LINK, cloudScopeOf(CapabilityId("drop-link")))
        assertEquals(CloudScope.MODELS, cloudScopeOf(CapabilityId("ai")))
        assertEquals(CloudScope.MODELS, cloudScopeOf(CapabilityId("cloud-ocr")))

        assertTrue("про модели спрашивают один раз", remembersConsent(CloudScope.MODELS))
        assertTrue(
            "разрешение выложить один файл не отвечает за следующий",
            !remembersConsent(CloudScope.PUBLIC_LINK),
        )
    }

    @Test
    fun `вопрос про открытую ссылку звучит не как вопрос про облако`() {
        val open = cloudAskTitle(CloudScope.PUBLIC_LINK)
        assertTrue(open, open.contains("ссылке"))
        assertTrue("а кнопка обещает ровно то, что произойдёт", cloudAskConfirm(CloudScope.PUBLIC_LINK) == "Выложить")

        assertTrue(cloudAskTitle(CloudScope.MODELS).contains("облако"))
        assertEquals("Разрешить", cloudAskConfirm(CloudScope.MODELS))
    }
}
