package com.point

import com.point.core.flow.CircleAnswer
import com.point.core.flow.CircleDevice
import com.point.core.flow.DeviceKind
import com.point.core.flow.PointAccount
import com.point.core.flow.SignIn
import com.point.core.flow.SignInDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Аккаунт и круг устройств живут своим держателем (#833, шаг 2).
 *
 * Второй шаг разреза `FlowViewModel` — после разговора (`ChatFlow`). Тема выбрана владельцем
 * и берётся целиком: вход, круг устройств, отключение, выход, удаление аккаунта. Пять
 * зависимостей уходят из ядра вместе с ней — они и нужны были только здесь.
 *
 * Ядро остаётся ядром: приём объекта, список действий, выполнение, обогащение и фокус — один
 * поток, и резать его нельзя. Соседние темы держатель зовёт швами, а не знает их устройство:
 * связка с компьютером — [onCircleLearned] и [onForgotten], настройки — [onSignedIn].
 *
 * Правила круга переехали дословно, вместе с их ценой (#1076): молчание сервера — беда
 * операции, а не «в круге никого нет»; отключённое уходит из памяти сразу, а не после
 * повторного ответа; вышедшему телефону чужие устройства задним числом не дописываются.
 */
/**
 * Всё, чем живёт аккаунт: хранилища и сервер (#833, шаг 2).
 *
 * Пять зависимостей ядру не нужны поодиночке — они нужны одной теме, и ходят вместе.
 * Внедряются они по-прежнему поштучно, здесь же собираются в одно имя: конструктор
 * `FlowViewModel` перестаёт расти от того, что у аккаунта появилось ещё одно хранилище.
 */
class AccountParts @javax.inject.Inject constructor(
    val store: com.point.core.flow.AccountStore,
    val client: com.point.core.flow.AccountClient,

    /** Последний успешный круг устройств: без сети экран показывает его, а не пустоту (#1076). */
    val circleStore: com.point.core.flow.CircleStore,

    val pendingLogins: com.point.core.flow.PendingLoginStore,
    val deviceKeys: com.point.core.flow.DeviceKeyStore,
)

class AccountFlow(
    parts: AccountParts,
    private val browser: com.point.core.flow.BrowserOpener,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,

    /** Как этот телефон называет себя в круге. */
    private val deviceName: () -> String,

    private val signInShown: () -> SignIn?,
    private val setSignIn: (SignIn?) -> Unit,

    private val devicesShown: () -> DevicesScreenState?,
    private val setDevices: (DevicesScreenState?) -> Unit,

    /** Экран устройств открывается поверх занятости и слов — их сносит тот, кто ими владеет. */
    private val showDevices: (DevicesScreenState) -> Unit,

    /** Человек вошёл: настройки едут за ним (#610). Тема настроек — не эта. */
    private val onSignedIn: () -> Unit,

    /** Новое знание о круге: связанный компьютер идёт за ним вместе (#1076). */
    private val onCircleLearned: suspend (List<CircleDevice>) -> Unit,

    /** Аккаунта больше нет: всё, что ему принадлежало, уходит вместе с ним. */
    private val onForgotten: suspend () -> Unit,
) {

    private val store = parts.store

    private val client = parts.client

    private val circleStore = parts.circleStore

    private val deviceKeys = parts.deviceKeys

    private val driver = SignInDriver(
        client = client,
        store = store,
        browser = browser,
        pending = parts.pendingLogins,
    )

    private var signInJob: Job? = null

    private var lastCircleSyncMs = 0L

    /** Есть ли аккаунт сейчас — спрашивают и соседние темы. */
    fun current(): PointAccount? = store.current()

    fun gate() {
        if (store.current() == null) {
            setSignIn(SignIn.SignedOut)
            resume()
        }
    }

    fun signIn() {
        signInJob?.cancel()
        signInJob = scope.launch {
            driver.signIn(deviceName(), DeviceKind.PHONE) { state -> show(state) }
        }
    }

    fun resume() {
        if (signInJob?.isActive == true) return
        signInJob = scope.launch {
            val started = withContext(io) { runCatching { driver.pendingLogin() }.getOrNull() }
            if (started == null) return@launch
            driver.resume(deviceName(), DeviceKind.PHONE) { state -> show(state, quiet = true) }
        }
    }

    private fun show(state: SignIn, quiet: Boolean = false) {
        if (state is SignIn.SignedIn) {
            val gateWasUp = signInShown() != null
            setSignIn(null)

            announceKey(state.account)
            onSignedIn()
            if (gateWasUp) openDevices()
            return
        }
        if (quiet && signInShown() == null) return
        setSignIn(state)
    }

    /**
     * Экран умер, а начатый вход — нет (#561).
     *
     * Отличается от [cancelSignIn]: там человек передумал, и начатое забывается. Здесь
     * забывать нечего — вход лежит на устройстве и дожимается вернувшимся человеком.
     */
    fun stopSignIn() {
        signInJob?.cancel()
        signInJob = null
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        signInJob = null

        scope.launch(NonCancellable) { runCatching { driver.forgetPending() } }
        setSignIn(SignIn.SignedOut)
    }

    fun dismissSignIn() = setSignIn(null)

    fun openSignInPage(url: String) = browser.open(url)

    fun hasGate(): Boolean = signInShown() != null

    fun openDevices() {
        val account = store.current()
        if (account == null) {
            gate()
            return
        }
        val self = CircleDevice(
            id = account.deviceId,
            kind = DeviceKind.PHONE,
            name = account.deviceName.ifBlank { deviceName() },
            lastSeenMillis = System.currentTimeMillis(),
            self = true,
        )
        showDevices(DevicesScreenState(email = account.email, devices = listOf(self), loading = true))
        scope.launch { loadCircle(account) }
        onSignedIn()
    }

    fun closeDevices() = setDevices(null)

    private fun updateDevices(block: (DevicesScreenState) -> DevicesScreenState) {
        devicesShown()?.let { setDevices(block(it)) }
    }

    private suspend fun loadCircle(account: PointAccount) {
        val answer = runCatching { client.circle(account) }.getOrDefault(CircleAnswer.Unreachable)
        when (answer) {
            is CircleAnswer.Circle -> {

                // Экран получает ответ сразу, знание укладывается следом: learnCircle
                // договаривает с компьютером и человека этим ждать незачем (#1076).
                updateDevices { it.copy(devices = answer.devices, checkedNow = true, loading = false, error = null) }
                learnCircle(answer.devices)
            }
            CircleAnswer.Unreachable -> {

                // Молчание сервера — беда операции, а не знание «в круге никого нет»:
                // ниже честной строки об ошибке стоит последний известный круг (#1076).
                // «Пока вы один» остаётся только тому, у кого круга не было никогда
                // или последний известный круг и правда из одного этого устройства.
                val remembered = withContext(io) { runCatching { circleStore.current() }.getOrNull() }
                updateDevices {
                    it.copy(
                        loading = false,
                        error = "Не удалось спросить сервер о ваших устройствах — проверьте интернет",
                        devices = remembered ?: it.devices,
                        checkedNow = false,
                    )
                }
            }
            CircleAnswer.Revoked -> forget(com.point.core.flow.ACCOUNT_REVOKED)
        }
    }

    fun revokeDevice(deviceId: String) {
        val account = store.current() ?: return

        // Круг, каким человек видел его, нажимая «Отключить». Память телефона знает круг лучше,
        // но её может не быть вовсе — шифрованное хранилище не создалось, и current() всегда
        // молчит. Тогда прежним кругом остаётся этот снимок, а не список на экране в момент
        // ответа сервера: экран человек вправе закрыть, не дождавшись его (#1076).
        val seen = devicesShown()?.devices.orEmpty()
        updateDevices { it.copy(busy = true, error = null) }
        scope.launch {
            val ok = runCatching { client.revoke(account, deviceId) }.getOrDefault(false)
            if (!ok) {
                updateDevices { it.copy(busy = false, error = "Сервер не отключил устройство — попробуйте ещё раз") }
                return@launch
            }
            if (deviceId == account.deviceId) {
                forget(SignIn.SignedOut)
                return@launch
            }

            // Сервер отключил устройство — это уже знание о круге, а не ожидание ответа:
            // устройство уходит из памяти телефона сейчас, а не после повторного чтения
            // круга, которое может и не дойти (#1076). Иначе отключённое переживало бы своё
            // отключение в памяти и возвращалось на экран, стоило серверу замолчать.
            //
            // Прежний круг берётся из памяти телефона, а на худой конец — из seen выше.
            // Из состояния экрана его не строят вовсе: экран — вид на знание, а не знание,
            // и закрытый экран превращал бы «в круге стало на одного меньше» в «круг опустел».
            updateDevices { screen ->
                screen.copy(busy = false, devices = screen.devices.filterNot { it.id == deviceId })
            }
            val remembered = withContext(io) { runCatching { circleStore.current() }.getOrNull() }
            val previous = remembered ?: seen
            learnCircle(previous.filterNot { it.id == deviceId })
            loadCircle(account)
        }
    }

    fun signOut() {
        val account = store.current() ?: return
        updateDevices { it.copy(busy = true, error = null) }
        scope.launch {
            runCatching { client.signOut(account) }
            forget(SignIn.SignedOut)
        }
    }

    fun deleteAccount() {
        val account = store.current() ?: return
        updateDevices { it.copy(busy = true, error = null) }
        scope.launch {
            val gone = runCatching { client.deleteAccount(account) }.getOrDefault(false)
            if (gone) {
                forget(SignIn.SignedOut)
            } else {
                updateDevices { it.copy(busy = false, error = com.point.core.flow.accountRefusal(null).what) }
            }
        }
    }

    private suspend fun forget(next: SignIn) {
        runCatching { store.clear() }

        // Круг принадлежит аккаунту: чужие устройства не переживают выход (#1076).
        runCatching { circleStore.clear() }
        onForgotten()
        setDevices(null)
        setSignIn(next)
    }

    fun syncCircle() {
        val account = store.current() ?: return
        val now = System.currentTimeMillis()
        if (now - lastCircleSyncMs < CIRCLE_SYNC_THROTTLE_MS) return
        lastCircleSyncMs = now
        announceKey(account)
        scope.launch {
            val answer = runCatching { client.circle(account) }.getOrNull()
            if (answer is CircleAnswer.Circle) learnCircle(answer.devices)
        }
    }

    /**
     * Новое знание о круге — одним шагом, откуда бы оно ни пришло: ответ сервера или
     * успешное отключение устройства. Память круга (#1076) и связанный компьютер идут
     * за знанием вместе; путь, который обновлял бы одно без другого, — и есть дефект,
     * при котором отключённое устройство оставалось в памяти до следующего ответа сервера.
     *
     * Шаг доводится до конца там, где его позвали, и не бросает работу вдогонку: одно
     * отключение приносит два знания подряд — своё и уточнение сервера, — и вторая запись
     * начинается после первой. Разбегавшиеся корутины писали связку с компьютером наперегонки,
     * и какое знание выигрывало, решал порядок ответов сети.
     *
     * Пустой список — незнание круга, а не круг без устройств: учить нечего.
     *
     * И учить некого, если аккаунта больше нет: круг принадлежит ему.
     */
    private suspend fun learnCircle(devices: List<CircleDevice>) {
        if (devices.isEmpty()) return

        // Ответ о круге приходит когда придёт, а человек за это время мог выйти: «Отключить»
        // прошло, кнопки на экране снова живые, повторный вопрос о круге ещё в пути — и тут
        // нажато «Выйти». forget к этому моменту уже стёр и память круга, и связку
        // с компьютером; дописать их задним числом — вернуть вышедшему телефону чужие
        // устройства и снова заговорить с чужим компьютером (#1076).
        if (store.current() == null) return
        runCatching { circleStore.save(devices) }
        onCircleLearned(devices)
    }

    private fun announceKey(account: PointAccount) {
        val key = runCatching { deviceKeys.keys().publicKey }.getOrNull() ?: return
        scope.launch { runCatching { client.enroll(account, key) } }
    }

    /** Ключи устройства нужны и теме настроек — за ними ходят сюда, а не мимо. */
    fun keys() = deviceKeys.keys()

    private companion object {

        /** Как часто телефон переспрашивает круг: чаще — впустую будить сеть на каждый объект. */
        const val CIRCLE_SYNC_THROTTLE_MS = 60_000L
    }
}
