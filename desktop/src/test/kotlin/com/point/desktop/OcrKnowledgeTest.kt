package com.point.desktop

import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Аудит 2026-08-09, блок 1.1: OCR рождал новый объект, знание на снимок не писал.
 * Прочитанное — знание об этом же снимке: текст слоем, сущности фактами,
 * «нет текста» — состояние знания, а не сбой (Конституция §4, §13).
 */
class OcrKnowledgeTest {

    @get:Rule val temp = TemporaryFolder()

    private var server: HttpServer? = null

    private fun serve(parsedText: String): String {
        val s = HttpServer.create(InetSocketAddress(0), 0)
        s.createContext("/parse") { exchange ->
            val body = """{"IsErroredOnProcessing":false,"ParsedResults":[{"ParsedText":"$parsedText"}]}"""
                .toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/parse"
    }

    @After fun stop() {
        server?.stop(0)
    }

    private fun serveRaw(body: String): String {
        val s = HttpServer.create(InetSocketAddress(0), 0)
        s.createContext("/parse") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/parse"
    }

    /**
     * Слова отказа живут в одном месте (#1237/#1259).
     *
     * Компьютер держал свою копию «Сервис не прочитал снимок» литералом рядом с объявленной
     * общей — две копии разъезжаются при первой же правке формулировки. И отказ, названный по
     * существу, накрывался общим «Сервис чтения не ответил — попробуйте позже»: причина
     * ложная, шаг ложный. Человек шёл ждать вместо того, чтобы поправить ключ.
     */
    @Test
    fun `сервис не принял ключ — так человеку и сказано, а не «не ответил»`() = runTest {
        val url = serveRaw("""{"IsErroredOnProcessing":true,"ErrorMessage":["Invalid API key"]}""")
        val realizer = PcCloudOcrRealizer(
            { OcrConfig(key = "к", url = url) },
            com.point.core.flow.RegexEntityExtractor(),
        )

        val result = realizer.perform(snapshot(), null)

        val said = (result as ActionResult.Failure).reason
        assertEquals(com.point.core.flow.KEY_NOT_TAKEN, said)
        assertFalse("чужой текст у человека: $said", said.contains("Invalid API key"))
    }

    private fun snapshot(): PointObject {
        val file = temp.newFile("чек.jpg").apply { writeBytes(ByteArray(64)) }
        return PointObject("img", "image/jpeg", ScratchRef(file.absolutePath), ObjectState(ObjectKind.IMAGE))
    }

    @Test
    fun `прочитанное — знание на снимке - текст слоем, телефон фактом`() = runTest {
        val url = serve("Оплата 500 грн, тел +380671234567")
        val realizer = PcCloudOcrRealizer(
            { OcrConfig(key = "к", url = url) },
            com.point.core.flow.RegexEntityExtractor(),
        )

        val result = realizer.perform(snapshot(), null)

        val done = result as ActionResult.Done
        val findings = done.findings!!
        assertTrue(findings.features.contains(Feature.HAS_TEXT))
        assertEquals("+380671234567", findings.metadata["entity.phone"])
        assertEquals("found", findings.metadata["investigated.ocr"])
        val layer = findings.metadata[com.point.core.flow.META_OCR_TEXT_REF]
        assertTrue("текст лежит слоем у объекта", File(layer!!).readText().contains("+380671234567"))
        assertTrue(done.message.startsWith("Прочитал снимок"))
    }

    @Test
    fun `служебная пометка сервиса — то же «не нашлось», что и пустой ответ`() = runTest {
        // Сервис на ПК тот же, что у телефона, и на кадре без надписей отвечает не пустотой,
        // а пометкой (#1054). Правило одно на обе поверхности: текстом снимка она не станет.
        val url = serve("*[No text detected]*")
        val realizer = PcCloudOcrRealizer(
            { OcrConfig(key = "к", url = url) },
            com.point.core.flow.RegexEntityExtractor(),
        )

        val result = realizer.perform(snapshot(), null)

        assertTrue("пометка стала текстом снимка: $result", result is ActionResult.Done)
        val findings = (result as ActionResult.Done).findings!!
        assertEquals("not_found", findings.metadata["investigated.ocr"])
        assertTrue("отписка легла слоем текста", findings.metadata[com.point.core.flow.META_OCR_TEXT_REF] == null)
        assertFalse("человек видит чужую отписку", result.message.contains("No text detected"))
    }

    @Test
    fun `на снимке нет текста — это знание, а не ошибка`() = runTest {
        val url = serve("")
        val realizer = PcCloudOcrRealizer(
            { OcrConfig(key = "к", url = url) },
            com.point.core.flow.RegexEntityExtractor(),
        )

        val result = realizer.perform(snapshot(), null)

        assertTrue("нет текста — Done, не Failure: $result", result is ActionResult.Done)
        assertEquals(
            "not_found",
            (result as ActionResult.Done).findings!!.metadata["investigated.ocr"],
        )
    }
}
