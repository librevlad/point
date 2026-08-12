package com.point.data

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.point.core.flow.HttpResult
import com.point.core.flow.HttpJson

class MistralOcrReaderTest {

    private fun reader(http: HttpJson, key: String = "free-key") =
        MistralOcrReader(http, FakeOutboundFrames(sentFrame()), { key }, "https://api.mistral.ai/v1")

    private fun pages(vararg markdown: String) = HttpResult(
        200,
        """{"pages":[${markdown.mapIndexed { i, m -> """{"index":$i,"markdown":"$m"}""" }.joinToString(",")}],
            "model":"mistral-ocr-latest"}""",
    )

    @Test
    fun `запрос идёт в OCR-ручку, с ключом и кадром в теле`() = runTest {
        val http = FakeHttpJson { pages("Ведомость") }
        reader(http).read(pageObject)

        val sent = http.posts.single()
        assertEquals("https://api.mistral.ai/v1/ocr", sent.url)
        assertEquals("Bearer free-key", sent.headers["Authorization"])
        val body = JSONObject(sent.body)
        assertEquals("mistral-ocr-latest", body.getString("model"))
        val document = body.getJSONObject("document")
        assertEquals("image_url", document.getString("type"))
        assertTrue(document.getString("image_url").startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun `модель не пинуется версией — серверный алиас переживает отключения`() = runTest {
        val http = FakeHttpJson { pages("x") }
        reader(http).read(pageObject)
        assertTrue(JSONObject(http.posts.single().body).getString("model").endsWith("-latest"))
    }

    @Test
    fun `картинки страницы обратно не просим — нужен текст, а не вес`() = runTest {
        val http = FakeHttpJson { pages("x") }
        reader(http).read(pageObject)
        assertFalse(JSONObject(http.posts.single().body).getBoolean("include_image_base64"))
    }

    @Test
    fun `страницы склеиваются по порядку — человеку нужен документ, а не куски`() = runTest {
        val text = reader(FakeHttpJson { pages("Первая страница", "Вторая страница") }).read(pageObject)
        assertEquals("Первая страница\n\nВторая страница", text)
    }

    @Test
    fun `упёрлись в предел — это причина отказа для следующего в очереди, а не касса`() = runTest {
        val said = runCatching { reader(FakeHttpJson { HttpResult(402, "payment required") }).read(pageObject) }
            .exceptionOrNull()!!.message!!

        assertTrue(said, com.point.core.flow.looksLikeQuotaFailure(said))
        assertFalse("код протокола человеку ни о чём не говорит: $said", said.contains("402"))
        assertFalse("наша кухня: $said", said.contains("покупать"))
    }

    @Test
    fun `слишком часто — очередь идёт дальше, и человек слышит про бесплатное, а не про частоту`() = runTest {
        val said = runCatching { reader(FakeHttpJson { HttpResult(429, "slow down") }).read(pageObject) }
            .exceptionOrNull()!!.message!!

        assertTrue(said, com.point.core.flow.looksLikeQuotaFailure(said))
        assertFalse("код протокола человеку ни о чём не говорит: $said", said.contains("429"))
        assertFalse("наша кухня: $said", said.contains("покупать"))
    }

    @Test
    fun `ключ не принят — так и сказано, а не «сломалось»`() = runTest {
        val error = runCatching { reader(FakeHttpJson { HttpResult(401, "unauthorized") }).read(pageObject) }
            .exceptionOrNull()
        assertEquals(com.point.core.flow.serviceRefusal(401), error?.message)
    }

    @Test
    fun `битый ответ — отказ, а не пустая страница`() = runTest {
        val error = runCatching { reader(FakeHttpJson { HttpResult(200, "не json") }).read(pageObject) }
            .exceptionOrNull()
        assertEquals(com.point.core.flow.UNREADABLE_ANSWER, error?.message)
    }

    @Test
    fun `без ключа читателя нет — и он этого не скрывает`() = runTest {
        val eye = reader(FakeHttpJson(), key = "")
        assertFalse(eye.configured)
        assertTrue(runCatching { eye.read(pageObject) }.exceptionOrNull()?.message?.contains("ключ не задан") == true)
    }

    @Test
    fun `ключ спрашивается на каждом чтении — человек мог задать его минуту назад`() = runTest {
        var key = ""
        val eye = MistralOcrReader(
            FakeHttpJson { pages("текст") },
            FakeOutboundFrames(sentFrame()),
            { key },
            "https://api.mistral.ai/v1",
        )

        assertFalse(eye.configured)
        key = "ключ-человека"
        assertTrue(eye.configured)
        assertEquals("текст", eye.read(pageObject))
    }

    @Test
    fun `адресат назван честно — Франция, и он учится на присланном`() {
        val eye = reader(FakeHttpJson())
        assertTrue(eye.privacy.where.contains("Франция"))

        assertEquals(com.point.core.flow.ReaderPromise.TRAINS, eye.privacy.promise)
    }

    @Test
    fun `берётся за снимок, не за PDF — читателей PDF среди бесплатных пока нет`() {
        val eye = reader(FakeHttpJson())
        assertTrue(eye.canRead(pageObject))
        assertFalse(eye.canRead(pageObject.copy(mime = "application/pdf")))
    }
}
