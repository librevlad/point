/**
 * Живое окно компакта для тестов, идущих путём человека (#1019).
 *
 * Собрано в одном месте: сцена окна, кадры, Esc, чистое состояние ПК и уже вошедший человек.
 * Прежде каждый такой тест носил свою копию этих помощников — вплоть до комментария, — и
 * новый сюжет начинался с переписывания чужого файла.
 */
@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
)

package com.point.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import com.point.core.flow.AccountClient
import com.point.core.flow.AccountStore
import com.point.core.flow.BrowserOpener
import com.point.core.flow.CircleAnswer
import com.point.core.flow.DeviceKeyPair
import com.point.core.flow.DeviceKeyStore
import com.point.core.flow.DeviceKind
import com.point.core.flow.LoginPoll
import com.point.core.flow.LoginStart
import com.point.core.flow.PointAccount
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.desktop.ui.CompactApp
import com.point.desktop.ui.PointDesktopTheme
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

/** Окно компакта целиком, как его собирает `Main`: список ⇄ объект, Esc — шаг назад. */
fun compactScene(
    state: DesktopState,
    onHide: () -> Unit,
    account: DesktopAccount = signedInAccount(),
): ImageComposeScene = ImageComposeScene(
    width = COMPACT_WIDTH,
    height = COMPACT_HEIGHT,
    density = Density(1f),
) {
    PointDesktopTheme {
        CompactApp(
            state = state,
            config = PcConfig(name = "Компьютер"),
            account = account,
            openObject = MutableStateFlow(null),
            onObjectOpened = {},
            onHide = onHide,
        )
    }
}

/**
 * Несколько кадров подряд: ответ окна — это цепочка (состояние → перерисовка → эффект),
 * и одним кадром она не проходит.
 */
fun ImageComposeScene.frames(count: Int = 5) {
    repeat(count) { render(nextFrameNanos()) }
}

/** Время кадра обязано расти: сцена сравнивает его со своим прошлым. */
private val frameClock = AtomicLong(0)

private fun nextFrameNanos(): Long = frameClock.addAndGet(16_000_000L)

/** Esc — та самая клавиша, которую жмёт человек. */
fun escapeKey() = KeyEvent(key = Key.Escape, type = KeyEventType.KeyDown)

/** Компьютер без прошлого: журнал пуст, действий не зарегистрировано. */
fun desktopState() = DesktopState(
    registry = DesktopRegistry(emptySet()),
    resolver = DesktopResolver(emptySet()),
    clipboard = { },
    journalStore = object : JournalStore {
        override fun load(): List<JournalEntry> = emptyList()

        override fun save(entries: List<JournalEntry>) = Unit
    },
)

/** Объект, каким он доходит до окна: настоящий файл рядом и человеческое имя. */
fun textArrival(id: String, name: String = "Счёт 4411"): InboxItem {
    val file = File.createTempFile("$id-", ".txt").apply {
        writeText("Оплатите счёт 4411 до 26.04.2026.")
        deleteOnExit()
    }
    return InboxItem(
        PointObject(
            id = id,
            mime = "text/plain",
            uri = ScratchRef(file.absolutePath),
            state = ObjectState(ObjectKind.TEXT),
            metadata = mapOf("name" to name),
        ),
    )
}

/** Человек за компьютером уже вошёл — иначе окно показывает вход, а не список. */
fun signedInAccount() = DesktopAccount(
    scope = CoroutineScope(Dispatchers.Unconfined),
    store = object : AccountStore {
        private var held: PointAccount? = PointAccount(
            deviceId = "пк-1",
            deviceToken = "пропуск",
            email = "человек@example.org",
            deviceName = "Компьютер",
            kind = DeviceKind.PC,
        )

        override fun current(): PointAccount? = held

        override suspend fun save(account: PointAccount) { held = account }

        override suspend fun clear() { held = null }
    },
    client = object : AccountClient {
        override suspend fun start(deviceName: String, kind: DeviceKind): LoginStart? = null

        override suspend fun poll(loginId: String, claimToken: String): LoginPoll = LoginPoll.Silent

        override suspend fun enroll(account: PointAccount, publicKey: String) = false

        override suspend fun circle(account: PointAccount) = CircleAnswer.Unreachable

        override suspend fun revoke(account: PointAccount, deviceId: String) = false

        override suspend fun deleteAccount(account: PointAccount) = false
    },
    browser = BrowserOpener { },
    deviceName = "Компьютер",
    keys = object : DeviceKeyStore {
        override fun keys() = DeviceKeyPair(privateKey = "", publicKey = "")
    },
)
