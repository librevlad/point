package com.point.core.flow

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ввёл на телефоне — компьютер знает (#610, решение владельца 10.08.2026).
 *
 * Проверяется весь путь, а не его половина: телефон запечатывает и кладёт на сервер,
 * компьютер забирает, вскрывает своим ключом и получает то же самое. Сервер здесь —
 * почтовый ящик, который хранит нечитаемое.
 */
class SettingsFollowThePersonTest {

    private val phoneKeys = DeviceKeys.generate()

    private val pcKeys = DeviceKeys.generate()

    private val phone = PointAccount("phone", "пропуск-телефона", "человек@example.com", "Пиксель", DeviceKind.PHONE)

    private val pc = PointAccount("pc", "пропуск-компьютера", "человек@example.com", "Рабочий ПК", DeviceKind.PC)

    /** Сервер: круг устройств и одна полка под запечатанное. Содержимое ему недоступно. */
    private class Server(private val keys: Map<String, String>) : AccountClient {
        var shelf: SealedSettings? = null
        var writes = 0
        var reachable = true

        override suspend fun start(deviceName: String, kind: DeviceKind) = null
        override suspend fun poll(loginId: String, claimToken: String) = LoginPoll.Silent
        override suspend fun enroll(account: PointAccount, publicKey: String) = true
        override suspend fun revoke(account: PointAccount, deviceId: String) = true
        override suspend fun deleteAccount(account: PointAccount) = true

        override suspend fun circle(account: PointAccount): CircleAnswer =
            if (!reachable) {
                CircleAnswer.Unreachable
            } else {
                CircleAnswer.Circle(
                    keys.map { (id, key) -> CircleDevice(id, DeviceKind.PHONE, id, key = key) },
                )
            }

        override suspend fun settings(account: PointAccount) = shelf

        override suspend fun saveSettings(account: PointAccount, sealed: SealedSettings): Boolean {
            if (!reachable) return false
            val lying = shelf
            if (lying != null && sealed.at < lying.at) return false
            shelf = sealed
            writes++
            return true
        }
    }

    private fun server() = Server(mapOf("phone" to phoneKeys.publicKey, "pc" to pcKeys.publicKey))

    private fun sync(server: Server, at: Long) = AccountSettingsSync(server, SettingsSeal()) { at }

    private val theirKey = "sk-ключ-человека"

    private val fromPc = "gsk-с-компьютера"

    private val enteredOnPhone = AccountSettings(
        aiKeys = UserAiKeys.NONE.with(UserAiKey("openrouter", theirKey)),
        privacy = PrivacyLevel.NO_TRAINING,
        at = 1_000L,
    )

    @Test
    fun `ключ, введённый на телефоне, компьютер узнаёт сам`() = runTest {
        val server = server()
        sync(server, at = 2_000).sync(phone, phoneKeys, enteredOnPhone)

        val onPc = sync(server, at = 3_000).sync(pc, pcKeys, AccountSettings())

        assertEquals(theirKey, onPc?.aiKeys?.keyFor("openrouter"))
        assertEquals(PrivacyLevel.NO_TRAINING, onPc?.privacy)
    }

    @Test
    fun `компьютер, которому нечего добавить, ничего и не пишет`() = runTest {
        val server = server()
        sync(server, at = 2_000).sync(phone, phoneKeys, enteredOnPhone)
        val afterPhone = server.writes

        sync(server, at = 3_000).sync(pc, pcKeys, AccountSettings())

        assertEquals("компьютер переписал общее, ничего не изменив", afterPhone, server.writes)
    }

    @Test
    fun `своё с чужим складывается, а не вытесняет`() = runTest {
        val server = server()
        sync(server, at = 2_000).sync(phone, phoneKeys, enteredOnPhone)

        val onPc = sync(server, at = 3_000).sync(
            pc,
            pcKeys,
            AccountSettings(aiKeys = UserAiKeys.NONE.with(UserAiKey("groq", fromPc)), at = 2_500),
        )

        assertEquals(fromPc, onPc?.aiKeys?.keyFor("groq"))
        assertEquals(theirKey, onPc?.aiKeys?.keyFor("openrouter"))
    }

    @Test
    fun `итог с компьютера доезжает обратно до телефона`() = runTest {
        val server = server()
        sync(server, at = 2_000).sync(phone, phoneKeys, enteredOnPhone)
        sync(server, at = 3_000).sync(
            pc,
            pcKeys,
            AccountSettings(aiKeys = UserAiKeys.NONE.with(UserAiKey("groq", fromPc)), at = 2_500),
        )

        val backOnPhone = sync(server, at = 4_000).sync(phone, phoneKeys, enteredOnPhone)

        assertEquals(fromPc, backOnPhone?.aiKeys?.keyFor("groq"))
    }

    /** На полке сервера лежит нечитаемое — этим весь обмен и держится. */
    @Test
    fun `на сервере ключа человека не видно`() = runTest {
        val server = server()

        sync(server, at = 2_000).sync(phone, phoneKeys, enteredOnPhone)

        assertTrue("ключ уехал открытым текстом", theirKey !in server.shelf!!.encode())
    }

    @Test
    fun `сети нет — своё остаётся при себе`() = runTest {
        val server = server().apply { reachable = false }

        val outcome = sync(server, at = 2_000).sync(phone, phoneKeys, enteredOnPhone)

        assertNull("несостоявшийся обмен выдан за состоявшийся", outcome)
        assertNull(server.shelf)
    }

    /**
     * Дефект, найденный при разборе своей же работы: устройство, ещё не объявившее серверу
     * публичную часть, вскрыть общее не может — и записало бы своё поверх чужого, стерев
     * ключи, введённые на других устройствах, молча и безвозвратно.
     */
    @Test
    fun `нечем вскрыть общее — своё поверх чужого не пишется`() = runTest {
        val server = server()
        sync(server, at = 2_000).sync(phone, phoneKeys, enteredOnPhone)
        val wasOnServer = server.shelf
        val newcomer = DeviceKeys.generate()

        val outcome = sync(server, at = 5_000).sync(
            pc,
            newcomer,
            AccountSettings(aiKeys = UserAiKeys.NONE.with(UserAiKey("groq", fromPc)), at = 4_000),
        )

        assertNull("чужие настройки затёрты вслепую", outcome)
        assertEquals(wasOnServer, server.shelf)
    }

    @Test
    fun `пока устройство не объявило свой ключ, настройки не едут`() = runTest {
        val server = Server(mapOf("phone" to phoneKeys.publicKey, "pc" to ""))

        val outcome = sync(server, at = 2_000).sync(pc, pcKeys, enteredOnPhone)

        assertNull("устройство записало общее, которого само не прочитает", outcome)
        assertNull(server.shelf)
    }

    @Test
    fun `устройства вне круга общего не читают`() = runTest {
        val server = server()
        sync(server, at = 2_000).sync(phone, phoneKeys, enteredOnPhone)
        val stranger = DeviceKeys.generate()

        val opened = SettingsSeal().open(server.shelf!!, "pc", stranger.privateKey)

        assertNull(opened)
    }
}
