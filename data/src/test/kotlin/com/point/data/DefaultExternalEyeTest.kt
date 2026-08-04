package com.point.data

import com.point.core.flow.CloudPrivacySettings
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.ReaderPrivacy
import com.point.core.model.PointObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Цепочка внешних глаз (#280): порядок — по замеру, приватность — по выбору человека.
 *
 * Прежняя постановка выбрасывала из цепочки всех, кто хранит присланное у себя. Владелец её снял:
 * «по моделям добавляй и не приватные, просто ранжируем их». Тесты стерегут обе половины — что
 * очередь идёт по эффективности и что уровень человека её сужает, а не переставляет.
 */
class DefaultExternalEyeTest {

    private val europe = ReaderPrivacy("Европа", europe = true, logsRequests = false)
    private val overseas = ReaderPrivacy("США", europe = false, logsRequests = true)

    private fun level(level: PrivacyLevel) = object : CloudPrivacySettings {
        override fun level() = level
        override suspend fun setLevel(level: PrivacyLevel) = Unit
    }

    private fun eye(
        name: String,
        privacy: ReaderPrivacy = europe,
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

    private fun chain(vararg readers: CloudTextReader, at: PrivacyLevel = PrivacyLevel.FREE_FIRST) =
        DefaultExternalEye(readers.toList(), level(at))

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
        val reading = chain(eye("mistral-ocr", europe) { "текст" }).read(pageObject)
        assertEquals("Европа", reading.where)
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
    fun `402 и 429 переводят очередь дальше, а не в кассу`() = runTest {
        val reading = chain(
            eye("исчерпанный") { error("mistral-ocr: лимит (402)") },
            eye("частивший") { error("ovh: слишком часто (429)") },
            eye("свежий") { "текст" },
        ).read(pageObject)
        assertEquals("свежий", reading.reader)
    }

    @Test
    fun `тот, кто хранит присланное, больше не выбрасывается — он просто стоит в очереди`() = runTest {
        val reading = chain(
            eye("европейский", europe) { error("недоступен") },
            eye("заморский", overseas) { "прочитано" },
        ).read(pageObject)

        // Решение владельца: приватность — столбец и настройка, а не причина исключения.
        assertEquals("заморский", reading.reader)
    }

    @Test
    fun `только Европа — заморский не спрашивается вовсе`() = runTest {
        val chain = chain(
            eye("европейский", europe) { "прочитано в Европе" },
            eye("заморский", overseas) { error("сюда не доходим") },
            at = PrivacyLevel.EUROPE_ONLY,
        )
        assertEquals("европейский", chain.read(pageObject).reader)
    }

    @Test
    fun `только на телефоне — наружу не идёт никто, и человеку сказано почему`() = runTest {
        val chain = chain(
            eye("европейский", europe) { error("сюда не доходим") },
            at = PrivacyLevel.DEVICE_ONLY,
        )
        assertFalse(chain.available())
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        // Совет «задайте ключ» здесь был бы непониманием: человеку поможет переключатель.
        assertTrue(error?.message!!, error.message!!.contains("Только на телефоне"))
        assertFalse(error.message!!, error.message!!.contains("ключ"))
    }

    @Test
    fun `только Европа и европейских нет — совет про настройку, а не про поломку`() = runTest {
        val chain = chain(eye("заморский", overseas) { "x" }, at = PrivacyLevel.EUROPE_ONLY)
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
    fun `все уперлись в лимит — отказ говорит про лимит и не предлагает платить`() = runTest {
        val chain = chain(
            eye("a") { error("mistral-ocr: лимит (402)") },
            eye("b") { error("ovh: слишком часто (429)") },
        )
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        assertTrue(error?.message!!, error.message!!.contains("Бесплатные лимиты"))
        assertFalse(error.message!!, error.message!!.contains("купить"))
    }

    @Test
    fun `сильнейший читатель выпал без ключа — об этом сказано вместе с отказом`() = runTest {
        val chain = chain(
            eye("сильный", hasKey = false) { error("сюда не доходим") },
            eye("безключевой") { error("ovh HTTP 500") },
        )
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        // Читатель без ключа работает всегда, поэтому «задайте ключ» отдельной веткой не всплывёт
        // никогда — а человек так и останется с худшим чтением, не узнав, что есть лучшее.
        assertTrue(error?.message!!, error.message!!.contains("бесплатный ключ Mistral"))
    }

    @Test
    fun `все ключи на месте — лишнего совета нет`() = runTest {
        val chain = chain(eye("a") { error("ovh HTTP 500") })
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        assertFalse(error?.message!!, error.message!!.contains("ключ"))
    }

    @Test
    fun `совет про ключ не даётся там, где ключ всё равно не поможет`() = runTest {
        val chain = chain(
            eye("заморский без ключа", overseas, hasKey = false) { "x" },
            eye("европейский") { error("mistral HTTP 500") },
            at = PrivacyLevel.EUROPE_ONLY,
        )
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        // На уровне «только Европа» заморский читатель не включится ни с каким ключом.
        assertFalse(error?.message!!, error.message!!.contains("ключ"))
    }

    @Test
    fun `все отказали — честный отказ, а не пустой текст`() = runTest {
        val chain = chain(eye("a") { "" }, eye("b") { error("ovh HTTP 500") })
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        // Пустой текст означал бы «страница пустая» — тихая ложь того же сорта, что зацикливание.
        assertTrue(error?.message!!, error.message!!.contains("не удалось"))
        assertTrue(error.message!!, error.message!!.contains("прочитана пустой"))
    }

    @Test
    fun `уровень спрашивается на каждом чтении, а не запоминается`() = runTest {
        var current = PrivacyLevel.FREE_FIRST
        val settings = object : CloudPrivacySettings {
            override fun level() = current
            override suspend fun setLevel(level: PrivacyLevel) { current = level }
        }
        val chain = DefaultExternalEye(listOf(eye("заморский", overseas) { "прочитано" }), settings)

        assertTrue(chain.available())
        current = PrivacyLevel.DEVICE_ONLY
        // Закэшированное разрешение отправило бы кадр туда, куда человек уже запретил.
        assertFalse(chain.available())
    }
}
