package com.point.core.flow

import kotlinx.coroutines.delay

/** Открыть ссылку в системном браузере — единственное, что вход просит у платформы. */
fun interface BrowserOpener {
    fun open(url: String)
}

/**
 * Ход входа целиком (#472, #473) — и он один на телефон и на компьютер.
 *
 * ```
 * start → запись о начатом входе → браузер → опрос → пропуск сохранён
 * ```
 *
 * Здесь, в чистом Kotlin, потому что это не UI и не HTTP, а порядок: что за чем и что человек видит
 * в каждый момент. Экран получает [SignIn] и рисует; ViewModel телефона и окно ПК зовут одно и то
 * же. Тест подставляет фальшивый [AccountClient] и проверяет все состояния без сети.
 *
 * **Начатый вход переживает экран** ([PendingLoginStore], #561). Смысл потока в том, что человек
 * уходит из приложения: браузер, Google, «Готово», возвращение. Пока `loginId` и `claimToken` жили
 * только в памяти экрана, вход не мог кончиться ничем: экран телефона к возвращению мог быть уже
 * уничтожен, и спрашивать сервер стало не о чем. Сервер это и показал — восемь начатых входов и
 * ноль вопросов о том, чем они кончились. Теперь начатый вход лежит на устройстве, и [resume]
 * дожимает его, когда человек вернулся, — не начиная нового и не открывая браузер второй раз.
 *
 * Ожидание кончается само: [timeoutMs] — та же пятиминутная жизнь входа, что и на сервере. Экран,
 * который ждёт вечно, — это тупик, а «из любого состояния есть выход» (#114).
 */
class SignInDriver(
    private val client: AccountClient,
    private val store: AccountStore,
    private val browser: BrowserOpener,
    private val pollIntervalMs: Long = POLL_MS,
    private val timeoutMs: Long = LOGIN_LIFETIME_MS,
    /** Где лежит начатый вход. По умолчанию — в памяти: окну ПК этого хватает. */
    private val pending: PendingLoginStore = InMemoryPendingLogins(),
    /** Часы — параметром, чтобы просроченный вход судился тестом, а не ожиданием в пять минут. */
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Провести вход от начала до конца, докладывая о каждом шаге в [onState].
     *
     * Возвращает пропуск, если вошли. Отмена (человек нажал «Отменить») — это отмена корутины: она
     * гасит опрос и оставляет экран тому, кто её попросил, — своего «состояния отмены» здесь нет.
     * Запись о начатом входе при отмене снимает вызывающий: только он знает, передумал человек или
     * просто закрылся экран.
     */
    suspend fun signIn(deviceName: String, kind: DeviceKind, onState: (SignIn) -> Unit): PointAccount? {
        val start = client.start(deviceName, kind)
        if (start == null) {
            // До сервера не дозвонились ДО того, как что-то началось: здесь «не дозвониться» —
            // правда, и человеку нечего дожимать.
            onState(accountRefusal(null))
            return null
        }
        val login = PendingLogin(
            loginId = start.loginId,
            claimToken = start.claimToken,
            // Вход одним шагом кода не показывает: браузер откроется здесь же и вернёт человека
            // сам, сверять число не с чем (#561). Пустой код доезжает и до записи о начатом
            // входе — иначе он всплыл бы на возврате, когда экран восстанавливается из неё.
            code = if (start.handoff) "" else start.code,
            url = start.url,
            startedAtMillis = now(),
        )
        // Запись ложится ДО браузера: уход в браузер — это и есть тот момент, после которого экрана
        // может не стать.
        pending.save(login)
        onState(SignIn.Waiting(loginId = login.loginId, code = login.code, url = login.url))
        // Браузер открывается ПОСЛЕ того, как экран сказал код: человек, вернувшийся из браузера,
        // должен найти на экране то же самое, что видел на странице, а не пустое ожидание.
        browser.open(login.url)
        return await(login, deviceName, kind, onState)
    }

    /**
     * Дожать вход, начатый раньше, — тот самый вопрос «чем всё кончилось» (#561).
     *
     * Зовётся, когда человек вернулся в Point: браузер он уже прошёл, и второго входа ему не надо.
     * `null` без единого доклада — значит дожимать нечего (начатого входа нет).
     */
    suspend fun resume(deviceName: String, kind: DeviceKind, onState: (SignIn) -> Unit): PointAccount? {
        val login = pending.current() ?: return null
        if (expired(login)) {
            // Человек ушёл и не вернулся вовремя. Мёртвая запись уходит сама — иначе она бы
            // поднимала опрос при каждом открытии Point до скончания века.
            pending.clear()
            onState(accountRefusal(410))
            return null
        }
        onState(SignIn.Waiting(loginId = login.loginId, code = login.code, url = login.url))
        return await(login, deviceName, kind, onState)
    }

    /** Человек передумал: начатый вход больше никому не нужен. */
    suspend fun forgetPending() = pending.clear()

    /**
     * Есть ли начатый вход — дешёвая проверка без сети, для тех, кто зовёт [resume] на возврате.
     *
     * Просроченную запись она НЕ прячет: спрятать значило бы оставить её лежать вечно. Разбирается
     * с просрочкой [resume] — он и стирает мёртвую запись, и говорит человеку «начните заново».
     */
    fun pendingLogin(): PendingLogin? = pending.current()

    /**
     * Опрос до ответа: «готово», отказ сервера или конец жизни входа.
     *
     * [LoginPoll.Silent] здесь — не конец, а повод спросить ещё раз. Своё «не дозвониться» опрос
     * говорит только после [silenceGiveUpMs] сплошного молчания: сообщать о неполадке связи после
     * одного сбоя значило бы врать в ту секунду, когда сервер отвечал (#561).
     */
    private suspend fun await(
        login: PendingLogin,
        deviceName: String,
        kind: DeviceKind,
        onState: (SignIn) -> Unit,
    ): PointAccount? {
        var silentFor = 0L
        var waited = 0L
        // Остаток жизни входа, а не полный срок: вход, начатый три минуты назад, ждёт две, а не
        // пять. Считается один раз — дальше счёт идёт шагами опроса, и тест меряет его виртуальным
        // временем, не настоящим.
        val budget = timeoutMs - (now() - login.startedAtMillis)
        // Спрашиваем СРАЗУ, а ждём между вопросами: человек, вернувшийся из браузера, уже всё
        // подтвердил, и заставлять его смотреть на ожидание лишние две секунды не за что.
        while (true) {
            when (val poll = client.poll(login.loginId, login.claimToken)) {
                LoginPoll.Pending -> silentFor = 0L
                LoginPoll.Silent -> {
                    silentFor += pollIntervalMs
                    if (silentFor >= silenceGiveUpMs) {
                        // Молчит долго и подряд — вот теперь это правда про связь. Запись остаётся:
                        // человек мог войти, пока связи не было, и следующий возврат её дожмёт.
                        onState(accountRefusal(null))
                        return null
                    }
                }
                is LoginPoll.Ready -> {
                    // Имя устройства сервер мог не вернуть — тогда остаётся то, которым представились.
                    val account = poll.account.copy(
                        deviceName = poll.account.deviceName.ifBlank { deviceName },
                        kind = kind,
                    )
                    store.save(account)
                    pending.clear()
                    onState(SignIn.SignedIn(account))
                    return account
                }
                is LoginPoll.Refused -> {
                    // Сервер ответил и отказал — этот вход пропуском не станет уже никогда.
                    pending.clear()
                    onState(SignIn.Refused(poll.what, poll.fix))
                    return null
                }
            }
            waited += pollIntervalMs
            if (waited >= budget) break
            delay(pollIntervalMs)
        }
        pending.clear()
        onState(
            SignIn.Refused(
                what = "Вход не подтвердили за пять минут",
                fix = "Нажмите «Войти через Google» ещё раз — страница входа откроется заново.",
            ),
        )
        return null
    }

    private fun expired(login: PendingLogin): Boolean = now() - login.startedAtMillis >= timeoutMs

    /** Сколько сплошного молчания считать неполадкой связи — но не меньше одного опроса. */
    private val silenceGiveUpMs: Long get() = maxOf(SILENCE_MS, pollIntervalMs)

    private companion object {
        const val POLL_MS = 2_000L
        const val LOGIN_LIFETIME_MS = 5 * 60_000L

        /** Полминуты молчания подряд — это уже связь, а не сбой. */
        const val SILENCE_MS = 30_000L
    }
}
