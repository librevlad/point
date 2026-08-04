package com.point.data

import com.point.core.flow.CloudPrivacySettings
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.ReaderPrivacy
import com.point.core.flow.ReaderPromise
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

    /** Обещал не учиться на присланном — и потому проходит на строгий уровень. */
    private val promises = ReaderPrivacy("OVH, Франция (ЕС)", ReaderPromise.NO_TRAINING)

    /** Учится на присланном — на строгом уровне молчит, каким бы хорошим ни был. */
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
        val reading = chain(eye("mistral-ocr", learns) { "текст" }).read(pageObject)
        assertEquals("Mistral, Франция (ЕС)", reading.where)
        // «Куда» без «что там с ним делают» — половина правды, и именно эта половина однажды дала
        // уровню «Только Европа» вид защиты при её отсутствии.
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

        // Решение владельца: приватность — столбец и настройка, а не причина исключения.
        assertEquals("учащийся", reading.reader)
    }

    @Test
    fun `строгий уровень — учащийся на присланном не спрашивается вовсе`() = runTest {
        val chain = chain(
            eye("обещавший", promises) { "прочитано тем, кто обещал" },
            eye("учащийся", learns) { error("сюда не доходим") },
            at = PrivacyLevel.NO_TRAINING,
        )
        // Прежний уровень «Только Европа» на этих двоих выбирал бы ровно наоборот: оба во Франции,
        // и вперёд шёл бы тот, кто учится. Настройка судит по обещанию, а не по стране.
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

        // Совет «задайте ключ» здесь был бы непониманием: человеку поможет переключатель.
        assertTrue(error?.message!!, error.message!!.contains("Только на телефоне"))
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
            eye("учащийся без ключа", learns, hasKey = false) { "x" },
            eye("обещавший") { error("ovh HTTP 500") },
            at = PrivacyLevel.NO_TRAINING,
        )
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        // На строгом уровне учащийся на присланном не включится ни с каким ключом.
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
        val chain = DefaultExternalEye(listOf(eye("учащийся", learns) { "прочитано" }), settings)

        assertTrue(chain.available())
        current = PrivacyLevel.DEVICE_ONLY
        // Закэшированное разрешение отправило бы кадр туда, куда человек уже запретил.
        assertFalse(chain.available())
    }
}
