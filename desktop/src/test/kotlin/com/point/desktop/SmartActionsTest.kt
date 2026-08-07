package com.point.desktop

import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.LlmClient
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SmartActionsTest {

    @get:Rule val temp = TemporaryFolder()

    private fun textObject(text: String): PointObject {
        val file = temp.newFile("object-" + text.hashCode() + ".txt").apply { writeText(text) }
        return PointObject(
            id = "obj-1",
            uri = ScratchRef(file.absolutePath),
            mime = "text/plain",
            state = ObjectState(ObjectKind.TEXT),
        )
    }

    private fun outbox() = Outbox(temp.newFolder("outbox-" + System.nanoTime()))

    @Test fun `находки становятся объектом, с которым можно работать дальше`() = runTest {

        val realizer = PcEntitiesRealizer(com.point.core.flow.RegexEntityExtractor(), outbox())

        val result = realizer.perform(
            textObject("Ирина, +7 916 123-45-67, irina@example.com, оплата 48500 руб."),
            null,
        )

        assertTrue("действие не вернуло объект: $result", result is ActionResult.Success)
        val born = (result as ActionResult.Success).result
        val report = File(born.uri.value).readText()
        assertTrue("в отчёте нет телефона", report.contains("+7 916 123-45-67"))
        assertTrue("в отчёте нет почты", report.contains("irina@example.com"))
        assertTrue("объект без человеческого имени", born.metadata["name"].orEmpty().startsWith("Найдено"))
    }

    @Test fun `ничего не нашлось — сказано словами, а не пустым файлом`() = runTest {
        val box = outbox()
        val realizer = PcEntitiesRealizer(com.point.core.flow.RegexEntityExtractor(), box)

        val result = realizer.perform(textObject("Просто текст без единого контакта"), null)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).reason.contains("не нашлось"))
        assertTrue("в почту компьютера уехал пустой объект", box.entries().isEmpty())
    }

    private class FakeLlm(
        override val configured: Boolean = true,
        private val answer: String = "ответ модели",
        private val fail: String? = null,
    ) : LlmClient {
        var lastPrompt: String? = null
            private set

        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            lastPrompt = prompt
            fail?.let { error(it) }
            val file = File.createTempFile("fake-ai-", ".txt").apply { writeText(answer) }
            return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(file.absolutePath))
        }
    }

    @Test fun `без ключа AI не идёт в сеть и говорит, где его вписать`() = runTest {
        val llm = FakeLlm(configured = false)
        val realizer = PcAiRealizer(CapabilityId("pc-understand"), llm, PcPrompts.UNDERSTAND, outbox(), "Понятое")

        val result = realizer.perform(textObject("любой текст"), null)

        assertTrue(result is ActionResult.Failure)
        val message = (result as ActionResult.Failure).reason
        assertTrue("отказ не называет, где ключ: $message", message.contains(".point-pc/config"))

        assertFalse("отказ предложил попробовать ещё раз", result.recoverable)
        assertEquals("клиент всё-таки позвали без ключа", null, llm.lastPrompt)
    }

    @Test fun `уточнение человека идёт после задания, а не вместо него`() = runTest {
        val llm = FakeLlm()
        val realizer = PcAiRealizer(CapabilityId("pc-ask"), llm, PcPrompts.ASK, outbox(), "Ответ AI")

        realizer.perform(textObject("текст договора"), "какая сумма?")

        val prompt = llm.lastPrompt.orEmpty()
        assertTrue("задание пропало: $prompt", prompt.startsWith(PcPrompts.ASK))
        assertTrue("вопрос человека пропал: $prompt", prompt.contains("какая сумма?"))
    }

    @Test fun `ответ модели становится объектом с человеческим именем`() = runTest {
        val box = outbox()
        val realizer = PcAiRealizer(CapabilityId("pc-translate"), FakeLlm(answer = "перевод"), PcPrompts.TRANSLATE, box, "Перевод")

        val result = realizer.perform(textObject("some english text"), null)

        assertTrue("ответ модели не стал объектом: $result", result is ActionResult.Success)
        val born = (result as ActionResult.Success).result
        assertEquals("Перевод", born.metadata["name"])
        assertEquals("перевод", File(born.uri.value).readText())
    }

    @Test fun `сервис отказал — человек читает слова, а не хвост исключения`() = runTest {
        val realizer = PcAiRealizer(
            CapabilityId("pc-understand"),
            FakeLlm(fail = "Сервис AI сейчас не отвечает"),
            PcPrompts.UNDERSTAND,
            outbox(),
            "Понятое",
        )

        val result = realizer.perform(textObject("текст"), null)

        assertTrue(result is ActionResult.Failure)

        val reason = (result as ActionResult.Failure).reason
        assertTrue("отказ не сказал про сервис: " + reason, reason.contains("Сервис AI"))
        assertTrue("отказ ничего не советует: " + reason, reason.contains("позже"))

        assertTrue(result.recoverable)
    }

    @Test fun `ссылка превращается в картинку, которую можно снять камерой`() = runTest {
        val box = outbox()

        val result = PcQrRealizer(box).perform(textObject("https://point.leerio.app/d/abc123"), null)

        assertTrue("QR не собрался: $result", result is ActionResult.Success)
        val born = (result as ActionResult.Success).result
        assertEquals("image/png", born.mime)
        val image = javax.imageio.ImageIO.read(File(born.uri.value))
        assertTrue("картинка пустая", image.width > 100 && image.width == image.height)
    }

    @Test fun `длинный текст в QR не лезет — и об этом сказано, а не обрезано`() = runTest {
        val box = outbox()

        val result = PcQrRealizer(box).perform(textObject("длинный текст ".repeat(40)), null)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).reason.contains("длиннее"))
        assertTrue(box.entries().isEmpty())
    }

    @Test fun `ссылка кладётся в буфер компьютера — оттуда её и вставляют`() = runTest {
        var copied: String? = null
        val realizer = PcDropRealizer(
            drop = { _, _, _ -> "https://point.leerio.app/d/abc" },
            clipboard = { text -> copied = text },
        )

        val result = realizer.perform(textObject("отчёт"), null)

        assertTrue(result is ActionResult.Done)
        assertEquals("https://point.leerio.app/d/abc", copied)

        assertTrue((result as ActionResult.Done).message.contains("сутки"))
    }

    @Test fun `ссылки не вышло — сказано, что с этим делать, а не почему так вышло`() = runTest {
        var copied: String? = null
        val realizer = PcDropRealizer(drop = { _, _, _ -> null }, clipboard = { copied = it })

        val result = realizer.perform(textObject("отчёт"), null)

        assertTrue(result is ActionResult.Failure)
        val message = (result as ActionResult.Failure).reason

        assertTrue("отказ не назвал вход: $message", message.contains("войдите"))
        assertTrue("отказ не назвал размер: $message", message.contains("50 МБ"))
        assertEquals("в буфер положили пустоту", null, copied)
    }

    private fun imageObject(width: Int, height: Int, alpha: Boolean = false): PointObject {
        val type = if (alpha) java.awt.image.BufferedImage.TYPE_INT_ARGB else java.awt.image.BufferedImage.TYPE_INT_RGB
        val image = java.awt.image.BufferedImage(width, height, type)

        val random = java.util.Random(42)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, random.nextInt())
        val file = temp.newFile("image-" + System.nanoTime() + ".png")
        javax.imageio.ImageIO.write(image, "png", file)
        return PointObject(
            id = "img-1",
            uri = ScratchRef(file.absolutePath),
            mime = "image/png",
            state = ObjectState(ObjectKind.IMAGE),
        )
    }

    @Test fun `большой снимок становится легче и меньше по стороне`() = runTest {
        val box = outbox()
        val source = imageObject(3000, 2000)

        val result = PcShrinkImageRealizer(box).perform(source, null)

        assertTrue("не уменьшилось: $result", result is ActionResult.Success)
        val out = File((result as ActionResult.Success).result.uri.value)
        assertTrue("файл не стал легче", out.length() < File(source.uri.value).length())
        val image = javax.imageio.ImageIO.read(out)
        assertEquals("длинная сторона не приведена к пределу", 1920, image.width)
    }

    @Test fun `прозрачность не теряется — такая картинка остаётся PNG`() = runTest {
        val box = outbox()

        val result = PcShrinkImageRealizer(box).perform(imageObject(2400, 1200, alpha = true), null)

        assertEquals("image/png", (result as ActionResult.Success).result.mime)
    }

    @Test fun `и без того лёгкая картинка не переделывается зря`() = runTest {
        val box = outbox()

        val result = PcShrinkImageRealizer(box).perform(imageObject(320, 240), null)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).reason.contains("лёгкая"))
        assertTrue("отдали копию того же самого", box.entries().isEmpty())
    }

    private fun audioObject(name: String = "voice.ogg", mime: String = "audio/ogg"): PointObject {
        val file = temp.newFile("audio-" + System.nanoTime() + ".ogg").apply { writeBytes(ByteArray(1024)) }
        return PointObject(
            id = "aud-1",
            uri = ScratchRef(file.absolutePath),
            mime = mime,
            state = ObjectState(ObjectKind.AUDIO),
            metadata = mapOf("name" to name),
        )
    }

    @Test fun `без ключа расшифровка не идёт в сеть и называет нужный сервис`() = runTest {
        val realizer = PcTranscribeRealizer({ SpeechConfig(key = "") }, outbox())

        val result = realizer.perform(audioObject(), null)

        assertTrue(result is ActionResult.Failure)
        val message = (result as ActionResult.Failure).reason

        assertTrue("не назван сервис: $message", message.contains("Groq") || message.contains("OpenAI"))
        assertFalse(result.recoverable)
    }

    @Test fun `формат, который движок не читает, отсекается до сети`() = runTest {
        val realizer = PcTranscribeRealizer({ SpeechConfig(key = "есть") }, outbox())

        val result = realizer.perform(audioObject(name = "запись.amr", mime = "audio/amr"), null)

        assertTrue(result is ActionResult.Failure)

        assertTrue((result as ActionResult.Failure).reason.contains("ogg"))
    }

    @Test fun `картинка кладётся в буфер картинкой, а не байтами как текст`() = runTest {
        var asText: String? = null
        var asImage: com.point.core.flow.ClipboardPayload? = null
        val source = imageObject(64, 64)

        val result = PcCopyRealizer({ asText = it }, { asImage = it }).perform(source, null)

        assertTrue(result is ActionResult.Done)

        assertEquals("картинку положили текстом", null, asText)
        assertTrue("картинка в буфер не попала", asImage != null)
        assertTrue("в буфер уехало не изображение", asImage!!.isImage)
    }

    @Test fun `текст по-прежнему кладётся текстом`() = runTest {
        var asText: String? = null
        var asImage: com.point.core.flow.ClipboardPayload? = null

        PcCopyRealizer({ asText = it }, { asImage = it }).perform(textObject("накладная 4512"), null)

        assertEquals("накладная 4512", asText)
        assertEquals("текст положили картинкой", null, asImage)
    }

    @Test fun `нечем положить картинку — сказано, а не сделано молча текстом`() = runTest {
        var asText: String? = null

        val result = PcCopyRealizer({ asText = it }, imageClipboard = null).perform(imageObject(32, 32), null)

        assertTrue(result is ActionResult.Failure)
        assertEquals("картинка всё-таки уехала текстом", null, asText)
    }

    @Test fun `слишком большой снимок отсекается до сети и советует, что делать`() = runTest {
        val box = outbox()

        val big = imageObject(2000, 2000)
        assertTrue("картинка вышла меньше предела — проверять нечего", File(big.uri.value).length() > 1024 * 1024)

        val result = PcCloudOcrRealizer({ OcrConfig() }, box).perform(big, null)

        assertTrue(result is ActionResult.Failure)
        val message = (result as ActionResult.Failure).reason
        assertTrue("отказ не подсказал выход: " + message, message.contains("Сделать легче"))
        assertTrue(box.entries().isEmpty())
    }

    @Test fun `у чтения снимка одна декларация на оба устройства, а компьютер даёт реализацию`() {

        val shared = com.point.core.flow.capabilities.OcrCapability()

        assertEquals(shared.id, PcCloudOcrRealizer({ OcrConfig() }, outbox()).capabilityId)
        assertEquals("Распознать текст", shared.label(ObjectState(ObjectKind.IMAGE)))
        assertTrue(
            "цена дороги не названа до тапа",
            shared.yields(ObjectState(ObjectKind.IMAGE)).toString().contains("сервис"),
        )
    }

    @Test fun `своей способности про облако у компьютера не осталось`() {

        val forImage = pcRegistry().bubblesFor(ObjectState(ObjectKind.IMAGE)).map { it.capabilityId.value }

        assertTrue("на ПК снова заведена своя способность чтения: $forImage", "pc-ocr" !in forImage)
        assertTrue("общее чтение не предложено картинке: $forImage", "ocr" in forImage)
    }

    @Test fun `у компьютера нет двух действий с одним именем на одном объекте`() {

        listOf(ObjectKind.IMAGE, ObjectKind.TEXT, ObjectKind.OFFICE, ObjectKind.ZIP, ObjectKind.URL)
            .forEach { kind ->
                val titles = pcRegistry().bubblesFor(ObjectState(kind)).map { it.title }
                val twins = titles.groupBy { it }.filterValues { it.size > 1 }.keys

                assertTrue("на $kind одно намерение объявлено дважды: $twins", twins.isEmpty())
            }
    }

    @Test fun `компьютер объявляет свои доставки и общие преобразования, и ничего сверх`() {

        val own = pcRegistry().all().map { it.id.value }.filter { it.startsWith("pc-") }
        val shared = com.point.core.flow.capabilities.sharedCapabilities().map { it.id.value }

        assertTrue(
            "общее намерение снова присвоено компьютеру: ${own.intersect(shared.toSet())}",
            own.none { it.removePrefix("pc-") in shared },
        )
    }

    @Test fun `у каждого объявленного действия компьютера есть чем его выполнить`() {

        val declared = pcRegistry().all().map { it.id.value }.toSet()
        val realizable = pcRealizerIds()

        assertTrue(
            "объявлено, но выполнить нечем: ${declared - realizable}",
            (declared - realizable).isEmpty(),
        )
    }

    private fun pcRealizerIds(): Set<String> = setOf(
        "pc-open", "pc-copy", "pc-reveal", "pc-save-as", "pc-download", "pc-to-phone", "pc-print",
        "pc-open-link", "pc-understand", "pc-translate", "pc-ask", "pc-transcribe",
        "pc-entities",
    ) + com.point.core.flow.capabilities.sharedCapabilities().map { it.id.value }

    private fun pcRegistry() = DesktopRegistry(
        setOf(
            PcOpenCapability(), PcCopyCapability(), PcRevealCapability(), PcSaveAsCapability(),
            PcDownloadCapability(), PcToPhoneCapability(), PcPrintCapability(),
            PcOpenLinkCapability(), PcUnderstandCapability(), PcTranslateCapability(),
            PcAskCapability(), PcTranscribeCapability(),
            PcEntitiesCapability(),
        ) + com.point.core.flow.capabilities.sharedCapabilities(),
    )
}
