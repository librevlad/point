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

/**
 * Аккаунт на компьютере (#473) — то же самое, что на телефоне, и намеренно тем же кодом.
 *
 * Ход входа (`SignInDriver`), разговор с сервером (`HttpAccountClient`) и слова экрана живут в
 * `:core:flow`; здесь остаётся только живое состояние окна. Компьютер до сих пор не знал о себе
 * ничего, кроме токена, имени и порта, — теперь у него есть владелец, и круг устройств он видит
 * тот же, что телефон.
 *
 * Вход стоит при первом запуске: компьютер без круга и раньше ничего не делал — он стартовал
 * экраном «подключите телефон». Вход просто занял место пейринга.
 */
class DesktopAccount(
    private val scope: CoroutineScope,
    private val store: AccountStore,
    private val client: AccountClient,
    browser: BrowserOpener,
    private val deviceName: String,
    /** Ключи этого компьютера (#475): открытая половина едет в круг, закрытая остаётся здесь. */
    private val keys: com.point.core.flow.DeviceKeyStore,
) {

    private val driver = SignInDriver(client, store, browser)

    private val _signIn = MutableStateFlow<SignIn?>(if (store.current() == null) SignIn.SignedOut else null)

    /** `null` — вход пройден, окно занято работой. Иначе окно занято дверью. */
    val signIn: StateFlow<SignIn?> = _signIn

    private val _circle = MutableStateFlow<List<CircleDevice>>(emptyList())
    val circle: StateFlow<List<CircleDevice>> = _circle

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var job: Job? = null

    fun current(): PointAccount? = store.current()

    /**
     * Соседи по кругу и их открытые ключи (#475) — то, чем почта распечатывается.
     *
     * Тот же круг, что человек видит на экране: второго списка устройств в проекте не заводится, иначе
     * экран и почта разошлись бы молча.
     */
    fun peers(): List<com.point.core.flow.LinkedPc> =
        _circle.value.filterNot { it.self }.map { com.point.core.flow.LinkedPc(it.id, it.name, it.key) }

    fun signIn() {
        job?.cancel()
        job = scope.launch {
            driver.signIn(deviceName, DeviceKind.PC) { state -> _signIn.value = state }
            store.current()?.let { account ->
                // Ключ объявляется сразу после входа: телефон, уже бывший в круге, должен уметь
                // написать сюда, ничего от человека не дожидаясь.
                runCatching { client.enroll(account, keys.keys().publicKey) }
                refreshCircle()
            }
        }
    }

    /** Передумал — опрос гаснет, дверь возвращается к одной кнопке. */
    fun cancel() {
        job?.cancel()
        job = null
        _signIn.value = SignIn.SignedOut
    }

    /** Вошли — дверь уходит, и окно наконец занято работой. */
    fun dismissGate() {
        _signIn.value = null
    }

    /**
     * Круг прямо сейчас, на том потоке, который спросил (#475).
     *
     * Нужен разбору почты: письмо от телефона, вошедшего ПОСЛЕ запуска компьютера, нечем
     * распечатать, пока круг не обновился. Ждать следующего открытия экрана значило бы
     * потерять это письмо совсем.
     */
    fun refreshCircleNow() {
        val account = store.current() ?: return
        kotlinx.coroutines.runBlocking {
            val answer = runCatching { client.circle(account) }.getOrDefault(CircleAnswer.Unreachable)
            if (answer is CircleAnswer.Circle) _circle.value = answer.devices
        }
    }

    fun refreshCircle() {
        val account = store.current() ?: return
        // Ключ объявляется и здесь: компьютер мог войти сборкой, у которой ключей ещё не
        // было, и тогда телефону нечем ему написать, а причину он назовёт неверную.
        scope.launch { runCatching { client.enroll(account, keys.keys().publicKey) } }
        scope.launch {
            val answer = runCatching { client.circle(account) }.getOrDefault(CircleAnswer.Unreachable)
            when (answer) {
                is CircleAnswer.Circle -> {
                    _error.value = null
                    _circle.value = answer.devices
                }
                CircleAnswer.Unreachable -> _error.value = "Не удалось спросить сервер о ваших устройствах"
                // Отключили этот компьютер — он стирает своё состояние и показывает вход. Молчаливый
                // выход человек прочитал бы как поломку.
                CircleAnswer.Revoked -> forget()
            }
        }
    }

    /**
     * Отключить устройство круга.
     *
     * Отключили это — компьютер стирает свой пропуск и показывает вход. Это и есть замена прежнего
     * `resetToken()`: раньше сброс токена отвязывал разом все телефоны и никому об этом не говорил;
     * теперь отзывается поимённо и на сервере.
     */
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

    /** «Выйти»: устройство и его ящики уходят с сервера. */
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
