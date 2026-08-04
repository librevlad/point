package com.point.core.flow

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * Очередь движков расшифровки (#223) судится обещаниями, а не устройством.
 *
 * Обещаний четыре: отказавший движок не оставляет человека без ответа; **тишина никуда дальше не
 * уезжает**; полный отказ приходит словами, а не пустой расшифровкой; услышавший первым останавливает
 * очередь — вторая отправка личной записи ради того же текста не бывает бесплатной ни в квоте, ни в
 * приватности.
 */
class FirstHeardSpeechToTextTest {

    private val recording = PointObject(
        "id", "audio/ogg", ScratchRef("/scratch/voice.ogg"), ObjectState(ObjectKind.AUDIO),
    )

    /** Движок с заранее известным исходом; считает, сколько раз его спросили. */
    private class FakeEngine(private val outcome: Result<Transcription>) : SpeechToText {
        var calls = 0
        override suspend fun transcribe(obj: PointObject): Transcription {
            calls++
            return outcome.getOrThrow()
        }

        companion object {
            fun hears(text: String) = FakeEngine(Result.success(Transcription.Heard(text)))

            fun silent() = FakeEngine(Result.success(Transcription.Silence))

            fun refuses(why: String) = FakeEngine(Result.failure(IllegalStateException(why)))
        }
    }

    @Test
    fun `первый отказал — отвечает второй`() = runTest {
        val first = FakeEngine.refuses("Whisper - слишком часто (429), пробуем следующий движок")
        val second = FakeEngine.hears("Привет, перезвони мне до шести.")

        val heard = FirstHeardSpeechToText(listOf(first, second)).transcribe(recording)

        assertEquals("Привет, перезвони мне до шести.", (heard as Transcription.Heard).text)
        assertEquals(1, second.calls)
    }

    @Test
    fun `тишина не уходит второму движку`() = runTest {
        // Тишина — это то, что движок УСЛЫШАЛ, а не то, что у него не вышло. Переспрашивать чужие
        // уши значило бы отправить личную запись во второй сервис ради того же самого ответа.
        val first = FakeEngine.silent()
        val second = FakeEngine.hears("что-то, чего в записи нет")

        val heard = FirstHeardSpeechToText(listOf(first, second)).transcribe(recording)

        assertEquals(Transcription.Silence, heard)
        assertEquals("второго не тревожили", 0, second.calls)
    }

    @Test
    fun `услышавший первым останавливает очередь`() = runTest {
        val first = FakeEngine.hears("А може до якого ґазди?")
        val second = FakeEngine.hears("другой текст")

        FirstHeardSpeechToText(listOf(first, second)).transcribe(recording)

        assertEquals(0, second.calls)
    }

    @Test
    fun `нет ни одного ключа — человеческий текст, а не пустая расшифровка`() = runTest {
        val chain = FirstHeardSpeechToText(
            listOf(
                FakeEngine.refuses("Whisper не настроен — нет ключа Groq"),
                FakeEngine.refuses("AI не настроен — задайте свой ключ"),
            ),
        )

        val said = runCatching { chain.transcribe(recording) }.exceptionOrNull()!!.message!!

        assertTrue("названо, что именно не вышло: $said", said.contains("Whisper не настроен"))
        assertTrue(said.contains("AI не настроен — задайте свой ключ"))
    }

    @Test
    fun `единственный отказ доходит своими словами`() = runTest {
        // Движок уже назвал причину; обёртка над ней добавила бы человеку только шум.
        val chain = FirstHeardSpeechToText(
            listOf(FakeEngine.refuses("AI недоступен — нет подключения к интернету")),
        )

        val e = runCatching { chain.transcribe(recording) }.exceptionOrNull()

        assertEquals("AI недоступен — нет подключения к интернету", e!!.message)
    }

    @Test
    fun `пустая очередь отказывает словами, а не молчанием`() = runTest {
        val e = runCatching { FirstHeardSpeechToText(emptyList()).transcribe(recording) }.exceptionOrNull()

        assertTrue(e!!.message!!.contains("Расшифровать некому"))
    }

    @Test
    fun `отмена человеком не роняет запись в следующий сервис`() = runTest {
        // Отмена (#288) — не отказ движка. Проглоти её как обычную ошибку — и тап по «Отмена»
        // посреди долгой записи запускал бы ЕЩЁ ОДНУ отправку, ровно ту, о которой просили не делать.
        val cancelled = FakeEngine(Result.failure(CancellationException("отменено")))
        val second = FakeEngine.hears("текст, за которым уже не идут")

        val e = runCatching {
            FirstHeardSpeechToText(listOf(cancelled, second)).transcribe(recording)
        }.exceptionOrNull()

        assertTrue("отмена ушла наверх, а не в сводку отказов", e is CancellationException)
        assertEquals(0, second.calls)
    }
}
