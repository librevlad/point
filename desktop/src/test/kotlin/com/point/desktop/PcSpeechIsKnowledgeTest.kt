package com.point.desktop

import com.point.core.flow.InvestigationState
import com.point.core.flow.KnownCapabilities
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.investigationStateOf
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * «Расшифровать» на компьютере кладёт слова знанием на саму запись (#1097).
 *
 * Решение #1157 было выполнено только на телефоне: компьютер рождал отдельный объект
 * «Расшифровка», у самой записи не появлялось ни текста, ни закрытого вопроса, — а через
 * связку на телефон приезжал чужой узел вместо знания (GRF-006). CLAUDE.md, «Продолжение на
 * компьютере»: «На той стороне это тот же объект, а не новый».
 *
 * Здесь же — отказ сервиса расшифровки (#1255): он тонул в общем «попробуйте позже», и
 * человек жал снова там, где сегодня уже не заработает.
 */
class PcSpeechIsKnowledgeTest {

    @get:Rule val temp = TemporaryFolder()

    private var server: HttpServer? = null

    @After fun stop() {
        server?.stop(0)
    }

    private fun serve(code: Int, body: String): String {
        val s = HttpServer.create(InetSocketAddress(0), 0)
        s.createContext("/transcriptions") { exchange ->
            exchange.requestBody.readBytes()
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/transcriptions"
    }

    private fun recording(): PointObject {
        val file = temp.newFile("голосовое.ogg").apply { writeBytes(ByteArray(1024)) }
        return PointObject(
            "aud",
            "audio/ogg",
            ScratchRef(file.absolutePath),
            ObjectState(ObjectKind.AUDIO),
            metadata = mapOf("name" to "голосовое.ogg"),
        )
    }

    @Test fun `расшифровка ложится знанием на саму запись, а не рождает объект`() = runTest {
        val realizer = PcTranscribeRealizer(
            { SpeechConfig(key = "есть") },
            askOutside = { _, _, _ -> "Встречаемся в четверг" },
        )

        val result = realizer.perform(recording(), null)

        assertTrue("на компьютере снова родился отдельный объект: $result", result is ActionResult.Done)
        val found = (result as ActionResult.Done).findings!!
        assertTrue("у записи не появилось текста", Feature.HAS_TEXT in found.features)
        val words = File(found.metadata.getValue(META_OCR_TEXT_REF)).readText()
        assertTrue("расшифрованное не легло слоем у записи: $words", words.contains("четверг"))
        assertEquals(
            InvestigationState.FOUND,
            investigationStateOf(found.metadata, KnownCapabilities.TRANSCRIBE),
        )
        assertEquals("одна работа — одни слова на двух устройствах", com.point.core.flow.SPEECH_IS_KNOWLEDGE, result.message)
    }

    /** Обещание действия — то же, что у телефона: знание записи, а не новая вещь. */
    @Test fun `дверь обещает знание записи, а не рождение текста`() {
        val cap = PcTranscribeCapability()
        val audio = ObjectState(ObjectKind.AUDIO)

        assertTrue("расшифровка снова обещает новый объект", cap.produces(audio)!!.has(Feature.HAS_TEXT))
        assertEquals(ObjectKind.AUDIO, cap.produces(audio)!!.kind)
        assertTrue(com.point.core.model.Intent.UNDERSTAND in cap.intents(audio))
    }

    /**
     * Бесплатное на сегодня кончилось — так и сказано (#1255). Прежде `require` бросал обычное
     * исключение, общий перехват накрывал его своим «Сервис расшифровки не ответил», и человек
     * жал снова и снова: сегодня это уже не заработает.
     */
    @Test fun `слишком часто — человек слышит про бесплатное, а не «попробуйте позже»`() = runTest {
        val url = serve(429, "slow down")
        val realizer = PcTranscribeRealizer({ SpeechConfig(key = "есть", url = url) })

        val said = (realizer.perform(recording(), null) as ActionResult.Failure).reason

        assertTrue(said, com.point.core.flow.looksLikeQuotaFailure(said))
        assertFalse("код протокола человеку ни о чём не говорит: $said", said.contains("429"))
    }

    /**
     * Медленный ответ сервиса — обычный случай для живого голосового (#1097): бюджет
     * синхронного ответа телефону 10 с, а расшифровка объявлена медленной и ждёт до трёх
     * минут. Результат попадает телефону через очередь, и знание в нём — текстом, а не путём
     * к файлу на диске этого компьютера.
     *
     * Прежде отправка через очередь этой замены не делала: телефон получал неоткрываемый путь
     * и рядом закрытый вопрос — ни строчки текста и никакого предложения расшифровать ещё раз.
     */
    @Test fun `медленная расшифровка едет телефону словами, а не путём на чужом диске`() {
        val heard = "Встречаемся в четверг"
        val outbox = Outbox(temp.newFolder("out-speech"))
        val realizer = PcTranscribeRealizer(
            { SpeechConfig(key = "есть") },
            askOutside = { _, _, _ ->
                Thread.sleep(300)
                heard
            },
        )
        val state = DesktopState(
            DesktopRegistry(setOf(PcTranscribeCapability())),
            object : com.point.core.flow.Resolver {
                override fun realizerFor(capabilityId: com.point.core.model.CapabilityId) = realizer
            },
            clipboard = { },
            outbox = outbox,
        )
        val item = InboxItem(recording().copy(metadata = mapOf(com.point.core.flow.META_ORIGIN_ID to "phone-aud")))

        val answer = state.runRemoteActionNow(KnownCapabilities.TRANSCRIBE.value, item, budgetMs = 50)

        assertEquals(com.point.core.flow.PC_STILL_WORKING, (answer as ActionResult.Done).message)
        val deadline = System.currentTimeMillis() + 5_000
        while (outbox.entries().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("исход медленной работы не лёг в очередь телефону", outbox.entries().isNotEmpty())
        val meta = outbox.entries().single().meta
        val u = com.point.core.flow.PcResultFields.UNDERSTOOD

        assertEquals("текста записи телефон не получил", heard, meta[u + com.point.core.flow.META_READ_TEXT])
        assertNull(
            "телефону отправлен путь к файлу на этом компьютере — там он не открывается",
            meta[u + META_OCR_TEXT_REF],
        )
        assertEquals(
            "вопрос отправлен закрытым — а текста при нём нет",
            InvestigationState.FOUND.wire,
            meta[u + com.point.core.flow.investigationKey(KnownCapabilities.TRANSCRIBE)],
        )
    }

    /** Ключ не принят — и подсказка про нужный сервис доходит, а не остаётся мёртвым текстом. */
    @Test fun `ключ не принят — сказано про ключ и про нужный сервис`() = runTest {
        val url = serve(401, "unauthorized")
        val realizer = PcTranscribeRealizer({ SpeechConfig(key = "есть", url = url) })

        val said = (realizer.perform(recording(), null) as ActionResult.Failure).reason

        assertTrue(said, said.startsWith(com.point.core.flow.KEY_NOT_TAKEN))
        assertTrue("подсказка про сервис так и не дошла: $said", said.contains("Groq"))
    }
}
