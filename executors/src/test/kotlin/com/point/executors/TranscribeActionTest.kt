package com.point.executors

import com.point.core.flow.GROQ_PROVIDER_ID
import com.point.core.flow.KEY_SETTINGS_CALL
import com.point.core.flow.LISTENING
import com.point.core.flow.META_SEMANTIC_SUMMARY
import com.point.core.flow.ObjectStore
import com.point.core.flow.SpeechKeyNeed
import com.point.core.flow.SpeechReadiness
import com.point.core.flow.SpeechToText
import com.point.core.flow.refusalNeedsKey
import com.point.core.flow.Transcription
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TranscribeActionTest {

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(
            result: ResultObject,
            from: com.point.core.model.PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("point-", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun clear() = Unit
    }

    private fun recording(sizeBytes: Int, mime: String = "audio/ogg"): PointObject {
        val file = File.createTempFile("voice", ".ogg").apply {
            writeBytes(ByteArray(sizeBytes))
            deleteOnExit()
        }
        return PointObject("id", mime, ScratchRef(file.absolutePath), ObjectState(ObjectKind.AUDIO))
    }

    private fun engine(result: Transcription) = object : SpeechToText {
        override suspend fun transcribe(obj: PointObject): Transcription = result
    }

    private fun brokenEngine(reason: String) = object : SpeechToText {
        override suspend fun transcribe(obj: PointObject): Transcription = error(reason)
    }

    private val ready = SpeechReadiness { emptyList() }

    private val keyless = SpeechReadiness {
        listOf(
            SpeechKeyNeed("Whisper слушает по ключу Groq", GROQ_PROVIDER_ID),
            SpeechKeyNeed("любой сервис AI — по вашему ключу"),
        )
    }

    private val forbidden = object : SpeechToText {
        override suspend fun transcribe(obj: PointObject): Transcription =
            error("движок спросили, хотя ключей нет")
    }

    @Test
    fun `«Расшифровать» появляется на записи и только на ней`() {
        val cap = TranscribeCapability(ready)

        assertTrue(cap.accepts(ObjectState(ObjectKind.AUDIO)))
        assertFalse(cap.accepts(ObjectState(ObjectKind.IMAGE)))
        assertFalse(cap.accepts(ObjectState(ObjectKind.TEXT)))
        assertFalse(cap.accepts(ObjectState(ObjectKind.UNKNOWN, setOf(Feature.LARGE))))
    }

    @Test
    fun `распознавания до тапа не бывает — действие объявлено сетевым и долгим`() {

        val meta = TranscribeCapability(ready).meta

        assertTrue("сеть — только после выбора человека", meta.network)
        assertEquals(com.point.core.flow.Latency.SLOW, meta.latency)
    }

    @Test
    fun `расшифровка обещает знание той же записи, а не новый объект`() {
        val out = TranscribeCapability(ready).produces(ObjectState(ObjectKind.AUDIO))
        assertEquals(ObjectKind.AUDIO, out.kind)
        assertTrue(out.has(com.point.core.model.Feature.HAS_TEXT))
    }

    @Test
    fun `без единого ключа название действия само говорит, что нужен ключ`() {
        assertEquals("Расшифровать", TranscribeCapability(ready).label(ObjectState(ObjectKind.AUDIO)))
        assertEquals(
            "Расшифровать · нужен ключ",
            TranscribeCapability(keyless).label(ObjectState(ObjectKind.AUDIO)),
        )
    }

    @Test
    fun `без ключей действие остаётся на месте — прятать его значило бы прятать саму способность`() {
        assertTrue(TranscribeCapability(keyless).accepts(ObjectState(ObjectKind.AUDIO)))
    }

    @Test
    fun `без ключей отказ приходит до ожидания и называет провайдеров`() = runTest {
        val stages = stagesHeard {
            val result = TranscribeRealizer(store, forbidden, keyless).perform(recording(540 * 1024))

            assertTrue(result is ActionResult.Failure)
            result as ActionResult.Failure
            assertTrue("назван Groq: ${result.reason}", result.reason.contains("ключу Groq"))
            assertTrue("назван любой AI: ${result.reason}", result.reason.contains("любой сервис AI"))
            assertTrue("сказано, куда идти", result.reason.contains(KEY_SETTINGS_CALL))
            assertTrue("экран откроет ключи сам", refusalNeedsKey(result.reason))
            assertTrue("это чинится ключом", result.recoverable)
        }

        assertEquals("«Слушаю запись…» над работой, которой не будет, — обещание впустую", emptyList<String>(), stages)
    }

    /**
     * Решение владельца 12.08.2026: «внизу лучше писать полный текст». Суть говорится
     * подписью объекта — сверху и один раз; в файле лежит сама расшифровка (#873).
     */
    @Test
    fun `один тап приносит и суть, и дословный текст — знанием той же записи`() = runTest {

        val said = "Перезвони мне до шести"
        val gist = "Просят перезвонить до шести"

        val result = TranscribeRealizer(store, engine(Transcription.Heard(said, gist)), ready)
            .perform(recording(1024))

        // Расшифровка — знание записи, а не новый объект (#1097, GRF-006).
        assertTrue(result is ActionResult.Done)
        val found = (result as ActionResult.Done).findings!!
        val ref = found.metadata.getValue(com.point.core.flow.META_OCR_TEXT_REF)
        assertEquals(said, File(ref).readText().trim())
        assertEquals(gist, found.metadata[com.point.core.flow.META_SEMANTIC_SUMMARY])
        assertTrue(com.point.core.model.Feature.HAS_TEXT in found.features)
    }

    @Test
    fun `суть видна на самом объекте, а не только внутри файла`() {

        runTest {
            val heard = Transcription.Heard("Длинный текст", "Просят перезвонить")
            val found = (TranscribeRealizer(store, engine(heard), ready).perform(recording(1024)) as ActionResult.Done).findings!!

            assertEquals("Просят перезвонить", found.metadata[META_SEMANTIC_SUMMARY])
        }
    }

    @Test
    fun `без сути объект не получает пустого обещания`() = runTest {
        val found = (TranscribeRealizer(store, engine(Transcription.Heard("Только слова")), ready)
            .perform(recording(1024)) as ActionResult.Done).findings!!

        assertFalse(META_SEMANTIC_SUMMARY in found.metadata)
    }

    @Test
    fun `тишина говорит про запись, и повторять её незачем`() = runTest {
        val result = TranscribeRealizer(store, engine(Transcription.Silence), ready).perform(recording(1024))

        assertTrue(result is ActionResult.Failure)
        result as ActionResult.Failure
        assertEquals("В записи не слышно речи", result.reason)
        assertFalse("повтор тишину не расшифрует", result.recoverable)
    }

    @Test
    fun `сбой движка доходит до человека его же словами, а не пустым текстом`() = runTest {
        val result = TranscribeRealizer(store, brokenEngine("Этот формат записи не читается"), ready)
            .perform(recording(1024, mime = "audio/amr"))

        assertTrue(result is ActionResult.Failure)
        result as ActionResult.Failure
        assertEquals("Этот формат записи не читается", result.reason)
        assertTrue("это чинится — ключом, сетью, другим файлом", result.recoverable)
    }

    @Test
    fun `над трёхминутной записью экран сразу говорит, что это займёт время`() = runTest {

        val heard = Transcription.Heard("текст")
        val stages = stagesHeard {
            TranscribeRealizer(store, engine(heard), ready).perform(recording(540 * 1024))
        }

        assertEquals(1, stages.size)
        assertTrue(stages.single().startsWith(LISTENING))
        assertTrue("3 мин" in stages.single())
        assertTrue("займёт время" in stages.single())
    }

    @Test
    fun `над короткой записью лишних обещаний нет`() = runTest {
        val stages = stagesHeard {
            TranscribeRealizer(store, engine(Transcription.Heard("текст")), ready).perform(recording(60 * 1024))
        }

        assertEquals(listOf(LISTENING), stages)
    }
}
