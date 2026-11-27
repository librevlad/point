package com.point.desktop

import com.point.core.flow.AccountClient
import com.point.core.flow.AccountStore
import com.point.core.flow.AiTrialOffer
import com.point.core.flow.BrowserOpener
import com.point.core.flow.CircleAnswer
import com.point.core.flow.DeviceKeyPair
import com.point.core.flow.DeviceKeyStore
import com.point.core.flow.DeviceKind
import com.point.core.flow.LoginPoll
import com.point.core.flow.LoginStart
import com.point.core.flow.MemoryPointAiQuota
import com.point.core.flow.PointAccount
import com.point.core.flow.PointAiTrial
import com.point.core.flow.usable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проба AI без своего ключа на компьютере (#477).
 *
 * Проба одна на все устройства человека, но компьютер о ней не знал: без своего ключа
 * AI-действия на нём молчали, а экран ключей не говорил, что вход даёт их даром. Здесь
 * проверяется, что аккаунт компьютера узнаёт о пробе у сервера и что исполнитель пробы
 * получает ту же память, по которой решает, браться ли за действие.
 */
class AiTrialOnPcTest {

    private val signedIn = PointAccount(
        deviceId = "pc-1",
        deviceToken = "pass",
        email = "",
        deviceName = "Компьютер",
        kind = DeviceKind.PC,
    )

    private class Server(
        var quota: PointAiTrial? = PointAiTrial(available = true, left = 7, trial = 30),
        var offer: PointAiTrial? = PointAiTrial(available = true, left = 30, trial = 30),
    ) : AccountClient {
        var offerAsked = 0
        override suspend fun start(deviceName: String, kind: DeviceKind): LoginStart? = null
        override suspend fun poll(loginId: String, claimToken: String): LoginPoll = LoginPoll.Silent
        override suspend fun enroll(account: PointAccount, publicKey: String) = false
        override suspend fun circle(account: PointAccount) = CircleAnswer.Unreachable
        override suspend fun revoke(account: PointAccount, deviceId: String) = true
        override suspend fun deleteAccount(account: PointAccount) = false
        override suspend fun aiTrial(account: PointAccount): PointAiTrial? = quota
        override suspend fun aiOffer(): PointAiTrial? = offer.also { offerAsked++ }
    }

    private val quota = MemoryPointAiQuota()

    private fun account(stored: PointAccount?, server: Server) = DesktopAccount(
        scope = CoroutineScope(Dispatchers.Unconfined),
        store = object : AccountStore {
            private var held: PointAccount? = stored
            override fun current(): PointAccount? = held
            override suspend fun save(account: PointAccount) { held = account }
            override suspend fun clear() { held = null }
        },
        client = server,
        browser = BrowserOpener { },
        deviceName = "Компьютер",
        keys = object : DeviceKeyStore {
            override fun keys() = DeviceKeyPair(privateKey = "", publicKey = "")
        },
        aiQuota = quota,
    )

    @Test
    fun `кто не вошёл, узнаёт, что вход даёт пробу`() {
        val server = Server()
        val pc = account(stored = null, server = server)

        pc.refreshAiTrial()
        pc.refreshAiTrial()

        assertEquals(AiTrialOffer(signedIn = false, left = 30, trial = 30), pc.aiTrial.value)
        assertEquals("предложение спрашивается у сервера один раз", 1, server.offerAsked)
    }

    @Test
    fun `вошедший видит остаток, и исполнитель пробы берётся за действие`() {
        val pc = account(stored = signedIn, server = Server())

        pc.refreshCircle()

        assertEquals(AiTrialOffer(signedIn = true, left = 7, trial = 30), pc.aiTrial.value)
        assertTrue("память пробы пуста — исполнитель пробы не возьмётся за действие", quota.usable(signedIn = true))
    }

    @Test
    fun `после выхода остатка нет, есть предложение войти`() {
        val pc = account(stored = signedIn, server = Server())
        pc.refreshCircle()

        pc.signOut()

        assertEquals(AiTrialOffer(signedIn = false, left = 30, trial = 30), pc.aiTrial.value)
    }

    @Test
    fun `сервер о пробе молчит — экран о ней не говорит`() {
        val pc = account(stored = null, server = Server(offer = null))

        pc.refreshAiTrial()

        assertNull(pc.aiTrial.value)
    }

    @Test
    fun `остаток после действия виден без похода на сервер`() {
        val pc = account(stored = signedIn, server = Server())
        pc.refreshCircle()

        quota.remember(PointAiTrial(available = true, left = 6, trial = 30))
        pc.publishAiTrial()

        assertEquals(6, pc.aiTrial.value?.left)
    }
}
