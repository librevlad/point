package com.point.executors

import com.point.core.flow.AudioLevel
import com.point.core.flow.Capability
import com.point.core.flow.InvestigationState
import com.point.core.flow.LinkedPc
import com.point.core.flow.NO_SPEECH_HEARD
import com.point.core.flow.PcLinks
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcTransport
import com.point.core.flow.Realizer
import com.point.core.flow.SpeechReadiness
import com.point.core.flow.SpeechToText
import com.point.core.flow.Transcription
import com.point.core.flow.investigationStateOf
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Пустая запись не едет никуда — ни в сервис, ни на компьютер (#1053, решение владельца
 * 21.08.2026: «слушать до отправки»).
 *
 * Двадцать секунд цифровой тишины возвращались из Whisper фразой «Thank you.», и выдумка
 * ложилась знанием об объекте. Телефон слушать научился — но у телефона, где в круге есть
 * компьютер, «Расшифровать» исполняют двое: своя расшифровка и компьютер, объявивший то же
 * умение под тем же именем. Своё «в записи не слышно речи» цепочка принимала за пустые руки,
 * уступала очередь компьютеру, и запись уезжала к нему, а оттуда — в тот же сервис за той же
 * выдумкой. Обещание «в сервис не едет вовсе» держалось только там, где компьютера нет.
 *
 * Здесь проверяется весь путь человека целиком: телефон с компьютером в круге, тап
 * «Расшифровать», и запись, которую никто не увозит.
 */
class EmptyRecordingGoesNowhereTest {

    @Test fun `в записи нечего слушать — её не забирает и компьютер`() = runTest {
        val transport = WatchfulTransport()

        val result = phoneWithPc(transport, level = silent, speech = forbidden)
            .realizerFor(TranscribeCapability.ID, voice.state)
            .perform(voice)

        assertFalse("пустая запись всё-таки уехала на компьютер", transport.sent)
        assertTrue("тишина ответила не знанием, а $result", result is ActionResult.Done)
        result as ActionResult.Done
        assertEquals(NO_SPEECH_HEARD, result.message)
        assertEquals(
            InvestigationState.NOT_FOUND,
            investigationStateOf(result.findings!!.metadata, TranscribeCapability.ID),
        )
    }

    /**
     * Сторож не пуст: компьютер в этой сборке и правда исполнитель той же способности, и
     * очередь до него доходит — просто не на тишине.
     */
    @Test fun `своя попытка сорвалась — очередь принимает компьютер`() = runTest {
        val transport = WatchfulTransport()

        phoneWithPc(transport, level = audible, speech = broken)
            .realizerFor(TranscribeCapability.ID, voice.state)
            .perform(voice)

        assertTrue("компьютер очередь не принял, хотя своя попытка сорвалась", transport.sent)
    }

    /**
     * Ответ сервиса «речи не слышно» — про речь, а не про звук (#1274, #1054): следующий
     * движок за ним ещё может услышать, и очередь ему остаётся.
     */
    @Test fun `ответ сервиса про речь очередь не закрывает`() = runTest {
        val transport = WatchfulTransport()

        phoneWithPc(transport, level = audible, speech = engine(Transcription.Silence))
            .realizerFor(TranscribeCapability.ID, voice.state)
            .perform(voice)

        assertTrue("за чужим ответом про речь никого больше не спросили", transport.sent)
    }

    private fun phoneWithPc(transport: PcTransport, level: AudioLevel, speech: SpeechToText): DefaultResolver {
        val own: Set<Capability> = setOf(TranscribeCapability(ready))
        val registry = DefaultCapabilityRegistry(
            own + remotePcCapabilities(own, advertisedByPc, pairedPc),
            DefaultBubblePolicy(),
        )
        val realizers = setOf<Realizer>(TranscribeRealizer(NoStore, speech, ready, level)) +
            remotePcRealizers(own, advertisedByPc, pairedPc, transport)
        return DefaultResolver(realizers, registry)
    }

    /** Компьютер, который молча запоминает, показали ли ему запись. */
    private class WatchfulTransport : PcTransport {
        var sent = false
        override suspend fun send(
            pc: LinkedPc,
            obj: PointObject,
            fileName: String,
            meta: Map<String, String>,
            action: String?,
        ): PcSendOutcome {
            sent = true
            return PcSendOutcome.Parked
        }

        override suspend fun fetchCaps(pc: LinkedPc): List<PcRemoteAction>? = null
        override suspend fun fetchOutbox(pc: LinkedPc): List<com.point.core.flow.PcOutboxEntry>? = null
        override suspend fun downloadOutboxFile(pc: LinkedPc, id: Int, targetPath: String) = false
        override suspend fun ackOutbox(pc: LinkedPc, id: Int) = Unit
        override suspend fun pushPhoneCaps(pc: LinkedPc, caps: List<PcRemoteAction>) = false
        override suspend fun exchangeSecrets(
            pc: LinkedPc,
            mine: com.point.core.flow.SharedSecrets,
        ): com.point.core.flow.SharedSecrets? = null
    }

    private companion object {

        val voice: PointObject = PointObject(
            "voice",
            "audio/ogg",
            ScratchRef(File.createTempFile("voice", ".ogg").apply { deleteOnExit() }.absolutePath),
            ObjectState(ObjectKind.AUDIO),
            metadata = mapOf("name" to "запись.ogg"),
        )

        val ready = SpeechReadiness { emptyList() }

        /** Цифровая тишина: телефон разобрал запись и не встретил в ней звука. */
        val silent = AudioLevel { 0.0 }

        val audible = AudioLevel { 0.4 }

        /** Свой движок, который обязан остаться неспрошенным. */
        val forbidden = object : SpeechToText {
            override suspend fun transcribe(obj: PointObject): Transcription =
                error("пустую запись всё-таки показали сервису")
        }

        val broken = object : SpeechToText {
            override suspend fun transcribe(obj: PointObject): Transcription = error("сеть отвалилась")
        }

        fun engine(said: Transcription) = object : SpeechToText {
            override suspend fun transcribe(obj: PointObject): Transcription = said
        }

        /** Компьютер объявил своё «transcribe» — то же умение под тем же именем. */
        val advertisedByPc = listOf(PcRemoteAction("transcribe", "Расшифровать", setOf("AUDIO")))

        val pairedPc = object : PcLinks {
            override fun current() = LinkedPc("d-pc", "Домашний ПК", "ключ")
            override suspend fun save(pc: LinkedPc) = Unit
            override suspend fun clear() = Unit
        }

        val NoStore = object : com.point.core.flow.ObjectStore {
            override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
            override suspend fun ingestMultiple(sources: List<String>) = error("unused")
            override suspend fun put(
                result: com.point.core.model.ResultObject,
                from: PointObject?,
                by: com.point.core.model.CapabilityId?,
            ) = error("unused")
            override suspend fun children(collection: PointObject, limit: Int) = error("unused")
            override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
            override suspend fun newScratchFile(extension: String) = error("unused")
            override suspend fun clear() = Unit
        }
    }
}
