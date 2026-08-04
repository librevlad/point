package com.point.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * Второй читатель страницы на подделках (#490/#493): проверяется запрос и разбор ответа. Живого
 * ключа нет и не нужно — он у этого читателя и не обязателен.
 *
 * Контракт сверен с рабочим замером (`tools/vision/freeprobe.py`) — тем самым, которым получены
 * 15/15 шесть попыток из шести.
 */
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
        // У ручки нет JSON-двери: общий Content-Type транспорта обязан быть перебит вызывающим.
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

        // Смысл читателя: он живой у человека, который ничего не настраивал.
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

    @Test
    fun `отказ с кодом 200 — это отказ, а не пустая страница`() = runTest {
        // Сервис умеет сказать «не смог» успешным HTTP. Пропустив это, мы отдали бы человеку пустой
        // текст — то есть «страница пустая», а это совсем другая новость.
        val body = """{"IsErroredOnProcessing":true,"ErrorMessage":["File size exceeds limit"]}"""
        val error = runCatching { reader(FakeHttpJson { HttpResult(200, body) }).read(pageObject) }
            .exceptionOrNull()

        assertTrue(error?.message!!, error.message!!.contains("File size exceeds limit"))
    }

    @Test
    fun `отказ строкой вместо списка тоже доходит словами`() = runTest {
        val body = """{"IsErroredOnProcessing":true,"ErrorMessage":"Invalid API key"}"""
        val error = runCatching { reader(FakeHttpJson { HttpResult(200, body) }).read(pageObject) }
            .exceptionOrNull()

        assertTrue(error?.message!!, error.message!!.contains("Invalid API key"))
    }

    @Test
    fun `429 переводит очередь дальше, а не в кассу`() = runTest {
        val error = runCatching { reader(FakeHttpJson { HttpResult(429, "slow down") }).read(pageObject) }
            .exceptionOrNull()
        assertTrue(error?.message!!, error.message!!.contains("(429)"))
        assertFalse(error.message!!, error.message!!.contains("купить"))
    }

    @Test
    fun `ключ не принят — так и сказано, а не «сломалось»`() = runTest {
        val error = runCatching { reader(FakeHttpJson { HttpResult(403, "forbidden") }).read(pageObject) }
            .exceptionOrNull()
        assertTrue(error?.message!!, error.message!!.contains("ключ не принят"))
    }

    @Test
    fun `ключ не возвращается на экран, даже если сервис вернул его в отказе`() = runTest {
        // Некоторые сервисы пересказывают запрос в тексте отказа. Один такой ответ, показанный
        // карточкой, — и секрет лежит на экране, а с отчётом о падении уезжает дальше.
        val http = FakeHttpJson { HttpResult(400, """{"ErrorMessage":"bad apikey=секретный-ключ-владельца"}""") }
        val error = runCatching { reader(http, key = "секретный-ключ-владельца").read(pageObject) }
            .exceptionOrNull()

        assertFalse(error?.message!!, error.message!!.contains("секретный-ключ-владельца"))
    }

    @Test
    fun `битый ответ — отказ, а не пустая страница`() = runTest {
        val error = runCatching { reader(FakeHttpJson { HttpResult(200, "не json") }).read(pageObject) }
            .exceptionOrNull()
        assertTrue(error?.message!!, error.message!!.contains("не разобран"))
    }

    @Test
    fun `адресат назван честно — Германия, и про обучение он промолчал`() {
        val eye = reader(FakeHttpJson())
        assertTrue(eye.privacy.where.contains("Германия"))
        // «Удаляем после обработки» — не то же самое, что «не учимся». Достраивать нельзя.
        assertEquals(com.point.core.flow.ReaderPromise.UNKNOWN, eye.privacy.promise)
    }

    @Test
    fun `берётся за снимок, не за PDF`() {
        val eye = reader(FakeHttpJson())
        assertTrue(eye.canRead(pageObject))
        assertFalse(eye.canRead(pageObject.copy(mime = "application/pdf")))
    }
}
