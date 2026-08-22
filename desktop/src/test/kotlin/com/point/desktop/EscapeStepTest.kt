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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Esc в компакт-окне идёт на один уровень назад, как «←» текущего экрана (#1025).
 *
 * Живая Windows, владелец 20.08.2026: из настроек Esc прятал окно целиком — человек
 * терял место, где стоял. Приоритет: раздел настроек → корень настроек → список;
 * объект → список; список → спрятать окно.
 *
 * Лестница проверена дважды: на пути человека — клавишей в живом окне — и по всем ветвям
 * решения отдельно, потому что до каждой ветви окном добираться дороже, чем она стоит.
 */
@OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
)
class EscapeStepTest {

    @Test
    fun `из раздела настроек — к корню настроек, а не мимо них`() {
        assertEquals(
            EscapeStep.SETTINGS_SECTION_BACK,
            escapeStep(settingsOpen = true, settingsAtRoot = false, objectOpen = false),
        )
    }

    @Test
    fun `из корня настроек — назад, а не прятать окно`() {
        assertEquals(
            EscapeStep.SETTINGS_CLOSE,
            escapeStep(settingsOpen = true, settingsAtRoot = true, objectOpen = false),
        )
    }

    @Test
    fun `настройки поверх открытого объекта уходят первыми — объект остаётся`() {
        assertEquals(
            EscapeStep.SETTINGS_CLOSE,
            escapeStep(settingsOpen = true, settingsAtRoot = true, objectOpen = true),
        )
        assertEquals(
            EscapeStep.SETTINGS_SECTION_BACK,
            escapeStep(settingsOpen = true, settingsAtRoot = false, objectOpen = true),
        )
    }

    @Test
    fun `из объекта — к списку`() {
        assertEquals(
            EscapeStep.OBJECT_CLOSE,
            escapeStep(settingsOpen = false, settingsAtRoot = true, objectOpen = true),
        )
    }

    @Test
    fun `из списка — спрятать окно`() {
        assertEquals(
            EscapeStep.WINDOW_HIDE,
            escapeStep(settingsOpen = false, settingsAtRoot = true, objectOpen = false),
        )
    }

    /**
     * Путь человека целиком: объект приехал с телефона и раскрылся, Esc возвращает к списку,
     * и только второй Esc прячет окно. До #1025 первый же Esc уносил окно с экрана вместе с
     * местом, где человек стоял.
     */
    @Test
    fun `Esc внутри объекта ведёт к списку, и только на списке прячет окно`() {
        val hidden = AtomicInteger(0)
        val state = state()
        val account = account()
        val scene = ImageComposeScene(
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
                    onHide = { hidden.incrementAndGet() },
                )
            }
        }
        try {
            scene.frames() // окно открыто на списке

            val item = arrived()
            state.onReceived(item, ObjectSource.PHONE_RELAY)
            scene.frames() // объект приехал с телефона и раскрылся сам
            assertTrue(
                "объект не раскрылся — проверять Esc внутри него не на чем",
                item.obj.id !in state.fresh.value,
            )

            scene.sendKeyEvent(escape())
            scene.frames()
            assertEquals("Esc внутри объекта спрятал окно вместо шага назад", 0, hidden.get().toLong())

            scene.sendKeyEvent(escape())
            scene.frames()
            assertEquals("Esc на списке не спрятал окно", 1, hidden.get().toLong())
        } finally {
            scene.close()
        }
    }

    private fun escape() = KeyEvent(key = Key.Escape, type = KeyEventType.KeyDown)

    /**
     * Несколько кадров подряд: ответ окна на клавишу — это цепочка (состояние → перерисовка →
     * эффект), и одним кадром она не проходит.
     */
    private fun ImageComposeScene.frames() {
        repeat(5) {
            frame += 16_000_000L
            render(frame)
        }
    }

    private var frame = 0L

    private fun state() = DesktopState(
        registry = DesktopRegistry(emptySet()),
        resolver = DesktopResolver(emptySet()),
        clipboard = { },
        journalStore = object : JournalStore {
            override fun load(): List<JournalEntry> = emptyList()

            override fun save(entries: List<JournalEntry>) = Unit
        },
    )

    private fun arrived(): InboxItem {
        val file = File.createTempFile("esc-", ".txt").apply {
            writeText("Оплатите счёт 4411 до 26.04.2026.")
            deleteOnExit()
        }
        return InboxItem(
            PointObject(
                id = "приехал",
                mime = "text/plain",
                uri = ScratchRef(file.absolutePath),
                state = ObjectState(ObjectKind.TEXT),
                metadata = mapOf("name" to "Счёт 4411"),
            ),
        )
    }

    /** Человек за компьютером уже вошёл — иначе окно показывает вход, а не список. */
    private fun account() = DesktopAccount(
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
}
