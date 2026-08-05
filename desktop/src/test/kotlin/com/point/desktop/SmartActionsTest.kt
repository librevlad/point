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

    @Test fun `находки складываются в объект, который можно забрать на телефон`() = runTest {
        val box = outbox()
        val realizer = PcEntitiesRealizer(com.point.core.flow.RegexEntityExtractor(), box)

        val result = realizer.perform(
            textObject("Ирина, +7 916 123-45-67, irina@example.com, оплата 48500 руб."),
            null,
        )

        assertTrue("действие не дошло до конца: $result", result is ActionResult.Done)
        val produced = box.entries().single()
        val report = box.file(produced.id)!!.readText()
        assertTrue("в отчёте нет телефона:\n$report", report.contains("+7 916 123-45-67"))
        assertTrue("в отчёте нет почты:\n$report", report.contains("irina@example.com"))
        assertEquals("Найдено в тексте", produced.meta["name"])
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

        assertTrue(result is ActionResult.Done)
        val produced = box.entries().single()
        assertEquals("Перевод", produced.meta["name"])
        assertEquals("перевод", box.file(produced.id)!!.readText())
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

        assertTrue("QR не собрался: $result", result is ActionResult.Done)
        val produced = box.entries().single()
        assertEquals("image/png", produced.meta["mime"])
        val image = javax.imageio.ImageIO.read(box.file(produced.id)!!)
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
