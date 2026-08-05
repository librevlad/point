package com.point.core.flow

import com.point.core.model.CapabilityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Согласие спрашивают одной мыслью: что уходит и что вернётся.
 *
 * Две болезни лечатся здесь, и обе — про доверие.
 *
 * Первая (#388): диалог говорил про «сервер AI-провайдера» одинаково для всего сетевого. На «Дать
 * ссылку» это прямая неправда — файл уезжает на релей Point и лежит по ссылке сутки. Просить
 * доверия, называя не того, кому отдаёшь файл, — худший способ его потратить.
 *
 * Вторая (#560): вылечив первую, мы переусердствовали в другую сторону. В вопрос переехала наша
 * кухня — порядок цепочки, страна сервиса, его обещания, имя настройки. Владелец, увидев это на
 * телефоне: «мне не интересно, что Mistral во Франции и кто там вообще читает». В момент тапа у
 * человека один вопрос — **уходит или нет** и что он получит взамен.
 */
class CloudDestinationTest {

    /**
     * Имена сервисов, которых человек не выбирал: их подобрал Point — по замеру, по квоте, по
     * порядку в цепочке. Завтра порядок сменится, и человек об этом не узнает, потому что это не
     * его решение. В вопросе, который задаётся в момент действия, таким именам места нет.
     *
     * Список нарочно шире живой цепочки: он стережёт не сегодняшних троих, а само правило.
     */
    private val pickedByPoint = listOf(
        "Mistral", "OCR.space", "OVH", "Groq", "Gemini", "Qwen", "SambaNova", "Cerebras",
        "Tesseract", "Whisper",
    )

    @Test
    fun `ссылка честно говорит, что файл будет доступен по ней и сколько`() {
        val text = cloudDestination(CapabilityId("drop-link"))
        assertTrue(text, text.contains("ссылк"))
        assertTrue("не сказано, сколько живёт: $text", text.contains("сутк"))
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
            assertTrue("«$id»: показано умолчание вместо своего текста — $text", !text.contains("AI-провайдера"))
        }
    }

    // --- Вопрос — одна мысль: что уходит, что вернётся (#560) ---

    /**
     * Каждая ветка отвечает ровно на то, что человек спросил тапом.
     *
     * Проверяется не наличие красивых слов, а обе половины ответа: **что уходит** (снимок, объект,
     * файл) и **что вернётся** — либо что с ним станет, если не вернётся ничего («Дать ссылку»
     * ничего не приносит обратно, у неё вместо возврата срок жизни ссылки).
     */
    @Test
    fun `каждый вопрос говорит, что уходит и что вернётся`() {
        listOf("ocr", "ocr-cloud", "ai", "translate").forEach { id ->
            val text = cloudDestination(CapabilityId(id))
            assertTrue("«$id»: не сказано, что уходит — $text", text.contains("уйдёт"))
            assertTrue("«$id»: не сказано, что вернётся — $text", text.contains("вернётся"))
        }
        val link = cloudDestination(CapabilityId("drop-link"))
        assertTrue("не сказано, что уходит: $link", link.contains("уедет"))
    }

    /**
     * Имени сервиса, выбранного Point, в вопросе нет — ни в одной ветке, ни с известным именем
     * AI-сервиса, ни без него.
     *
     * Это и есть #560 дословно: кого Point выбирает из своей цепочки — наша кухня. Человек узнаёт
     * это после работы, в происхождении результата, и в настройке, куда он приходит именно за
     * выбором. А не в секунду, когда решает, отпускать ли снимок с телефона.
     */
    @Test
    fun `в вопросе нет имени сервиса, который Point выбрал сам`() {
        val ids = listOf("drop-link", "ocr", "ocr-cloud", "ai", "translate", "excel")
        val guilty = ids.flatMap { id ->
            listOf(null, "OpenRouter").flatMap { service ->
                val text = cloudDestination(CapabilityId(id), aiService = service)
                pickedByPoint.filter { text.contains(it, ignoreCase = true) }.map { "«$id»: «$it» в «$text»" }
            }
        }

        assertTrue(guilty.joinToString("\n"), guilty.isEmpty())
    }

    /**
     * Вопрос читается целиком, не прокручиваясь (критерий #560 №4).
     *
     * Мерка грубая — длина строки, — и другой честной здесь нет: экран согласия рисует подпись
     * одним абзацем, и абзац в четыре строки на телефоне читают не с начала, а по диагонали.
     * Прежний текст распознавания был вдвое длиннее этого потолка.
     */
    @Test
    fun `вопрос помещается на экран`() {
        listOf("drop-link", "ocr", "ocr-cloud", "ai", "translate").forEach { id ->
            val text = cloudDestination(CapabilityId(id), aiService = "OpenRouter")
            assertTrue("«$id»: ${text.length} знаков — «$text»", text.length <= 120)
        }
    }

    /**
     * Адресат AI-ветки назван поимённо, потому что Point его знает (#538).
     *
     * «Сервер AI-провайдера» — это класс адресатов, а не адресат. Человек выбрал сервис на экране
     * ключа своими руками, вписал его ключ — и в момент, когда у него спрашивают согласие отдать
     * объект, Point отвечал так, будто не знает куда. Имя здесь пережило #560 намеренно: оно
     * названо не потому, что Point рассказывает про себя, а потому, что это **выбор человека**.
     */
    @Test
    fun `AI-ветка называет выбранный сервис по имени`() {
        val text = cloudDestination(CapabilityId("ai"), aiService = "OpenRouter")
        assertTrue("адресат не назван: $text", text.contains("OpenRouter"))
        assertTrue("имя есть, а класс адресатов остался: $text", !text.contains("AI-провайдера"))
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
