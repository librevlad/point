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

    /**
     * Выбор человека здесь — такое же событие, как приехавшее с сервера (#1085).
     *
     * Метку времени двигало только приехавшее снаружи, и местный выбор никогда не оказывался
     * новее: «Только на этом устройстве», выбранное на компьютере, не уезжало ни на сервер,
     * ни на телефон, а следующая сверка молча возвращала прежний уровень.
     */
    @Test
    fun `выбранный здесь уровень приватности оказывается новее приехавшего`() {
        val store = config()
        val was = store.load()
        store.applyAccountSettings(AccountSettings(privacy = com.point.core.flow.PrivacyLevel.FREE_FIRST, at = 1_000))

        store.save(store.load().copy(privacy = com.point.core.flow.PrivacyLevel.DEVICE_ONLY))
        val mine = store.accountSettings()

        assertTrue("местный выбор остался старее приехавшего", mine.at > 1_000)
        assertEquals(
            "уровень не уехал бы: приехавшее перекрыло бы выбранное человеком",
            com.point.core.flow.PrivacyLevel.DEVICE_ONLY,
            mine.mergedWith(AccountSettings(privacy = com.point.core.flow.PrivacyLevel.FREE_FIRST, at = 1_000)).privacy,
        )
        assertEquals(was.name, store.load().name)
    }

    /** Имя компьютера — свойство места, а не предпочтение: чужой выбор перекрывать ему нечем. */
    @Test
    fun `имя компьютера метку времени не двигает`() {
        val store = config()
        store.applyAccountSettings(AccountSettings(privacy = com.point.core.flow.PrivacyLevel.FREE_FIRST, at = 5_000))

        store.save(store.load().copy(name = "Рабочий"))

        assertEquals(5_000L, store.accountSettings().at)
    }

    @Test
    fun `ключ компьютера уезжает под именем своего сервиса`() {
        val store = config()
        val was = store.load()
        store.save(was.copy(aiKeys = was.aiKeys.with(UserAiKey("openrouter", "sk-1"))))

        val mine = store.accountSettings()

        assertEquals("sk-1", mine.aiKeys.keyFor("openrouter"))
    }

    @Test
    fun `свой адрес сервиса не выдаётся за чужой`() {
        val store = config()
        val was = store.load()
        store.save(
            was.copy(
                aiKeys = was.aiKeys.with(
                    UserAiKey(com.point.core.flow.OWN_SERVICE_ID, "sk-1", baseUrl = ownUrl),
                ),
            ),
        )

        val key = store.accountSettings().aiKeys.mine.single()

        assertEquals(com.point.core.flow.OWN_SERVICE_ID, key.providerId)
        assertEquals(ownUrl, key.baseUrl)
    }

    @Test
    fun `приехавший с телефона ключ ложится своему сервису`() {
        val store = config()

        store.applyAccountSettings(
            AccountSettings(
                aiKeys = UserAiKeys.NONE.with(UserAiKey("openrouter", fromPhone)),
                at = 1_000,
            ),
        )

        assertEquals(fromPhone, store.load().aiKeys.keyFor("openrouter"))
    }

    @Test
    fun `с телефона приезжает вся связка, а не самый свежий ключ`() {
        val store = config()

        store.applyAccountSettings(
            AccountSettings(
                aiKeys = UserAiKeys.NONE
                    .with(UserAiKey("openrouter", "sk-router", savedAt = 100))
                    .with(UserAiKey("groq", "sk-groq", savedAt = 200))
                    .with(UserAiKey("mistral", "sk-mistral", savedAt = 300)),
                at = 1_000,
            ),
        )

        val keys = store.load().aiKeys
        assertEquals(3, keys.mine.size)
        assertEquals("sk-router", keys.keyFor("openrouter"))
        assertEquals("sk-groq", keys.keyFor("groq"))
        assertEquals("sk-mistral", keys.keyFor("mistral"))
    }

    @Test
    fun `единственный старый ключ не теряется при обновлении`() {
        val old = "sk-" + "старый"
        val dir = java.io.File(temp.root, "point-pc").apply { mkdirs() }
        java.io.File(dir, "config").writeText(
            listOf("name=User-PC", "ai.key=" + old, "ai.url=" + openRouter.baseUrl)
                .joinToString("\n", postfix = "\n"),
        )

        val keys = FilePcConfig(dir).load().aiKeys

        assertEquals(1, keys.mine.size)
        assertEquals(old, keys.keyFor("openrouter"))
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
        store.save(
            was.copy(
                aiKeys = was.aiKeys.with(UserAiKey("openrouter", myKey)),
                speech = was.speech.copy(key = speech),
            ),
        )

        store.applyAccountSettings(AccountSettings(at = 5_000))

        assertEquals(myKey, store.load().aiKeys.keyFor("openrouter"))
        assertEquals(speech, store.load().speech.key)
    }

    @Test
    fun `имя устройства и правый клик за человеком не едут`() {
        val store = config()
        val was = store.load()
        store.save(
            was.copy(
                name = "Рабочий ноутбук",
                rightClick = false,
                aiKeys = was.aiKeys.with(UserAiKey("openrouter", "sk-1")),
            ),
        )

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
