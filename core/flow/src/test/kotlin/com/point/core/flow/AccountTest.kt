package com.point.core.flow

import kotlinx.coroutines.launch
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

    // --- Вход доходит до конца (#561) ---

    /**
     * Главная улика закрытого релиза: сервер видел восемь начатых входов и НОЛЬ вопросов о том,
     * чем они кончились. Опрос — это не деталь реализации, а единственный способ забрать пропуск,
     * и проверяется он счётом на подделке клиента, а не чтением кода.
     */
    @Test fun `после старта приложение спрашивает сервер, чем вход кончился`() = runTest {
        val client = FakeAccountClient(readyAfter = 3)
        val driver = SignInDriver(client, MemoryAccountStore(), browser = {})

        val account = driver.signIn("Pixel", DeviceKind.PHONE) {}

        assertEquals(3, client.polls)
        assertNotNull(account)
    }

    /**
     * Один сбой связи не обрывает вход, который сервер уже подтвердил (#561).
     *
     * Молчание сети вероятнее всего ровно в ту секунду, когда телефон переключается на браузер, —
     * и пока оно приезжало отказом, вход умирал именно там, где чаще всего и происходил.
     */
    @Test fun `один сбой связи не обрывает вход`() = runTest {
        val client = FakeAccountClient(readyAfter = 3, silentPolls = setOf(1, 2))
        val store = MemoryAccountStore()
        val seen = mutableListOf<SignIn>()

        val account = SignInDriver(client, store, browser = {}).signIn("Pixel", DeviceKind.PHONE) { seen += it }

        assertNotNull("вход обязан дойти до пропуска, пережив молчание", account)
        assertEquals(account, store.current())
        assertTrue("человеку нечего было сообщать — сервер ответил", seen.none { it is SignIn.Refused })
    }

    /**
     * «До сервера не дозвониться» говорится только тогда, когда это правда (#561).
     *
     * Сообщение о неполадке связи через секунду после ответа сервера — ложь на экране поверх
     * несделанной работы. Правдой оно становится после полуминуты сплошного молчания.
     */
    @Test fun `про неполадку связи говорится после долгого молчания, а не после первого сбоя`() = runTest {
        val client = FakeAccountClient(readyAfter = Int.MAX_VALUE, silentAlways = true)
        val seen = mutableListOf<SignIn>()

        val account = SignInDriver(client, MemoryAccountStore(), browser = {}, pollIntervalMs = 2_000)
            .signIn("Pixel", DeviceKind.PHONE) { seen += it }

        assertNull(account)
        assertTrue("одного сбоя мало для приговора связи", client.polls >= 15)
        val refused = seen.last() as SignIn.Refused
        assertTrue(refused.what.contains("не дозвониться"))
    }

    /**
     * Начатый вход переживает экран, который его начал (#561).
     *
     * Это и есть настоящая причина «ноль обращений к сессии»: человек уходит в браузер, экрана к
     * возвращению может не быть, и спрашивать сервер становится некому и не о чем. Здесь первый
     * ход гибнет на полуслове, а вернувшийся человек дожимает вход **новым** ходом — без второго
     * входа и без второго браузера.
     */
    @Test fun `начатый вход переживает смерть экрана и дожимается на возврате`() = runTest {
        val logins = InMemoryPendingLogins()
        val store = MemoryAccountStore()
        val client = FakeAccountClient(readyAfter = Int.MAX_VALUE) // человек ещё в браузере
        val opened = mutableListOf<String>()

        // Экран умер посреди ожидания — как умирает Activity, пока человек у Google.
        val died = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            SignInDriver(client, store, browser = { opened += it }, pending = logins)
                .signIn("Pixel", DeviceKind.PHONE) {}
        }
        kotlinx.coroutines.yield()
        died.cancel()
        assertEquals("браузер обязан был открыться один раз", 1, opened.size)
        assertNotNull("начатый вход обязан лежать на устройстве", logins.current())

        // Человек подтвердил вход в браузере и вернулся в Point.
        client.readyNow()
        val seen = mutableListOf<SignIn>()
        val account = SignInDriver(client, store, browser = { opened += it }, pending = logins)
            .resume("Pixel", DeviceKind.PHONE) { seen += it }

        assertNotNull("вернувшийся человек обязан оказаться вошедшим", account)
        assertEquals(account, store.current())
        assertTrue(seen.last() is SignIn.SignedIn)
        assertEquals("второго браузера человек не просил", 1, opened.size)
        assertNull("законченный вход не остаётся лежать", logins.current())
    }

    /** Дожимать нечего — значит не задаётся ни одного вопроса: молчаливый возврат ничего не стоит. */
    @Test fun `без начатого входа возврат не трогает сервер`() = runTest {
        val client = FakeAccountClient()
        val seen = mutableListOf<SignIn>()

        val account = SignInDriver(client, MemoryAccountStore(), browser = {}, pending = InMemoryPendingLogins())
            .resume("Pixel", DeviceKind.PHONE) { seen += it }

        assertNull(account)
        assertEquals(0, client.polls)
        assertTrue("человеку нечего показывать", seen.isEmpty())
    }

    /**
     * Прерванный вход не оставляет приложение в вечном ожидании (критерий приёмки #3 из #561).
     *
     * Человек закрыл браузер и не вернулся; через полчаса он открывает Point. Просроченная запись
     * уходит сама, вместо опроса — понятное «начните заново».
     */
    @Test fun `прерванный вход просрочивается, а не ждёт вечно`() = runTest {
        val logins = InMemoryPendingLogins()
        logins.save(PendingLogin("l1", "claim-1", "K7-42Q", "https://point.example/login?d=l1", startedAtMillis = 0L))
        val client = FakeAccountClient()
        val seen = mutableListOf<SignIn>()

        val driver = SignInDriver(
            client, MemoryAccountStore(), browser = {}, pending = logins,
            now = { 30 * 60_000L }, // полчаса спустя
        )
        assertNotNull("просроченная запись обязана быть ВИДНА — иначе её никто не уберёт", driver.pendingLogin())

        val account = driver.resume("Pixel", DeviceKind.PHONE) { seen += it }

        assertNull(account)
        assertEquals("просроченный вход не опрашивают", 0, client.polls)
        assertNull("мёртвая запись не остаётся на устройстве", logins.current())
        assertTrue((seen.single() as SignIn.Refused).what.contains("просрочен"))
    }

    /** Передумал — начатый вход снимается, и дожимать на возврате нечего. */
    @Test fun `отмена входа снимает и запись о нём`() = runTest {
        val logins = InMemoryPendingLogins()
        val client = FakeAccountClient(readyAfter = Int.MAX_VALUE)
        val driver = SignInDriver(client, MemoryAccountStore(), browser = {}, pending = logins)
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            driver.signIn("Pixel", DeviceKind.PHONE) {}
        }
        kotlinx.coroutines.yield()
        job.cancel()

        driver.forgetPending()

        assertNull(logins.current())
        assertNull(driver.pendingLogin())
    }
}

/** Сервер, которого нет: отвечает по сценарию и запоминает, куда нас увели. */
internal class FakeAccountClient(
    private var readyAfter: Int = 1,
    private val startFails: Boolean = false,
    private val refuseWith: LoginPoll.Refused? = null,
    private val nameFromServer: String = "Pixel",
    var circle: List<CircleDevice> = emptyList(),
    /** Какие по счёту опросы не дозвонились — молчание сети посреди живого входа (#561). */
    private val silentPolls: Set<Int> = emptySet(),
    /** Связи нет вовсе: каждый опрос уходит в никуда. */
    private val silentAlways: Boolean = false,
) : AccountClient {

    var opened: String? = null
    var revoked: String? = null
    var signedOut = false
    var enrolledKey: String? = null
    var polls = 0
        private set

    /** Человек подтвердил вход в браузере — со следующего вопроса сервер отдаёт пропуск. */
    fun readyNow() { readyAfter = polls + 1 }

    override suspend fun start(deviceName: String, kind: DeviceKind): LoginStart? =
        if (startFails) null else LoginStart("login-1", "claim-1", "K7-42Q", "https://point.example/login?d=login-1")

    override suspend fun poll(loginId: String, claimToken: String): LoginPoll {
        refuseWith?.let { return it }
        polls++
        if (silentAlways || polls in silentPolls) return LoginPoll.Silent
        return if (polls >= readyAfter) {
            LoginPoll.Ready(PointAccount("d-1", "tok-1", "me@example.com", nameFromServer, DeviceKind.PHONE))
        } else {
            LoginPoll.Pending
        }
    }

    override suspend fun circle(account: PointAccount): CircleAnswer = CircleAnswer.Circle(circle)

    override suspend fun enroll(account: PointAccount, publicKey: String): Boolean {
        enrolledKey = publicKey
        return true
    }

    override suspend fun revoke(account: PointAccount, deviceId: String): Boolean {
        revoked = deviceId
        circle = circle.filterNot { it.id == deviceId }
        return true
    }

    override suspend fun signOut(account: PointAccount): Boolean {
        signedOut = true
        return revoke(account, account.deviceId)
    }
}

/** Пропуск в памяти — хранилище для тестов. */
internal class MemoryAccountStore(private var account: PointAccount? = null) : AccountStore {
    override fun current(): PointAccount? = account
    override suspend fun save(account: PointAccount) { this.account = account }
    override suspend fun clear() { account = null }
}
