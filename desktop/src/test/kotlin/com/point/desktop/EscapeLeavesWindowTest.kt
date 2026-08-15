package com.point.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.point.core.flow.AccountClient
import com.point.core.flow.AccountStore
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
import com.point.desktop.ui.PointColors
import com.point.desktop.ui.PointDesktopTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Выход из окна на компьютере (#1025). У окна нет ни рамки, ни системной кнопки, а Esc
 * не делал ничего: клавишу ждал корневой узел внутри, и пока фокус внутри никем не взят,
 * Compose не доставляет клавиши вовсе. Человеку оставалось попадание мышью в «←» и «✕»
 * размером с букву.
 *
 * Здесь проверено обещание: Esc уводит на шаг назад, а из списка — прячет окно, и слышит
 * его само окно, а не тот, кому достался фокус.
 */
class EscapeLeavesWindowTest {

    @Test
    fun `из объекта Esc возвращает в список, а не прячет окно`() {
        assertEquals(
            EscapeExit.CLOSE_OBJECT,
            escapeExit(asking = false, atSettings = false, objectOpened = true),
        )
    }

    @Test
    fun `из настроек Esc возвращает в список`() {
        assertEquals(
            EscapeExit.LEAVE_SETTINGS,
            escapeExit(asking = false, atSettings = true, objectOpened = false),
        )
    }

    @Test
    fun `из списка Esc прячет окно — отступать больше некуда`() {
        assertEquals(
            EscapeExit.HIDE_WINDOW,
            escapeExit(asking = false, atSettings = false, objectOpened = false),
        )
    }

    @Test
    fun `заданный вопрос Esc снимает первым — окно не уезжает из-под вопроса`() {
        assertEquals(
            EscapeExit.DISMISS_ASK,
            escapeExit(asking = true, atSettings = true, objectOpened = true),
        )
    }

    @Test
    fun `Esc слышит само окно, а не сфокусированный узел внутри`() {
        val main = File("src/main/kotlin/com/point/desktop/Main.kt").readText()
        val window = main.substringAfter("undecorated = true").substringBefore("CompactApp(")

        assertTrue("окно само не слушает клавиши", window.contains("onPreviewKeyEvent"))
        assertTrue("окно не слышит Esc", window.contains("Key.Escape"))
    }

    @Test
    fun `Esc из списка прячет окно, а из открытого объекта возвращает к списку`() {
        val item = textItem()
        val state = state()
        val escapes = MutableStateFlow(0)
        var hidden = 0

        val scene = ImageComposeScene(width = COMPACT_WIDTH, height = COMPACT_HEIGHT, density = Density(1f)) {
            PointDesktopTheme {
                Box(Modifier.fillMaxSize().background(PointColors.window)) {
                    CompactApp(
                        state = state,
                        config = PcConfig(name = "ПК"),
                        account = account(),
                        openObject = MutableStateFlow(null),
                        onObjectOpened = {},
                        escape = escapes,
                        onHide = { hidden += 1 },
                    )
                }
            }
        }
        try {
            repeat(3) { scene.render() }

            // Прибывшее раскрывается само: человек оказывается внутри сцены объекта.
            state.onReceived(item)
            repeat(3) { scene.render() }

            // Первый Esc уводит из объекта в список: окно при этом остаётся на экране —
            // иначе человек, закрывая объект, терял бы и окно.
            escapes.value += 1
            repeat(3) { scene.render() }
            assertEquals("Esc из объекта не должен прятать окно", 0, hidden)

            // Второй Esc прячет окно — и он же доказывает, что первый вернул в список.
            escapes.value += 1
            repeat(3) { scene.render() }
            assertEquals("Esc из списка обязан спрятать окно", 1, hidden)
        } finally {
            scene.close()
        }
    }

    private fun state() = DesktopState(
        registry = DesktopRegistry(emptySet()),
        resolver = DesktopResolver(emptySet()),
        clipboard = { },
        journalStore = object : JournalStore {
            override fun load(): List<JournalEntry> = emptyList()
            override fun save(entries: List<JournalEntry>) = Unit
        },
    )

    private fun textItem(): InboxItem {
        val file = File.createTempFile("выход-", ".txt").apply {
            writeText("Счёт на 1240")
            deleteOnExit()
        }
        return InboxItem(
            PointObject(
                id = "объект",
                mime = "text/plain",
                uri = ScratchRef(file.absolutePath),
                state = ObjectState(ObjectKind.TEXT),
                metadata = mapOf("name" to "Счёт"),
            ),
        )
    }

    private fun account(): DesktopAccount {
        val mine = PointAccount("пк", "пропуск", "я@point", "ПК", DeviceKind.PC)
        return DesktopAccount(
            scope = CoroutineScope(SupervisorJob()),
            store = object : AccountStore {
                override fun current(): PointAccount = mine
                override suspend fun save(account: PointAccount) = Unit
                override suspend fun clear() = Unit
            },
            client = object : AccountClient {
                override suspend fun start(deviceName: String, kind: DeviceKind): LoginStart? = null
                override suspend fun poll(loginId: String, claimToken: String): LoginPoll = LoginPoll.Silent
                override suspend fun enroll(account: PointAccount, publicKey: String) = false
                override suspend fun circle(account: PointAccount): CircleAnswer = CircleAnswer.Unreachable
                override suspend fun revoke(account: PointAccount, deviceId: String) = false
                override suspend fun deleteAccount(account: PointAccount) = false
            },
            browser = { },
            deviceName = "ПК",
            keys = object : DeviceKeyStore {
                override fun keys() = DeviceKeyPair("тайное", "открытое")
            },
        )
    }
}
