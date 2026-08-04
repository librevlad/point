package com.point.executors

import com.point.core.flow.LISTENING
import com.point.core.flow.META_SEMANTIC_SUMMARY
import com.point.core.flow.ObjectStore
import com.point.core.flow.SpeechToText
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

/**
 * «Расшифровать» (#223): голосовое → текст, у которого суть уже сверху.
 *
 * Тесты судят обещания продукта, а не форму кода: сеть не трогается до тапа, суть приезжает
 * вместе с расшифровкой (и это НЕ цепочка), тишина и сбой движка звучат разными словами, а над
 * длинной записью экран не молчит.
 */
class TranscribeActionTest {

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(result: ResultObject) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("point-", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun clear() = Unit
    }

    /** Запись на диске: длину действие читает само — от неё зависит предупреждение о времени. */
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

    // --- Пузырёк: что можно сделать с записью ---

    @Test
    fun `«Расшифровать» появляется на записи и только на ней`() {
        val cap = TranscribeCapability()

        assertTrue(cap.accepts(ObjectState(ObjectKind.AUDIO)))
        assertFalse(cap.accepts(ObjectState(ObjectKind.IMAGE)))
        assertFalse(cap.accepts(ObjectState(ObjectKind.TEXT)))
        assertFalse(cap.accepts(ObjectState(ObjectKind.UNKNOWN, setOf(Feature.LARGE))))
    }

    @Test
    fun `распознавания до тапа не бывает — действие объявлено сетевым и долгим`() {
        // Инвариант первого экрана: сеть держится вне ≤300 мс объявлением, а не дисциплиной.
        // Сломается объявление — сломается и обещание, поэтому оно проверяется здесь.
        val meta = TranscribeCapability().meta

        assertTrue("сеть — только после выбора человека", meta.network)
        assertEquals(com.point.core.flow.Latency.SLOW, meta.latency)
    }

    @Test
    fun `расшифровка обещает текст — дальше он живёт по общим правилам графа`() {
        assertEquals(
            ObjectState(ObjectKind.TEXT),
            TranscribeCapability().produces(ObjectState(ObjectKind.AUDIO)),
        )
    }

    // --- Работа ---

    @Test
    fun `один тап приносит и суть, и дословный текст`() = runTest {
        // Суть НЕ вторым действием: цепочка — это когда Point сам запускает следующее действие.
        // Здесь один тап, один запрос к движку, один объект.
        val heard = Transcription.Heard("Перезвони мне до шести", "Просят перезвонить до шести")
        val result = TranscribeRealizer(store, engine(heard)).perform(recording(1024))

        assertTrue(result is ActionResult.Success)
        val out = (result as ActionResult.Success).result
        assertEquals(ObjectKind.TEXT, out.type)
        val text = File(out.uri.value).readText()
        assertTrue("Перезвони мне до шести" in text)
        assertTrue("Просят перезвонить до шести" in text)
        assertTrue("суть стоит выше расшифровки", text.indexOf("## Суть") < text.indexOf("## Расшифровка"))
    }

    @Test
    fun `суть видна на самом объекте, а не только внутри файла`() {
        // `semantic.summary` — то самое место, которым экран показывает суть «Понять»;
        // расшифровка пользуется им же, а не заводит свой экран.
        runTest {
            val heard = Transcription.Heard("Длинный текст", "Просят перезвонить")
            val out = (TranscribeRealizer(store, engine(heard)).perform(recording(1024)) as ActionResult.Success).result

            assertEquals("Просят перезвонить", out.metadata[META_SEMANTIC_SUMMARY])
        }
    }

    @Test
    fun `без сути объект не получает пустого обещания`() = runTest {
        val out = (TranscribeRealizer(store, engine(Transcription.Heard("Только слова")))
            .perform(recording(1024)) as ActionResult.Success).result

        assertFalse(META_SEMANTIC_SUMMARY in out.metadata)
    }

    // --- Честный отказ ---

    @Test
    fun `тишина говорит про запись, и повторять её незачем`() = runTest {
        val result = TranscribeRealizer(store, engine(Transcription.Silence)).perform(recording(1024))

        assertTrue(result is ActionResult.Failure)
        result as ActionResult.Failure
        assertEquals("В записи не слышно речи", result.reason)
        assertFalse("повтор тишину не расшифрует", result.recoverable)
    }

    @Test
    fun `сбой движка доходит до человека его же словами, а не пустым текстом`() = runTest {
        val result = TranscribeRealizer(store, brokenEngine("Этот формат записи модель не читает"))
            .perform(recording(1024, mime = "audio/amr"))

        assertTrue(result is ActionResult.Failure)
        result as ActionResult.Failure
        assertEquals("Этот формат записи модель не читает", result.reason)
        assertTrue("это чинится — ключом, сетью, другим файлом", result.recoverable)
    }

    // --- Долгая работа не молчит (#288) ---

    @Test
    fun `над трёхминутной записью экран сразу говорит, что это займёт время`() = runTest {
        // 3 минуты opus ≈ 540 КБ. Молчание здесь читается как «зависло».
        val heard = Transcription.Heard("текст")
        val stages = stagesHeard {
            TranscribeRealizer(store, engine(heard)).perform(recording(540 * 1024))
        }

        assertEquals(1, stages.size)
        assertTrue(stages.single().startsWith(LISTENING))
        assertTrue("3 мин" in stages.single())
        assertTrue("займёт время" in stages.single())
    }

    @Test
    fun `над короткой записью лишних обещаний нет`() = runTest {
        val stages = stagesHeard {
            TranscribeRealizer(store, engine(Transcription.Heard("текст"))).perform(recording(60 * 1024))
        }

        assertEquals(listOf(LISTENING), stages)
    }
}
