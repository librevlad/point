package com.point.core.flow

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ввёл на одном устройстве — знают все устройства аккаунта (#610), и для этого не нужен круг
 * (владелец 26.11.2026: «оно должно зависеть только от аккаунта, не от круга»).
 *
 * Проверяется весь путь, а не его половина: одно устройство кладёт на сервер, другое
 * забирает и получает то же самое — в том числе вошедшее позже, без участия первого.
 */
class SettingsFollowThePersonTest {

    private val phone = PointAccount("phone", "пропуск-телефона", "человек@example.com", "Пиксель", DeviceKind.PHONE)

    private val pc = PointAccount("pc", "пропуск-компьютера", "человек@example.com", "Рабочий ПК", DeviceKind.PC)

    /** Сервер: одна полка под настройки аккаунта. Старое поверх нового не кладёт. */
    private class Server : AccountClient {
        var shelf: AccountSettings? = null
        var writes = 0
        var reachable = true
        var circleAsked = 0

        override suspend fun start(deviceName: String, kind: DeviceKind) = null
        override suspend fun poll(loginId: String, claimToken: String) = LoginPoll.Silent
        override suspend fun enroll(account: PointAccount, publicKey: String) = true
        override suspend fun revoke(account: PointAccount, deviceId: String) = true
        override suspend fun deleteAccount(account: PointAccount) = true

        override suspend fun circle(account: PointAccount): CircleAnswer {
            circleAsked++
            return CircleAnswer.Unreachable
        }

        override suspend fun settings(account: PointAccount): AccountSettings? =
            if (reachable) shelf ?: AccountSettings() else null

        override suspend fun saveSettings(account: PointAccount, settings: AccountSettings): Boolean {
            if (!reachable) return false
            val lying = shelf
            if (lying != null && settings.at < lying.at) return false
            shelf = settings
            writes++
            return true
        }
    }

    private fun sync(server: Server, at: Long) = AccountSettingsSync(server) { at }

    private val theirKey = "sk-ключ-человека"

    private val fromPc = "gsk-с-компьютера"

    private val enteredOnPhone = AccountSettings(
        aiKeys = UserAiKeys.NONE.with(UserAiKey("openrouter", theirKey)),
        privacy = PrivacyLevel.NO_TRAINING,
        at = 1_000L,
    )

    private val enteredOnPc = AccountSettings(
        aiKeys = UserAiKeys.NONE
            .with(UserAiKey("groq", fromPc))
            .with(UserAiKey("gemini", "AIza-с-компьютера"))
            .with(UserAiKey("nvidia", "nvapi-с-компьютера")),
        speechKey = fromPc,
        at = 2_500L,
    )

    @Test
    fun `ключ, введённый на телефоне, компьютер узнаёт сам`() = runTest {
        val server = Server()
        sync(server, at = 2_000).sync(phone, enteredOnPhone)

        val onPc = sync(server, at = 3_000).sync(pc, AccountSettings())

        assertEquals(theirKey, onPc?.aiKeys?.keyFor("openrouter"))
        assertEquals(PrivacyLevel.NO_TRAINING, onPc?.privacy)
    }

    /**
     * Ровно тот случай, что сломался 26.11.2026: компьютер всё настроил, телефон вошёл в
     * аккаунт позже — и видел один ключ из двенадцати, пока компьютер не перепечатает
     * конверт под него. Теперь телефону хватает своего пропуска.
     */
    @Test
    fun `устройство, вошедшее позже, получает все ключи без участия остальных`() = runTest {
        val server = Server()
        sync(server, at = 3_000).sync(pc, enteredOnPc)

        val onPhone = sync(server, at = 9_000).sync(phone, AccountSettings())

        assertEquals(3, onPhone?.aiKeys?.entries?.size)
        assertEquals(fromPc, onPhone?.speechKey)
        assertEquals("о круге устройств спрашивать незачем", 0, server.circleAsked)
    }

    @Test
    fun `устройство, которому нечего добавить, ничего и не пишет`() = runTest {
        val server = Server()
        sync(server, at = 2_000).sync(phone, enteredOnPhone)
        val afterPhone = server.writes

        sync(server, at = 3_000).sync(pc, AccountSettings())

        assertEquals("компьютер переписал общее, ничего не изменив", afterPhone, server.writes)
    }

    @Test
    fun `своё с чужим складывается, а не вытесняет`() = runTest {
        val server = Server()
        sync(server, at = 2_000).sync(phone, enteredOnPhone)

        val onPc = sync(server, at = 3_000).sync(pc, enteredOnPc)

        assertEquals(fromPc, onPc?.aiKeys?.keyFor("groq"))
        assertEquals(theirKey, onPc?.aiKeys?.keyFor("openrouter"))
    }

    @Test
    fun `итог с компьютера доезжает обратно до телефона`() = runTest {
        val server = Server()
        sync(server, at = 2_000).sync(phone, enteredOnPhone)
        sync(server, at = 3_000).sync(pc, enteredOnPc)

        val backOnPhone = sync(server, at = 4_000).sync(phone, enteredOnPhone)

        assertEquals(fromPc, backOnPhone?.aiKeys?.keyFor("groq"))
    }

    @Test
    fun `сети нет — своё остаётся при себе`() = runTest {
        val server = Server().apply { reachable = false }

        val outcome = sync(server, at = 2_000).sync(phone, enteredOnPhone)

        assertNull("несостоявшийся обмен выдан за состоявшийся", outcome)
        assertNull(server.shelf)
    }

    /** «Не спросили» не то же, что «пусто»: иначе своё легло бы поверх чужого вслепую. */
    @Test
    fun `сервер не ответил — своё поверх общего не пишется`() = runTest {
        val server = Server()
        sync(server, at = 3_000).sync(pc, enteredOnPc)
        val wasOnServer = server.shelf
        server.reachable = false

        val outcome = sync(server, at = 9_000).sync(phone, enteredOnPhone)

        assertNull(outcome)
        assertEquals(wasOnServer, server.shelf)
    }

    @Test
    fun `сервер успел положить новее — отказ не выдаётся за сохранённое`() = runTest {
        val server = Server()
        server.shelf = AccountSettings(sound = true, at = 50_000)
        val racing = object : AccountClient by server {
            // Пока это устройство сводило, другое положило ещё новее.
            override suspend fun settings(account: PointAccount) = AccountSettings(sound = true, at = 1_000)
        }

        val outcome = AccountSettingsSync(racing) { 2_000 }.sync(phone, enteredOnPhone)

        assertNull("отказ сервера выдан за сохранённое", outcome)
    }
}
