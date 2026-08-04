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
    private class FakeEngine(
        private val outcome: Result<Transcription>,
        private val need: SpeechKeyNeed? = null,
    ) : SpeechToText {
        var calls = 0

        override fun missingKey(): SpeechKeyNeed? = need

        override suspend fun transcribe(obj: PointObject): Transcription {
            calls++
            return outcome.getOrThrow()
        }

        companion object {
            fun hears(text: String) = FakeEngine(Result.success(Transcription.Heard(text)))

            fun silent() = FakeEngine(Result.success(Transcription.Silence))

            fun refuses(why: String) = FakeEngine(Result.failure(IllegalStateException(why)))

            /** Ключа нет — движка сегодня нет. Спросят его только по ошибке, и это будет видно. */
            fun keyless(phrase: String, providerId: String? = null) = FakeEngine(
                Result.failure(IllegalStateException("этот движок спрашивать было нельзя")),
                SpeechKeyNeed(phrase, providerId),
            )
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

    // --- Ключей нет вовсе (#467): владелец живьём получал «общую непонятную ошибку» ---

    @Test
    fun `ключа нет ни у кого — отказ называет провайдеров и зовёт в настройки`() = runTest {
        val chain = FirstHeardSpeechToText(
            listOf(
                FakeEngine.keyless("Whisper слушает по ключу Groq", GROQ_PROVIDER_ID),
                FakeEngine.keyless("модель общего назначения — по любому ключу AI"),
            ),
        )

        val said = runCatching { chain.transcribe(recording) }.exceptionOrNull()!!.message!!

        assertTrue("назван конкретный провайдер: $said", said.contains("ключу Groq"))
        assertTrue("сказано, что годится и любой другой: $said", said.contains("любому ключу AI"))
        assertTrue("сказано, куда идти: $said", said.contains(KEY_SETTINGS_CALL))
        assertTrue("экран узнаёт такой отказ и откроет ключи", refusalNeedsKey(said))
    }

    @Test
    fun `ненастроенный движок не спрашивается вовсе`() = runTest {
        // Спросить его значило бы положить «нет ключа» в сводку рядом с настоящей причиной второго
        // — и утопить её. Ключа нет — движка сегодня нет.
        val keyless = FakeEngine.keyless("Whisper слушает по ключу Groq", GROQ_PROVIDER_ID)
        val working = FakeEngine.hears("А може до якого ґазди?")

        val heard = FirstHeardSpeechToText(listOf(keyless, working)).transcribe(recording)

        assertEquals("А може до якого ґазди?", (heard as Transcription.Heard).text)
        assertEquals("ненастроенного не тревожили", 0, keyless.calls)
    }

    @Test
    fun `пока слышит хоть кто-то, ключ не нужен`() = runTest {
        // Подсказка «нужен ключ» до тапа обязана молчать, пока расшифровка работает: полуправда на
        // экране дороже молчания.
        val chain = FirstHeardSpeechToText(
            listOf(FakeEngine.keyless("Whisper слушает по ключу Groq"), FakeEngine.hears("текст")),
        )

        assertTrue(chain.missingKeys().isEmpty())
        assertEquals(null, chain.missingKey())
    }

    @Test
    fun `нужен ключ виден ДО работы, а не после ожидания`() = runTest {
        val chain = FirstHeardSpeechToText(
            listOf(
                FakeEngine.keyless("Whisper слушает по ключу Groq", GROQ_PROVIDER_ID),
                FakeEngine.keyless("модель общего назначения — по любому ключу AI"),
            ),
        )

        val needs = chain.missingKeys()

        assertEquals(2, needs.size)
        assertEquals(GROQ_PROVIDER_ID, needs.first().providerId)
        assertEquals("подсказка и отказ — одни и те же слова", speechKeyRefusal(needs), speechKeyRefusal(chain.missingKeys()))
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
