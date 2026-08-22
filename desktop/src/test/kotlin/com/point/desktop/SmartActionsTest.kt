package com.point.desktop

import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.QR_MAX_BYTES
import com.point.core.flow.QR_TOO_LONG
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
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

        val realizer = PcEntitiesRealizer(com.point.core.flow.RegexEntityExtractor())

        val result = realizer.perform(
            textObject("Ирина, +7 916 123-45-67, irina@example.com, оплата 48500 руб."),
            null,
        )

        // Аудит 2026-08-09, блок 1.1: найденное — знание на исходнике, а не объект-отчёт.
        assertTrue("исследование обязано вернуть знание: $result", result is ActionResult.Done)
        val findings = (result as ActionResult.Done).findings!!
        assertEquals("+7 916 123-45-67", findings.metadata["entity.phone"])
        assertEquals("irina@example.com", findings.metadata["entity.email"])
        assertEquals("found", findings.metadata["investigated.entities"])
        assertTrue(findings.features.contains(com.point.core.model.Feature.HAS_PHONE))
        assertTrue("сводка — человеческая", result.message.startsWith("Нашёл"))
    }

    @Test fun `второй телефон не теряется — остаётся ещё-значением`() = runTest {
        val realizer = PcEntitiesRealizer(com.point.core.flow.RegexEntityExtractor())

        val result = realizer.perform(
            textObject("Отправитель +380671234567, получатель +380509876543"),
            null,
        ) as ActionResult.Done

        val meta = result.findings!!.metadata
        assertEquals("+380671234567", meta["entity.phone"])
        assertTrue(
            "второе значение обязано остаться",
            meta["entity.phone.more"].orEmpty().contains("+380509876543"),
        )
    }

    @Test fun `ничего не нашлось — это знание, а не ошибка`() = runTest {
        val realizer = PcEntitiesRealizer(com.point.core.flow.RegexEntityExtractor())

        val result = realizer.perform(textObject("Просто текст без единого контакта"), null)

        // Конституция §13: «исследовано, не найдено» — состояние знания, не сбой операции.
        assertTrue("не нашлось — Done, не Failure: $result", result is ActionResult.Done)
        assertEquals(
            "not_found",
            (result as ActionResult.Done).findings!!.metadata["investigated.entities"],
        )
        assertTrue(result.message.contains("не нашлось"))
    }

    // «Понять»/«Спросить AI»/«Перевести» на компьютере убраны (#701, решение владельца
    // «Убрать, ПК — только исполнитель») — тесты этих исполнителей ушли вместе с ними,
    // а не переписаны: продукт больше не делает того, что они проверяли.

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

        // Кириллица — два байта на знак, столько знаков заведомо выше общего потолка (#1084).
        val over = "длинный текст ".repeat(QR_MAX_BYTES / 10)

        val result = PcQrRealizer(box).perform(textObject(over), null)

        assertTrue(result is ActionResult.Failure)
        // Отказ — теми же словами, что и на телефоне (#1084): потолок в core один на оба.
        assertEquals(QR_TOO_LONG, (result as ActionResult.Failure).reason)
        assertTrue(box.entries().isEmpty())
    }

    /** #1084: тот самый текст карточки — телефон делал QR, компьютер отказывал. */
    @Test fun `текст, который кодирует телефон, кодируется и здесь`() = runTest {
        val box = outbox()
        val text = "Оплата 4 500 ₽ до 25.08.2026, тел. +7 900 123-45-67, почта sales@example.org — " +
            "счёт № 1084 от 17 августа, получатель ООО «Точка», назначение: сканирование"

        val result = PcQrRealizer(box).perform(textObject(text), null)

        assertTrue("QR не собрался: $result", result is ActionResult.Success)
        val image = javax.imageio.ImageIO.read(File((result as ActionResult.Success).result.uri.value))
        assertTrue("картинка пустая", image.width > 100 && image.width == image.height)
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

    @Test fun `тяжёлый снимок Point укладывает сам, а не отсылает человека жать «Сделать легче»`() = runTest {
        val big = imageObject(2000, 2000)
        assertTrue("картинка вышла меньше предела — проверять нечего", File(big.uri.value).length() > 1024 * 1024)
        var wentOutside: File? = null

        val result = PcCloudOcrRealizer(
            { OcrConfig() },
            readOutside = { _, file, _ -> wentOutside = file; "накладная 4512" },
        ).perform(big, null)

        assertTrue("наружу ушёл исходник, а не уложенная копия", wentOutside!!.length() <= 1024 * 1024)
        assertTrue(result is ActionResult.Done)
        val said = (result as ActionResult.Done).message
        assertTrue("человеку не сказано про подмену: " + said, said.contains("уменьшенн"))
        assertTrue("остался совет из прежнего отказа: " + said, !said.contains("Сделать легче"))
    }

    @Test fun `у чтения снимка одна декларация на оба устройства, а компьютер даёт реализацию`() {

        val shared = desktopCapabilities().first { it.id == com.point.core.flow.capabilities.OcrCapability.ID }

        assertEquals(shared.id, PcCloudOcrRealizer({ OcrConfig() }).capabilityId)
        assertEquals("Распознать текст", shared.label(ObjectState(ObjectKind.IMAGE)))
        assertTrue(
            "цена дороги не названа до тапа",
            shared.yields(ObjectState(ObjectKind.IMAGE)).toString().contains("сервис"),
        )
    }

    /**
     * #1021, решение владельца — «обещание по исполнителю». Компьютер показывал под «Распознать
     * текст» телефонное «сначала на телефоне, потом спрошу про сервис» — шаг, которого на нём
     * не бывает: своего чтения у компьютера нет, снимок может уйти только в сервис.
     */
    @Test fun `на компьютере чтение снимка обещает сервис, а не шаг на телефоне`() {

        val ocr = pcRegistry().bubblesFor(ObjectState(ObjectKind.IMAGE)).first { it.capabilityId.value == "ocr" }
        val note = com.point.core.flow.yieldLabel(ocr.yields).orEmpty()

        assertEquals(OCR_ON_PC_PROMISE, note)
        assertTrue("компьютер обещает шаг на телефоне: $note", "телефон" !in note)
        assertTrue("дорога в сервис не названа до тапа: $note", "сервис" in note)
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

    /**
     * Разбор скрина владельца без live-теста: «Найти в тексте» уже отработала сама при
     * получении объекта (autoInvestigate) и записала «смотрели — не нашлось», а в списке
     * «что можно сделать» всё равно стояла кликабельной кнопкой поверх готового ответа.
     * Причина — Capability исследования без meta.investigation = true.
     */
    @Test fun `«найти в тексте» — знание, а не кнопка поверх готового ответа`() {

        val forText = pcRegistry().bubblesFor(ObjectState(ObjectKind.TEXT)).map { it.capabilityId.value }

        assertTrue(
            "исследование осталось пользовательской кнопкой: $forText",
            "entities" !in forText,
        )
    }

    @Test fun `«найти в тексте» не уезжает на телефон вторым дублем`() {

        val advertised = com.point.core.flow.advertisedActions(pcRegistry().all()).map { it.id }

        assertTrue(
            "исследование объявлено телефону как отдельное действие «на ПК»: $advertised",
            "entities" !in advertised,
        )
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
        "pc-open-link", "transcribe",

        // Сканированный PDF читается и на ПК (#1014): страницы pdfbox + облачное чтение.
        "read-document",

        // Поиск значений в тексте зовётся так же, как на телефоне (#840): работа одна,
        // исполнители разные.
        "entities",
    ) + com.point.core.flow.capabilities.sharedCapabilities().map { it.id.value }

    private fun pcRegistry() = DesktopRegistry(desktopCapabilities())
}
