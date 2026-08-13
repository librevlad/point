package com.point.desktop

import com.point.core.flow.AccountClient
import com.point.core.flow.AccountStore
import com.point.core.flow.BrowserOpener
import com.point.core.flow.CircleAnswer
import com.point.core.flow.CircleDevice
import com.point.core.flow.DeviceKind
import com.point.core.flow.PointAccount
import com.point.core.flow.SignIn
import com.point.core.flow.SignInDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DesktopAccount(
    private val scope: CoroutineScope,
    private val store: AccountStore,
    private val client: AccountClient,
    browser: BrowserOpener,
    private val deviceName: String,

    private val keys: com.point.core.flow.DeviceKeyStore,

    /** Настройки этого компьютера в общем виде — то, что уедет за человеком (#610). */
    private val mySettings: () -> com.point.core.flow.AccountSettings = {
        com.point.core.flow.AccountSettings()
    },

    /** Что приехало общего — сюда. */
    private val onSettings: (com.point.core.flow.AccountSettings) -> Unit = {},
) {

    private val settingsSync = com.point.core.flow.AccountSettingsSync(client)

    private val driver = SignInDriver(client, store, browser)

    private val _signIn = MutableStateFlow<SignIn?>(if (store.current() == null) SignIn.SignedOut else null)

    val signIn: StateFlow<SignIn?> = _signIn

    private val _circle = MutableStateFlow<List<CircleDevice>>(emptyList())
    val circle: StateFlow<List<CircleDevice>> = _circle

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var job: Job? = null

    fun current(): PointAccount? = store.current()

    fun peers(): List<com.point.core.flow.LinkedPc> =
        _circle.value.filterNot { it.self }.map { com.point.core.flow.LinkedPc(it.id, it.name, it.key) }

    /**
     * Постучать в телефоны круга: «зайдите, для вас что-то есть» (#817).
     *
     * Стучим во все телефоны, а не в один: у человека их может быть два, и угадывать, за
     * каким он сейчас сидит, неоткуда. Молчание сервера не ошибка — просьба дождётся.
     */
    suspend fun knockPhones() {
        val account = store.current() ?: return
        _circle.value
            .filterNot { it.self }
            .filter { it.kind == DeviceKind.PHONE }
            .forEach { runCatching { client.knock(account, it.id) } }
    }

    fun signIn() {
        job?.cancel()
        job = scope.launch {
            driver.signIn(deviceName, DeviceKind.PC) { state -> _signIn.value = state }
            store.current()?.let { account ->

                runCatching { client.enroll(account, keys.keys().publicKey) }
                refreshCircle()
                syncSettings()
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _signIn.value = SignIn.SignedOut
    }

    fun dismissGate() {
        _signIn.value = null
    }

    fun refreshCircleNow() {
        val account = store.current() ?: return
        kotlinx.coroutines.runBlocking {
            val answer = runCatching { client.circle(account) }.getOrDefault(CircleAnswer.Unreachable)
            if (answer is CircleAnswer.Circle) _circle.value = answer.devices
        }
    }

    fun refreshCircle() {
        val account = store.current() ?: return

        scope.launch { runCatching { client.enroll(account, keys.keys().publicKey) } }
        scope.launch {
            val answer = runCatching { client.circle(account) }.getOrDefault(CircleAnswer.Unreachable)
            when (answer) {
                is CircleAnswer.Circle -> {
                    _error.value = null
                    _circle.value = answer.devices
                    syncSettings()
                }
                CircleAnswer.Unreachable -> _error.value = "Не удалось спросить сервер о ваших устройствах"

                CircleAnswer.Revoked -> forget()
            }
        }
    }

    /**
     * Свести настройки с общими (#610). Молча: обмен предпочтениями — не событие для
     * человека, а его отсутствие не повод для сообщения об ошибке.
     */
    fun syncSettings() {
        val account = store.current() ?: return
        scope.launch {
            val merged = runCatching { settingsSync.sync(account, keys.keys(), mySettings()) }.getOrNull()
            merged?.let(onSettings)
        }
    }

    fun revoke(deviceId: String) {
        val account = store.current() ?: return
        _busy.value = true
        scope.launch {
            val ok = runCatching { client.revoke(account, deviceId) }.getOrDefault(false)
            _busy.value = false
            if (!ok) {
                _error.value = "Сервер не отключил устройство — попробуйте ещё раз"
                return@launch
            }
            if (deviceId == account.deviceId) forget() else refreshCircle()
        }
    }

    fun signOut() {
        val account = store.current() ?: return
        _busy.value = true
        scope.launch {
            runCatching { client.signOut(account) }
            _busy.value = false
            forget()
        }
    }

    private suspend fun forget() {
        runCatching { store.clear() }
        _circle.value = emptyList()
        _signIn.value = SignIn.SignedOut
    }
}
