package com.point.executors

import com.point.core.flow.ObjectStore
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.Spoken
import com.point.core.flow.TextToSpeech
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Голос читает по режиму приватности (#924).
 *
 * Голос, который система выбрала по умолчанию, нередко читает на сервере: короткая фраза
 * проходит незаметно, а статья на шесть тысяч знаков уезжает к чужому сервису целиком. Point
 * обещал обратное — «локально, бесплатно, без сети, то есть приватно по умолчанию».
 *
 * Решение владельца 13.08.2026: «Голос по режиму приватности». Закрытый режим — только голос,
 * читающий на устройстве. Открытый — любой, включая лучший серверный.
 */
class VoiceObeysThePrivacyModeTest {

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

    private class Voice : TextToSpeech {
        var askedOnDeviceOnly: Boolean? = null
        override suspend fun voices() = listOf("ru")
        override suspend fun speak(
            text: String,
            language: String?,
            into: String,
            onDeviceOnly: Boolean,
            onPart: suspend (Int, Int) -> Unit,
        ): Spoken {
            askedOnDeviceOnly = onDeviceOnly
            return Spoken.Done(into)
        }
    }

    private val article = File.createTempFile("point-", ".txt")
        .apply { writeText("Довга стаття, яку зручно слухати за кермом."); deleteOnExit() }

    private fun text() = PointObject(
        id = "text",
        mime = "text/plain",
        uri = ScratchRef(article.absolutePath),
        state = ObjectState(ObjectKind.TEXT),
    )

    private fun spokenAt(level: PrivacyLevel): Boolean {
        val voice = Voice()
        runBlocking { SpeakRealizer(store, voice, privacyAt(level)).perform(text(), null) }
        return voice.askedOnDeviceOnly ?: error("голос не спросили вовсе")
    }

    @Test fun `закрытый режим — читает только голос устройства`() {
        assertEquals(true, spokenAt(PrivacyLevel.DEVICE_ONLY))
    }

    @Test fun `наружу нельзя и по строгому режиму — тоже только устройство`() {
        assertEquals(true, spokenAt(PrivacyLevel.NO_TRAINING))
    }

    @Test fun `открытый режим — годится любой голос, включая лучший серверный`() {
        assertEquals(false, spokenAt(PrivacyLevel.FREE_FIRST))
    }
}
