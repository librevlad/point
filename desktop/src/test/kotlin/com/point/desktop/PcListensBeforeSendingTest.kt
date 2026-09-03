package com.point.desktop

import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.sin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Компьютер слушает запись сам — прежде сервиса (#1312).
 *
 * Осталось от #1053: телефон разбирает запись до сэмплов и пустую никуда не отправляет, а
 * запись, брошенная в окно Point на компьютере, уезжала в сервис не слушанной — и на
 * цифровой тишине оттуда приходила выдумка вроде «Thank you.», ложившаяся знанием об
 * объекте.
 *
 * Правило одно на оба устройства и лежит в `:core:flow`; здесь проверяется, что компьютер
 * его соблюдает — и что незнакомый формат при этом расшифровку не отнимает.
 */
class PcListensBeforeSendingTest {

    @get:Rule val temp = TemporaryFolder()

    private val readyOnPc = com.point.core.flow.SpeechReadiness { emptyList() }

    /** Сервис, который считает, сколько раз его спросили, и отвечает заранее известным. */
    private fun counting(answer: String, hit: () -> Unit) = object : com.point.core.flow.SpeechToText {
        override suspend fun transcribe(obj: PointObject): com.point.core.flow.Transcription {
            hit()
            return if (answer.isBlank()) com.point.core.flow.Transcription.Silence else com.point.core.flow.Transcription.Heard(answer)
        }
    }

    private fun pcRealizer(speech: com.point.core.flow.SpeechToText) =
        com.point.core.flow.TranscribeRealizer(speech, readyOnPc, JvmAudioLevel(), PcTextInTemp)

    @Test
    fun `цифровая тишина в сервис не едет`() = runTest {
        var asked = 0
        val realizer = pcRealizer(counting("Thank you.") { asked++ })

        val result = realizer.perform(recording(wav(seconds = 1, loud = false)), null)

        assertEquals("тишину отправили в сервис", 0, asked)
        assertTrue("тишина не стала ответом: $result", result is ActionResult.Done)
        assertEquals(com.point.core.flow.NO_SPEECH_HEARD, (result as ActionResult.Done).message)
        assertEquals(
            com.point.core.flow.InvestigationState.NOT_FOUND,
            com.point.core.flow.investigationStateOf(
                result.findings!!.metadata,
                com.point.core.flow.KnownCapabilities.TRANSCRIBE,
            ),
        )
    }

    @Test
    fun `запись со звуком едет в сервис, как и раньше`() = runTest {
        var asked = 0
        val realizer = pcRealizer(counting("Перезвони мне") { asked++ })

        val result = realizer.perform(recording(wav(seconds = 1, loud = true)), null)

        assertEquals("запись со звуком до сервиса не дошла", 1, asked)
        assertTrue(result is ActionResult.Done)
    }

    /**
     * Незнакомый формат — «не измерили», а не «тихо»: молча отнимать у человека расшифровку
     * нельзя (ADR-0001 §9).
     */
    @Test
    fun `запись, которую компьютер не разобрал, идёт дальше обычным путём`() = runTest {
        var asked = 0
        val notAudio = temp.newFile("voice.mp3").apply { writeBytes(ByteArray(2048) { 7 }) }
        val realizer = pcRealizer(counting("Перезвони мне") { asked++ })

        realizer.perform(recording(notAudio), null)

        assertEquals("неразобранную запись объявили тишиной", 1, asked)
    }

    private fun recording(file: File) = PointObject(
        id = "rec",
        mime = "audio/wav",
        uri = ScratchRef(file.absolutePath),
        state = ObjectState(ObjectKind.AUDIO),
        metadata = mapOf("name" to file.name),
    )

    /** Настоящий WAV: тишина — нули, звук — синус на полшкалы. */
    private fun wav(seconds: Int, loud: Boolean): File {
        val rate = 8000
        val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
        val frames = rate * seconds
        val bytes = ByteArray(frames * 2)
        if (loud) {
            for (i in 0 until frames) {
                val sample = (sin(i * 2 * Math.PI * 440 / rate) * 16_000).toInt().toShort()
                bytes[i * 2] = (sample.toInt() and 0xFF).toByte()
                bytes[i * 2 + 1] = (sample.toInt() shr 8).toByte()
            }
        }
        val out = temp.newFile(if (loud) "voice-loud.wav" else "voice-silent.wav")
        AudioSystem.write(
            AudioInputStream(ByteArrayInputStream(bytes), format, frames.toLong()),
            AudioFileFormat.Type.WAVE,
            out,
        )
        return out
    }
}
