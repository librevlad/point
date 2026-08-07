package com.point.core.flow

import kotlinx.coroutines.delay

fun interface BrowserOpener {
    fun open(url: String)
}

class SignInDriver(
    private val client: AccountClient,
    private val store: AccountStore,
    private val browser: BrowserOpener,
    private val pollIntervalMs: Long = POLL_MS,
    private val timeoutMs: Long = LOGIN_LIFETIME_MS,

    private val pending: PendingLoginStore = InMemoryPendingLogins(),

    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun signIn(deviceName: String, kind: DeviceKind, onState: (SignIn) -> Unit): PointAccount? {
        val start = client.start(deviceName, kind)
        if (start == null) {

            onState(accountRefusal(null))
            return null
        }
        val login = PendingLogin(
            loginId = start.loginId,
            claimToken = start.claimToken,

            code = if (start.handoff) "" else start.code,
            url = start.url,
            startedAtMillis = now(),
        )

        pending.save(login)
        onState(SignIn.Waiting(loginId = login.loginId, code = login.code, url = login.url))

        browser.open(login.url)
        return await(login, deviceName, kind, onState)
    }

    suspend fun resume(deviceName: String, kind: DeviceKind, onState: (SignIn) -> Unit): PointAccount? {
        val login = pending.current() ?: return null
        if (expired(login)) {

            pending.clear()
            onState(accountRefusal(410))
            return null
        }
        onState(SignIn.Waiting(loginId = login.loginId, code = login.code, url = login.url))
        return await(login, deviceName, kind, onState)
    }

    suspend fun forgetPending() = pending.clear()

    fun pendingLogin(): PendingLogin? = pending.current()

    private suspend fun await(
        login: PendingLogin,
        deviceName: String,
        kind: DeviceKind,
        onState: (SignIn) -> Unit,
    ): PointAccount? {
        var silentFor = 0L
        var waited = 0L

        val budget = timeoutMs - (now() - login.startedAtMillis)

        while (true) {
            when (val poll = client.poll(login.loginId, login.claimToken)) {
                LoginPoll.Pending -> silentFor = 0L
                LoginPoll.Silent -> {
                    silentFor += pollIntervalMs
                    if (silentFor >= silenceGiveUpMs) {

                        onState(accountRefusal(null))
                        return null
                    }
                }
                is LoginPoll.Ready -> {

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

    private val silenceGiveUpMs: Long get() = maxOf(SILENCE_MS, pollIntervalMs)

    private companion object {
        const val POLL_MS = 2_000L
        const val LOGIN_LIFETIME_MS = 5 * 60_000L

        const val SILENCE_MS = 30_000L
    }
}
