package com.point.desktop

import com.point.core.flow.AccountSettings
import com.point.core.flow.UserAiKey
import com.point.core.flow.UserAiKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Настройки компьютера едут за человеком в общем виде (#610).
 *
 * Ключ, введённый на телефоне, компьютер узнаёт сам — и наоборот. Своё у компьютера при этом
 * остаётся здесь: имя устройства и правый клик не предпочтение человека, а свойство места.
 */
class PcSettingsTravelTest {

    @get:Rule val temp = TemporaryFolder()

    private fun config() = FilePcConfig(temp.newFolder("point-home"))

    private val openRouter = com.point.core.flow.AI_PROVIDERS.first { it.id == "openrouter" }

    private val ownUrl = "https://свой.сервис/v1"

    private val fromPhone = "sk-с-телефона"

    private val myKey = "sk-моё"

    private val speech = "речь"

    @Test
    fun `ключ компьютера уезжает под именем своего сервиса`() {
        val store = config()
        val was = store.load()
        store.save(was.copy(ai = was.ai.copy(key = "sk-1", url = openRouter.baseUrl)))

        val mine = store.accountSettings()

        assertEquals("sk-1", mine.aiKeys.keyFor("openrouter"))
    }

    @Test
    fun `свой адрес сервиса не выдаётся за чужой`() {
        val store = config()
        val was = store.load()
        store.save(was.copy(ai = was.ai.copy(key = "sk-1", url = ownUrl)))

        val key = store.accountSettings().aiKeys.mine.single()

        assertEquals(com.point.core.flow.OWN_SERVICE_ID, key.providerId)
        assertEquals(ownUrl, key.baseUrl)
    }

    @Test
    fun `приехавший с телефона ключ ложится вместе с адресом и моделью`() {
        val store = config()

        store.applyAccountSettings(
            AccountSettings(
                aiKeys = UserAiKeys.NONE.with(UserAiKey("openrouter", fromPhone)),
                at = 1_000,
            ),
        )

        val now = store.load()
        assertEquals(fromPhone, now.ai.key)
        assertEquals(openRouter.baseUrl, now.ai.url)
        assertTrue("модель не подставилась: " + now.ai.model, now.ai.model.isNotBlank())
    }

    @Test
    fun `звук едет вместе с ключами`() {
        val store = config()

        store.applyAccountSettings(AccountSettings(sound = false, at = 1_000))

        assertTrue("выбор человека про звук не доехал", !store.load().sound)
    }

    @Test
    fun `пустое приехавшее ничего не стирает`() {
        val store = config()
        val was = store.load()
        store.save(was.copy(ai = was.ai.copy(key = myKey), speech = was.speech.copy(key = speech)))

        store.applyAccountSettings(AccountSettings(at = 5_000))

        assertEquals(myKey, store.load().ai.key)
        assertEquals(speech, store.load().speech.key)
    }

    @Test
    fun `имя устройства и правый клик за человеком не едут`() {
        val store = config()
        val was = store.load()
        store.save(was.copy(name = "Рабочий ноутбук", rightClick = false, ai = was.ai.copy(key = "sk-1")))

        val wire = store.accountSettings().encode()

        assertTrue("имя устройства уехало в общие настройки", "Рабочий ноутбук" !in wire)
        assertTrue("правый клик уехал в общие настройки", "right.click" !in wire)
    }

    /** Отметка «когда» переживает перезапуск: без неё компьютер каждый раз спорил бы заново. */
    @Test
    fun `отметка приехавших настроек помнится между запусками`() {
        val home = temp.newFolder("point-home-stamp")
        FilePcConfig(home).applyAccountSettings(AccountSettings(sound = true, at = 7_000))

        assertEquals(7_000L, FilePcConfig(home).accountSettings().at)
    }
}
