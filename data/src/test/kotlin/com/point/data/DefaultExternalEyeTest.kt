package com.point.data

import com.point.core.flow.CloudPrivacySettings
import com.point.core.flow.NetworkAvailability
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.ReaderPrivacy
import com.point.core.flow.ReaderPromise
import com.point.core.model.PointObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultExternalEyeTest {

    private val promises = ReaderPrivacy("OVH, Франция (ЕС)", ReaderPromise.NO_TRAINING)

    private val learns = ReaderPrivacy("Mistral, Франция (ЕС)", ReaderPromise.TRAINS)

    private fun level(level: PrivacyLevel) = object : CloudPrivacySettings {
        override fun level() = level
        override suspend fun setLevel(level: PrivacyLevel) = Unit
    }

    private fun eye(
        name: String,
        privacy: ReaderPrivacy = promises,
        hasKey: Boolean = true,
        takesObject: Boolean = true,
        answer: () -> String,
    ) = object : CloudTextReader {
        override val reader = name
        override val privacy = privacy
        override val configured = hasKey
        override fun canRead(obj: PointObject) = takesObject
        override suspend fun read(obj: PointObject) = answer()
    }

    private fun chain(
        vararg readers: CloudTextReader,
        at: PrivacyLevel = PrivacyLevel.FREE_FIRST,
        network: NetworkAvailability = NetworkAvailability { true },
    ) = DefaultExternalEye(readers.toList(), level(at), network)

    @Test
    fun `первый прочитавший выигрывает`() = runTest {
        val reading = chain(
            eye("mistral-ocr") { "24 строки" },
            eye("следующий") { error("сюда не доходим") },
        ).read(pageObject)

        assertEquals("24 строки", reading.text)
        assertEquals("mistral-ocr", reading.reader)
    }

    @Test
    fun `прочитавший назван — происхождение значения видно человеку`() = runTest {
        val reading = chain(eye("mistral-ocr", learns) { "текст" }).read(pageObject)
        assertEquals("Mistral, Франция (ЕС)", reading.where)

        assertEquals(ReaderPromise.TRAINS.what, reading.promise)
    }

    @Test
    fun `пустое чтение — не победа, очередь идёт дальше`() = runTest {
        val reading = chain(
            eye("пустой") { "" },
            eye("прочитавший") { "текст ведомости" },
        ).read(pageObject)
        assertEquals("прочитавший", reading.reader)
    }

    @Test
    fun `служебная пометка читателя — не текст и не победа, очередь идёт дальше`() = runTest {
        // Дословный ответ OCR.space на кадре без единой надписи (#1054): он ложился
        // текстом объекта, и Point предлагал «Понять» и «Перевести» чужую отписку.
        val reading = chain(
            eye("ocr-space") { "*[No text detected]*" },
            eye("mistral-ocr") { "текст ведомости" },
        ).read(pageObject)
        assertEquals("mistral-ocr", reading.reader)
    }

    @Test
    fun `все посмотрели и текста не увидели — чтение пустое, а не срыв`() = runTest {
        val reading = chain(
            eye("ocr-space") { "*[No text detected]*" },
            eye("mistral-ocr") { "" },
        ).read(pageObject)

        assertTrue("ответ «текста нет» пришёл текстом", reading.text.isBlank())
        assertEquals("назван первый, кто посмотрел", "ocr-space", reading.reader)
    }

    @Test
    fun `все ответили пометкой — чтение пустое, отписка наружу не выходит`() = runTest {
        // Пометки у сервисов разные, а ответ один: посмотрели и текста не увидели (#1054).
        val reading = chain(
            eye("ocr-space") { "*[No text detected]*" },
            eye("ovh-ocr") { "[нет текста]" },
        ).read(pageObject)

        assertTrue("чужая пометка ушла в чтение", reading.text.isBlank())
    }

    @Test
    fun `после пометки сорвавшийся читатель — отказ, а не «не нашлось»`() = runTest {
        // Сорвавшийся мог увидеть то, чего не увидел ответивший пометкой: закрывать вопрос
        // «не нашлось» его срыв не вправе (ADR-0001 §9).
        val failed = runCatching {
            chain(
                eye("ocr-space") { "*[No text detected]*" },
                eye("mistral-ocr") { error("сеть отвалилась") },
            ).read(pageObject)
        }

        assertTrue("срыв растворился в пустом чтении", failed.isFailure)
    }

    @Test
    fun `402 и 429 переводят очередь дальше, а не в кассу`() = runTest {
        val reading = chain(
            eye("исчерпанный") { error("mistral-ocr: лимит (402)") },
            eye("частивший") { error("ovh: слишком часто (429)") },
            eye("свежий") { "текст" },
        ).read(pageObject)
        assertEquals("свежий", reading.reader)
    }

    @Test
    fun `тот, кто учится на присланном, больше не выбрасывается — он просто стоит в очереди`() = runTest {
        val reading = chain(
            eye("обещавший", promises) { error("недоступен") },
            eye("учащийся", learns) { "прочитано" },
        ).read(pageObject)

        assertEquals("учащийся", reading.reader)
    }

    @Test
    fun `строгий уровень — учащийся на присланном не спрашивается вовсе`() = runTest {
        val chain = chain(
            eye("обещавший", promises) { "прочитано тем, кто обещал" },
            eye("учащийся", learns) { error("сюда не доходим") },
            at = PrivacyLevel.NO_TRAINING,
        )

        assertEquals("обещавший", chain.read(pageObject).reader)
    }

    @Test
    fun `только на телефоне — наружу не идёт никто, и человеку сказано почему`() = runTest {
        val chain = chain(
            eye("обещавший", promises) { error("сюда не доходим") },
            at = PrivacyLevel.DEVICE_ONLY,
        )
        assertFalse(chain.available())
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        assertTrue(error?.message!!, error.message!!.contains("настройк"))
        assertFalse(error.message!!, error.message!!.contains("ключ"))
    }

    @Test
    fun `строгий уровень и обещавших нет — совет про настройку, а не про поломку`() = runTest {
        val chain = chain(eye("учащийся", learns) { "x" }, at = PrivacyLevel.NO_TRAINING)
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        assertTrue(error?.message!!, error.message!!.contains("Куда можно отправлять"))
    }

    @Test
    fun `ни одного ключа — вот тогда просьба задать бесплатный ключ`() = runTest {
        val chain = chain(eye("безключевой", hasKey = false) { "x" })
        assertFalse(chain.available())
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        assertTrue(error?.message!!, error.message!!.contains("не настроено"))
        assertTrue(error.message!!, error.message!!.contains("бесплатный ключ"))
    }

    @Test
    fun `ключ есть, а входа такого сорта никто не берёт — это не «задайте ключ»`() = runTest {
        val chain = chain(eye("только кадры", takesObject = false) { "x" })
        assertTrue(chain.available())
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        assertTrue(error?.message!!, error.message!!.contains("не берётся за этот объект"))
        assertFalse(error.message!!, error.message!!.contains("задайте"))
    }

    @Test
    fun `все уперлись в лимит — отказ говорит про лимит и не выдаёт нашу кухню`() = runTest {
        val chain = chain(
            eye("a") { error("mistral-ocr: лимит (402)") },
            eye("b") { error("ovh: слишком часто (429)") },
        )
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        assertTrue(error?.message!!, error.message!!.contains("бесплатное чтение закончилось"))
        assertFalse(error.message!!, error.message!!.contains("купить"))
    }

    @Test
    fun `сильнейший читатель выпал без ключа — об этом сказано вместе с отказом`() = runTest {
        val chain = chain(
            eye("сильный", hasKey = false) { error("сюда не доходим") },
            eye("безключевой") { error(com.point.core.flow.serviceRefusal(429)) },
        )
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        assertTrue(error?.message!!, error.message!!.contains("бесплатный ключ Mistral"))
    }

    @Test
    fun `все ключи на месте — лишнего совета нет`() = runTest {
        val chain = chain(eye("a") { error(com.point.core.flow.serviceRefusal(429)) })
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        assertFalse(error?.message!!, error.message!!.contains("ключ"))
    }

    @Test
    fun `совет про ключ не даётся там, где ключ всё равно не поможет`() = runTest {
        val chain = chain(
            eye("учащийся без ключа", learns, hasKey = false) { "x" },
            eye("обещавший") { error(com.point.core.flow.serviceRefusal(429)) },
            at = PrivacyLevel.NO_TRAINING,
        )
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        assertFalse(error?.message!!, error.message!!.contains("ключ"))
    }

    /**
     * Приписка про ключ уводила не туда (#1260): интернета нет, а человеку советовали
     * идти в настройки и заводить ключ Mistral. Он тратил ход на действие, которое ничего
     * не изменит. Приписка звучит там, где ключ и решает.
     */
    @Test
    fun `связь оборвалась в пути — про ключи ни слова`() = runTest {
        val chain = chain(
            eye("сильный", hasKey = false) { error("сюда не доходим") },
            eye("свободный") { error("Unable to resolve host \"api.ocr.space\"") },
        )

        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        assertEquals(com.point.core.flow.NO_NETWORK_TEXT, error?.message)
        assertFalse("совет завести ключ там, где выключен интернет", error?.message!!.contains("ключ Mistral"))
    }

    @Test
    fun `все отказали — честный отказ, а не пустой текст`() = runTest {
        val chain = chain(eye("a") { "" }, eye("b") { error("страница сегодня не по зубам") })
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        assertTrue(error?.message!!, error.message!!.contains("Не удалось прочитать"))
        assertTrue(error.message!!, error.message!!.contains("прочитана пустой"))
    }

    /** Идентификатор читалки человеку не адресован (#1259) — он остаётся в metadata. */
    @Test
    fun `в отказе нет внутренних имён читалок`() = runTest {
        val chain = chain(eye("ocr-space") { "" }, eye("ovh-qwen-vl") { "" }, eye("mistral-ocr") { error("сорвалось") })
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        val said = error?.message.orEmpty()
        assertFalse(said, said.contains("ocr-space"))
        assertFalse(said, said.contains("ovh-qwen-vl"))
        assertFalse(said, said.contains("mistral-ocr"))
    }

    @Test
    fun `уровень спрашивается на каждом чтении, а не запоминается`() = runTest {
        var current = PrivacyLevel.FREE_FIRST
        val settings = object : CloudPrivacySettings {
            override fun level() = current
            override suspend fun setLevel(level: PrivacyLevel) { current = level }
        }
        val chain = DefaultExternalEye(listOf(eye("учащийся", learns) { "прочитано" }), settings, NetworkAvailability { true })

        assertTrue(chain.available())
        current = PrivacyLevel.DEVICE_ONLY

        assertFalse(chain.available())
    }

    @Test
    fun `на телефоне нет сети — ни один читатель не получает запрос`() = runTest {
        // Решение владельца (#690, #691), тот же гейт, что и в FallbackLlmClient:
        // офлайн вся очередь читателей одинаково молчит — ждать каждого по очереди
        // незачем, если телефон уже знает, что сети нет.
        val calls = mutableListOf<String>()

        val error = runCatching {
            chain(
                eye("primary") { calls += "primary"; "текст" },
                network = NetworkAvailability { false },
            ).read(pageObject)
        }.exceptionOrNull()

        assertEquals(com.point.core.flow.NO_NETWORK_TEXT, error?.message)
        assertTrue("офлайн ни один читатель не должен получить запрос", calls.isEmpty())
    }
}
