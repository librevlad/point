package com.point.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import com.point.core.flow.HttpResult
import com.point.core.flow.HttpJson

class OcrSpaceReaderTest {

    private fun reader(http: HttpJson, key: String = "") =
        OcrSpaceReader(http, FakeOutboundFrames(sentFrame()), { key }, "https://api.ocr.space/parse/image")

    private fun parsed(vararg texts: String) = HttpResult(
        200,
        """{"ParsedResults":[${texts.joinToString(",") { """{"ParsedText":"$it"}""" }}],
            "IsErroredOnProcessing":false,"OCRExitCode":1}""",
    )

    private fun fields(body: String): Map<String, String> = body.split('&').associate { pair ->
        val (name, value) = pair.split('=', limit = 2)
        name to URLDecoder.decode(value, "UTF-8")
    }

    @Test
    fun `запрос идёт формой в ручку разбора, с кадром и движком из замера`() = runTest {
        val http = FakeHttpJson { parsed("Ведомость") }
        reader(http).read(pageObject)

        val sent = http.posts.single()
        assertEquals("https://api.ocr.space/parse/image", sent.url)

        assertTrue(sent.headers["Content-Type"]!!, sent.headers["Content-Type"]!!.contains("x-www-form-urlencoded"))
        val form = fields(sent.body)
        assertEquals("3", form["OCREngine"])
        assertEquals("true", form["isTable"])
        assertTrue(form["base64Image"]!!.startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun `без своего ключа читает демо-ключом — тем самым, что напечатан в их примерах`() = runTest {
        val http = FakeHttpJson { parsed("x") }
        val eye = reader(http)

        assertTrue(eye.configured)
        eye.read(pageObject)
        assertEquals("helloworld", fields(http.posts.single().body)["apikey"])
    }

    @Test
    fun `свой ключ выигрывает у демо-ключа — потолок поднимает он`() = runTest {
        val http = FakeHttpJson { parsed("x") }
        reader(http, key = "мой-ключ").read(pageObject)
        assertEquals("мой-ключ", fields(http.posts.single().body)["apikey"])
    }

    @Test
    fun `страницы склеиваются по порядку — человеку нужен документ, а не куски`() = runTest {
        val text = reader(FakeHttpJson { parsed("Первая страница", "Вторая страница") }).read(pageObject)
        assertEquals("Первая страница\n\nВторая страница", text)
    }

    /**
     * Сервис отвечает успешным кодом, а отказ кладёт внутрь ответа — по-английски (#1259).
     * Прежние тесты требовали, чтобы «File size exceeds limit» и «Invalid API key» стояли
     * на русском экране дословно, то есть цементировали дефект: отказ обязан доходить, но
     * своими словами и без внутреннего имени читалки.
     */
    @Test
    fun `отказ с кодом 200 — это отказ, а не пустая страница`() = runTest {

        val body = """{"IsErroredOnProcessing":true,"ErrorMessage":["File size exceeds limit"]}"""
        val error = runCatching { reader(FakeHttpJson { HttpResult(200, body) }).read(pageObject) }
            .exceptionOrNull()

        val said = error?.message.orEmpty()
        assertEquals("сказано про размер, а не «сломалось»", com.point.core.flow.serviceRefusal(413), said)
        assertFalse(said, said.contains("File size"))
        assertFalse("внутреннее имя читалки человеку не адресовано", said.contains("ocr-space"))
    }

    @Test
    fun `отказ строкой вместо списка тоже доходит словами`() = runTest {
        val body = """{"IsErroredOnProcessing":true,"ErrorMessage":"Invalid API key"}"""
        val error = runCatching { reader(FakeHttpJson { HttpResult(200, body) }).read(pageObject) }
            .exceptionOrNull()

        val said = error?.message.orEmpty()
        assertEquals(com.point.core.flow.serviceRefusal(401), said)
        assertFalse(said, said.any { it in 'a'..'z' || it in 'A'..'Z' })
    }

    @Test
    fun `непонятный отказ сервиса не пересказывается дословно`() = runTest {
        val body = """{"IsErroredOnProcessing":true,"ErrorMessage":"E216: Timed out waiting for the worker"}"""
        val error = runCatching { reader(FakeHttpJson { HttpResult(200, body) }).read(pageObject) }
            .exceptionOrNull()

        val said = error?.message.orEmpty()
        assertEquals(com.point.core.flow.SERVICE_DID_NOT_READ, said)
        assertFalse(said, said.contains("worker"))
    }

    @Test
    fun `слишком часто — очередь идёт дальше, а человек слышит про бесплатное`() = runTest {
        val said = runCatching { reader(FakeHttpJson { HttpResult(429, "slow down") }).read(pageObject) }
            .exceptionOrNull()!!.message!!

        assertTrue(said, com.point.core.flow.looksLikeQuotaFailure(said))
        assertFalse("код протокола человеку ни о чём не говорит: $said", said.contains("429"))
        assertFalse("наша касса — не его дело: $said", said.contains("купить"))
    }

    @Test
    fun `ключ не принят — так и сказано, а не «сломалось»`() = runTest {
        val error = runCatching { reader(FakeHttpJson { HttpResult(403, "forbidden") }).read(pageObject) }
            .exceptionOrNull()
        assertEquals(com.point.core.flow.serviceRefusal(403), error?.message)
    }

    @Test
    fun `ключ не возвращается на экран, даже если сервис вернул его в отказе`() = runTest {

        val http = FakeHttpJson { HttpResult(400, """{"ErrorMessage":"bad apikey=секретный-ключ-владельца"}""") }
        val error = runCatching { reader(http, key = "секретный-ключ-владельца").read(pageObject) }
            .exceptionOrNull()

        assertFalse(error?.message!!, error.message!!.contains("секретный-ключ-владельца"))
    }

    /**
     * Сервис ответил успешно, а страниц в ответе нет (#1255).
     *
     * Прежде телефон разбирал такой ответ в пустой текст, и снимок получал знание «текста не
     * нашлось» — ответ за сервис, который не отдал ничего. Сорвавшееся исследование в
     * «не нашлось» не переводится (CLAUDE.md, «Investigation State»), и теперь оба устройства
     * отвечают на пустой список одинаково: сервис не прочитал.
     */
    @Test
    fun `ответ без страниц — отказ сервиса, а не «текста нет»`() = runTest {
        val body = """{"IsErroredOnProcessing":false,"ParsedResults":[],"OCRExitCode":1}"""
        val error = runCatching { reader(FakeHttpJson { HttpResult(200, body) }).read(pageObject) }
            .exceptionOrNull()

        assertEquals(com.point.core.flow.SERVICE_DID_NOT_READ, error?.message)
    }

    @Test
    fun `битый ответ — отказ, а не пустая страница`() = runTest {
        val error = runCatching { reader(FakeHttpJson { HttpResult(200, "не json") }).read(pageObject) }
            .exceptionOrNull()
        assertEquals(com.point.core.flow.UNREADABLE_ANSWER, error?.message)
    }

    @Test
    fun `адресат назван честно — Германия, и про обучение он промолчал`() {
        val eye = reader(FakeHttpJson())
        assertTrue(eye.privacy.where.contains("Германия"))

        assertEquals(com.point.core.flow.ReaderPromise.UNKNOWN, eye.privacy.promise)
    }

    @Test
    fun `берётся за снимок, не за PDF`() {
        val eye = reader(FakeHttpJson())
        assertTrue(eye.canRead(pageObject))
        assertFalse(eye.canRead(pageObject.copy(mime = "application/pdf")))
    }
}
