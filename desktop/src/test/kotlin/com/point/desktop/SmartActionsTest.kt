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

/**
 * Компьютер работает с содержимым, а не только с файлом как с файлом (#585).
 *
 * До этого среза ПК умел восемь вещей — открыть, показать, напечатать, сохранить, — и человек,
 * приславший туда документ с телефона, получал на большом экране меньше, чем имел в руке.
 * Здесь судится то, что добавилось: находки в тексте, AI и QR.
 */
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

    // --- Найти в тексте ---------------------------------------------------------------------

    @Test fun `находки становятся объектом, с которым можно работать дальше`() = runTest {
        // Результат остаётся ЗДЕСЬ (#595): человек, начавший работу на компьютере, продолжает её
        // на компьютере. Раньше находки уезжали письмом на телефон, а на экране оставался
        // исходный текст — и следующее действие применялось к нему.
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

        // Пустой файл на выходе человек прочитает как поломку, а это не поломка.
        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).reason.contains("не нашлось"))
        assertTrue("в почту компьютера уехал пустой объект", box.entries().isEmpty())
    }

    // --- AI ---------------------------------------------------------------------------------

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
        // Чинится вписыванием ключа, а не повтором — повторять нечего.
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
        assertEquals("Сервис AI сейчас не отвечает", (result as ActionResult.Failure).reason)
        // Сеть чинится ожиданием, поэтому дверь остаётся открытой.
        assertTrue(result.recoverable)
    }

    // --- QR ---------------------------------------------------------------------------------

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

        // Обрезанный QR ведёт не туда — это хуже, чем отсутствие QR.
        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).reason.contains("длиннее"))
        assertTrue(box.entries().isEmpty())
    }

    // --- Дать ссылку --------------------------------------------------------------------------

    @Test fun `ссылка кладётся в буфер компьютера — оттуда её и вставляют`() = runTest {
        var copied: String? = null
        val realizer = PcDropRealizer(
            drop = { _, _, _ -> "https://point.leerio.app/d/abc" },
            clipboard = { text -> copied = text },
        )

        val result = realizer.perform(textObject("отчёт"), null)

        assertTrue(result is ActionResult.Done)
        assertEquals("https://point.leerio.app/d/abc", copied)
        // Срок жизни сказан сразу: человек вставляет ссылку в письмо и должен знать, сколько она живёт.
        assertTrue((result as ActionResult.Done).message.contains("сутки"))
    }

    @Test fun `ссылки не вышло — сказано, что с этим делать, а не почему так вышло`() = runTest {
        var copied: String? = null
        val realizer = PcDropRealizer(drop = { _, _, _ -> null }, clipboard = { copied = it })

        val result = realizer.perform(textObject("отчёт"), null)

        assertTrue(result is ActionResult.Failure)
        val message = (result as ActionResult.Failure).reason
        // Причин три (не вошли, нет сети, файл велик), и человеку нужна не та, что случилась, а
        // что делать: поэтому названы все три коротко.
        assertTrue("отказ не назвал вход: $message", message.contains("войдите"))
        assertTrue("отказ не назвал размер: $message", message.contains("50 МБ"))
        assertEquals("в буфер положили пустоту", null, copied)
    }

    // --- Картинка полегче -----------------------------------------------------------------------

    private fun imageObject(width: Int, height: Int, alpha: Boolean = false): PointObject {
        val type = if (alpha) java.awt.image.BufferedImage.TYPE_INT_ARGB else java.awt.image.BufferedImage.TYPE_INT_RGB
        val image = java.awt.image.BufferedImage(width, height, type)
        // Шум, а не заливка: одноцветная картинка жмётся до килобайта и ничего не проверяет.
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

        // JPEG прозрачности не знает: превратив в него логотип, Point залил бы фон чёрным.
        assertEquals("image/png", (result as ActionResult.Success).result.mime)
    }

    @Test fun `и без того лёгкая картинка не переделывается зря`() = runTest {
        val box = outbox()

        val result = PcShrinkImageRealizer(box).perform(imageObject(320, 240), null)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).reason.contains("лёгкая"))
        assertTrue("отдали копию того же самого", box.entries().isEmpty())
    }

    // --- Расшифровка речи -----------------------------------------------------------------------

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
        // Ключ OpenRouter тут не подойдёт — сказано прямо, иначе человек будет вставлять не тот.
        assertTrue("не назван сервис: $message", message.contains("Groq") || message.contains("OpenAI"))
        assertFalse(result.recoverable)
    }

    @Test fun `формат, который движок не читает, отсекается до сети`() = runTest {
        val realizer = PcTranscribeRealizer({ SpeechConfig(key = "есть") }, outbox())

        val result = realizer.perform(audioObject(name = "запись.amr", mime = "audio/amr"), null)

        assertTrue(result is ActionResult.Failure)
        // 400 от чужого сервиса человеку ничего не говорит; формат называется словами заранее.
        assertTrue((result as ActionResult.Failure).reason.contains("ogg"))
    }

    // --- Копирование по виду объекта ------------------------------------------------------------

    @Test fun `картинка кладётся в буфер картинкой, а не байтами как текст`() = runTest {
        var asText: String? = null
        var asImage: com.point.core.flow.ClipboardPayload? = null
        val source = imageObject(64, 64)

        val result = PcCopyRealizer({ asText = it }, { asImage = it }).perform(source, null)

        assertTrue(result is ActionResult.Done)
        // Картинка, положенная строкой, вставится в письмо кашей из символов.
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

    // --- Чтение снимка в облаке -----------------------------------------------------------------

    @Test fun `слишком большой снимок отсекается до сети и советует, что делать`() = runTest {
        val box = outbox()
        // Бесплатный уровень сервиса берёт до мегабайта; отправить больше значит подарить человеку
        // минуту ожидания ради отказа.
        val big = imageObject(2000, 2000)
        assertTrue("картинка вышла меньше предела — проверять нечего", File(big.uri.value).length() > 1024 * 1024)

        val result = PcCloudOcrRealizer({ OcrConfig() }, box).perform(big, null)

        assertTrue(result is ActionResult.Failure)
        val message = (result as ActionResult.Failure).reason
        assertTrue("отказ не подсказал выход: " + message, message.contains("Сделать легче"))
        assertTrue(box.entries().isEmpty())
    }

    @Test fun `чтение в облаке названо облаком, а не распознаванием`() {
        // На телефоне «Распознать текст» читает сам и бесплатно. Одинаковое имя у разных по цене
        // действий — это обещание, которое ПК не выполнит: у него локального чтения нет.
        assertEquals("Прочитать в облаке", PcCloudOcrCapability().label(ObjectState(ObjectKind.IMAGE)))
        assertTrue("действие не помечено сетевым", PcCloudOcrCapability().meta.network)
    }

    // --- Объявление телефону ------------------------------------------------------------------

    @Test fun `каждое новое действие объявлено и телефону, и реестру`() {
        // Расхождение этих двух списков — молчаливая поломка: кнопка на телефоне есть, а на
        // компьютере её некому исполнить (или наоборот).
        val declared = setOf(
            "pc-entities", "pc-understand", "pc-translate", "pc-ask", "pc-qr",
        )
        val registry = DesktopRegistry(
            setOf(
                PcEntitiesCapability(), PcUnderstandCapability(), PcTranslateCapability(),
                PcAskCapability(), PcQrCapability(),
            ),
        )

        // Реестр спрашивается так же, как его спрашивает экран: какие действия он даёт тексту.
        val known = registry.bubblesFor(ObjectState(ObjectKind.TEXT)).map { it.capabilityId.value }.toSet()

        assertEquals(declared - "pc-qr", known - "pc-qr")
        assertTrue("QR не предложен тексту", known.contains("pc-qr"))
    }
}
