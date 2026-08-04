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
 * start → браузер → опрос → пропуск сохранён
 * ```
 *
 * Здесь, в чистом Kotlin, потому что это не UI и не HTTP, а порядок: что за чем и что человек видит
 * в каждый момент. Экран получает [SignIn] и рисует; ViewModel телефона и окно ПК зовут одно и то
 * же. Тест подставляет фальшивый [AccountClient] и проверяет **все четыре** состояния без сети.
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
) {

    /**
     * Провести вход от начала до конца, докладывая о каждом шаге в [onState].
     *
     * Возвращает пропуск, если вошли. Отмена (человек нажал «Отменить») — это отмена корутины: она
     * гасит опрос и оставляет экран тому, кто её попросил, — своего «состояния отмены» здесь нет.
     */
    suspend fun signIn(deviceName: String, kind: DeviceKind, onState: (SignIn) -> Unit): PointAccount? {
        val start = client.start(deviceName, kind)
        if (start == null) {
            onState(accountRefusal(null))
            return null
        }
        onState(SignIn.Waiting(loginId = start.loginId, code = start.code, url = start.url))
        // Браузер открывается ПОСЛЕ того, как экран сказал код: человек, вернувшийся из браузера,
        // должен найти на экране то же самое, что видел на странице, а не пустое ожидание.
        browser.open(start.url)

        var waited = 0L
        while (waited < timeoutMs) {
            delay(pollIntervalMs)
            waited += pollIntervalMs
            when (val poll = client.poll(start.loginId)) {
                LoginPoll.Pending -> Unit
                is LoginPoll.Ready -> {
                    // Имя устройства сервер мог не вернуть — тогда остаётся то, которым представились.
                    val account = poll.account.copy(
                        deviceName = poll.account.deviceName.ifBlank { deviceName },
                        kind = kind,
                    )
                    store.save(account)
                    onState(SignIn.SignedIn(account))
                    return account
                }
                is LoginPoll.Refused -> {
                    onState(SignIn.Refused(poll.what, poll.fix))
                    return null
                }
            }
        }
        onState(
            SignIn.Refused(
                what = "Вход не подтвердили за пять минут",
                fix = "Нажмите «Войти через Google» ещё раз — страница входа откроется заново.",
            ),
        )
        return null
    }

    private companion object {
        const val POLL_MS = 2_000L
        const val LOGIN_LIFETIME_MS = 5 * 60_000L
    }
}
