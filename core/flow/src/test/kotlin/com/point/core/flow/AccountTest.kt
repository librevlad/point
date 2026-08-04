package com.point.core.flow

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Вход судится здесь, без сети и без экрана (#472): порядок шагов, слова отказов и время последнего
 * контакта — чистая логика, и воспроизвести её пальцами с телефоном нельзя. Просроченный вход,
 * отозванное устройство и молчащий сервер — ровно те случаи, из-за которых человек застревает.
 */
class AccountTest {

    // --- Слова, которые читает человек ---

    @Test fun `код сверки стоит прямо в строке ожидания`() {
        assertEquals("Подтвердите вход в браузере · код K7-42Q", signInWaitingLine("K7-42Q"))
    }

    @Test fun `молчание сервера и отказ сервера — разные советы`() {
        val silent = accountRefusal(null)
        val refused = accountRefusal(401)
        assertTrue(silent.fix.contains("интернет"))
        assertTrue(refused.fix.contains("браузере"))
        assertTrue(accountRefusal(410).what.contains("просрочен"))
    }

    @Test fun `устройство без единого контакта — не то же, что молчащее`() {
        val now = 1_000_000_000L
        assertEquals("ещё ни разу не выходило на связь", lastSeenLabel(null, now))
        assertEquals("на связи", lastSeenLabel(now - 30_000, now))
        assertEquals("меньше часа назад", lastSeenLabel(now - 10 * 60_000, now))
        assertEquals("сегодня", lastSeenLabel(now - 5 * 60 * 60_000L, now))
        assertEquals("вчера", lastSeenLabel(now - 30 * 60 * 60_000L, now))
        assertEquals("4 дн. назад", lastSeenLabel(now - 4 * 24 * 60 * 60_000L, now))
    }

    // --- Ход входа ---

    @Test fun `вход проходит четыре состояния и сохраняет пропуск`() = runTest {
        val client = FakeAccountClient(readyAfter = 2)
        val store = MemoryAccountStore()
        val seen = mutableListOf<SignIn>()
        val driver = SignInDriver(client, store, browser = { client.opened = it })

        val account = driver.signIn("Pixel", DeviceKind.PHONE) { seen += it }

        assertNotNull(account)
        assertEquals("d-1", account!!.deviceId)
        assertEquals(DeviceKind.PHONE, account.kind)
        // Экран сказал код ДО того, как увёл в браузер: вернувшийся человек находит то же самое.
        assertTrue(seen.first() is SignIn.Waiting)
        assertEquals("K7-42Q", (seen.first() as SignIn.Waiting).code)
        assertTrue(seen.last() is SignIn.SignedIn)
        assertEquals("https://point.example/login?d=login-1", client.opened)
        assertEquals(account, store.current())
    }

    @Test fun `молчащий сервер не уводит в браузер и говорит, что чинить`() = runTest {
        val client = FakeAccountClient(startFails = true)
        val store = MemoryAccountStore()
        val seen = mutableListOf<SignIn>()

        val account = SignInDriver(client, store, browser = { client.opened = it })
            .signIn("Pixel", DeviceKind.PHONE) { seen += it }

        assertNull(account)
        assertNull(client.opened)
        assertTrue(seen.single() is SignIn.Refused)
        assertNull(store.current())
    }

    @Test fun `отказ сервера доносится словами сервера, а не общей ошибкой`() = runTest {
        val client = FakeAccountClient(refuseWith = LoginPoll.Refused("Почта не подтверждена", "Подтвердите почту в Google."))
        val store = MemoryAccountStore()
        val seen = mutableListOf<SignIn>()

        SignInDriver(client, store, browser = {}).signIn("Pixel", DeviceKind.PHONE) { seen += it }

        val refused = seen.last() as SignIn.Refused
        assertEquals("Почта не подтверждена", refused.what)
        assertEquals("Подтвердите почту в Google.", refused.fix)
    }

    @Test fun `ожидание кончается само и предлагает начать заново`() = runTest {
        val client = FakeAccountClient(readyAfter = Int.MAX_VALUE)
        val store = MemoryAccountStore()
        val seen = mutableListOf<SignIn>()

        val account = SignInDriver(client, store, browser = {}, pollIntervalMs = 1_000, timeoutMs = 5_000)
            .signIn("Pixel", DeviceKind.PHONE) { seen += it }

        assertNull(account)
        val refused = seen.last() as SignIn.Refused
        assertTrue(refused.what.contains("пять минут"))
        assertTrue(refused.fix.contains(SIGN_IN_ACTION))
    }

    @Test fun `имя устройства не теряется, если сервер его не вернул`() = runTest {
        val client = FakeAccountClient(readyAfter = 1, nameFromServer = "")
        val store = MemoryAccountStore()

        val account = SignInDriver(client, store, browser = {}).signIn("Рабочий ноутбук", DeviceKind.PC) {}

        assertEquals("Рабочий ноутбук", account?.deviceName)
        assertEquals(DeviceKind.PC, account?.kind)
    }
}

/** Сервер, которого нет: отвечает по сценарию и запоминает, куда нас увели. */
internal class FakeAccountClient(
    private val readyAfter: Int = 1,
    private val startFails: Boolean = false,
    private val refuseWith: LoginPoll.Refused? = null,
    private val nameFromServer: String = "Pixel",
    var circle: List<CircleDevice> = emptyList(),
) : AccountClient {

    var opened: String? = null
    var revoked: String? = null
    var signedOut = false
    private var polls = 0

    override suspend fun start(deviceName: String, kind: DeviceKind): LoginStart? =
        if (startFails) null else LoginStart("login-1", "K7-42Q", "https://point.example/login?d=login-1")

    override suspend fun poll(loginId: String): LoginPoll {
        refuseWith?.let { return it }
        polls++
        return if (polls >= readyAfter) {
            LoginPoll.Ready(PointAccount("d-1", "tok-1", "me@example.com", nameFromServer, DeviceKind.PHONE))
        } else {
            LoginPoll.Pending
        }
    }

    override suspend fun circle(account: PointAccount): List<CircleDevice> = circle

    override suspend fun revoke(account: PointAccount, deviceId: String): Boolean {
        revoked = deviceId
        circle = circle.filterNot { it.id == deviceId }
        return true
    }

    override suspend fun signOut(account: PointAccount): Boolean {
        signedOut = true
        return true
    }
}

/** Пропуск в памяти — хранилище для тестов. */
internal class MemoryAccountStore(private var account: PointAccount? = null) : AccountStore {
    override fun current(): PointAccount? = account
    override suspend fun save(account: PointAccount) { this.account = account }
    override suspend fun clear() { account = null }
}
