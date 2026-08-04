package com.point.desktop

import com.point.core.flow.AccountClient
import com.point.core.flow.AccountStore
import com.point.core.flow.BrowserOpener
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

    /** Пропуск для клиентов релея: функция, а не значение, — он появляется и исчезает по ходу. */
    fun pass(): () -> String? = { store.current()?.deviceToken }

    fun signIn() {
        job?.cancel()
        job = scope.launch {
            driver.signIn(deviceName, DeviceKind.PC) { state -> _signIn.value = state }
            if (store.current() != null) refreshCircle()
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

    fun refreshCircle() {
        val account = store.current() ?: return
        scope.launch {
            val circle = runCatching { client.circle(account) }.getOrNull()
            if (circle == null) {
                _error.value = "Не удалось спросить сервер о ваших устройствах"
            } else {
                _error.value = null
                _circle.value = circle
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
