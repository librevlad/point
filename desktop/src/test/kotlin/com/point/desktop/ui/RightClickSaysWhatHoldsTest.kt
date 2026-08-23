package com.point.desktop.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.point.desktop.PcConfig
import com.point.desktop.ShortcutSendToMenu
import com.point.desktop.rightClickHolds
import com.point.desktop.sendToShortcut
import com.point.desktop.shellCommandFor
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Переключатель «Открыть в Point» в меню файла говорит то, что есть на деле (#1082).
 *
 * Аудит: правда о пункте жила в памяти экрана и менялась только тапом — после перезапуска
 * переключатель снова говорил «Показывается», хотя команды в реестре не было. Здесь экран
 * рендерится как у человека и читается то, что на нём написано, — не чистая функция рядом.
 */
class RightClickSaysWhatHoldsTest {

    @get:Rule val compose = createComposeRule()

    @get:Rule val temp = TemporaryFolder()

    private val enabled = PcConfig(name = "PC", rightClick = true)

    private fun show(
        config: PcConfig,
        holds: suspend (Boolean) -> Boolean?,
        tap: suspend (Boolean) -> Boolean? = { true },
    ) {
        compose.setContent {
            PointDesktopTheme {
                SettingsRoot(
                    config = config,
                    devices = 0,
                    email = "",
                    onSave = {},
                    onRightClick = tap,
                    rightClickHolds = holds,
                    onOpen = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `после перезапуска включённый флаг не говорит «Показывается» поверх пустого реестра`() {
        // Флаг на диске помнит «включено», а реестр пуст: так выглядит машина после перезапуска,
        // на которой запись не встала. Никто ничего не нажимал — правда обязана читаться сама.
        show(enabled, holds = { false })

        compose.onNodeWithText(rightClickLine(on = true, trouble = false, checking = false)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = true, trouble = true, checking = false)).assertExists()
    }

    @Test
    fun `пункт стоит — экран говорит об этом без тапа`() {
        show(enabled, holds = { true })

        compose.onNodeWithText(rightClickLine(on = true, trouble = false, checking = false)).assertExists()
    }

    @Test
    fun `выключенный флаг над оставшимся пунктом не говорит «Не показывается»`() {
        show(enabled.copy(rightClick = false), holds = { false })

        compose.onNodeWithText(rightClickLine(on = false, trouble = false, checking = false)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = false, trouble = true, checking = false)).assertExists()
    }

    @Test
    fun `тап отвечает по эффекту — не вставшая запись названа словом`() {
        show(enabled.copy(rightClick = false), holds = { true }, tap = { false })

        compose.onNodeWithText(rightClickLine(on = false, trouble = false, checking = false)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(rightClickLine(on = true, trouble = false, checking = false)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = true, trouble = true, checking = false)).assertExists()
    }

    @Test
    fun `чтение, начатое до тапа, не перебивает ответ тапа`() {
        val slowRead = CompletableDeferred<Boolean>()
        show(enabled, holds = { slowRead.await() }, tap = { true })

        // Человек выключил, пока реестр ещё читался для прежнего положения.
        compose.onNodeWithText(rightClickLine(on = true, trouble = null, checking = true)).performClick()
        compose.waitForIdle()
        slowRead.complete(false)
        compose.waitForIdle()

        compose.onNodeWithText(rightClickLine(on = false, trouble = false, checking = false)).assertExists()
    }

    /**
     * Пока реестр читается, знания о пункте нет — и подпись обязана молчать о вердикте.
     * «Показывается» поверх непрочитанного реестра — та же неправда, что и после перезапуска,
     * только длиной в секунду: чтение идёт полторы, и всё это время экран утверждает своё.
     */
    @Test
    fun `пока правда не прочитана, экран не выносит вердикта`() {
        val slowRead = CompletableDeferred<Boolean>()
        show(enabled, holds = { slowRead.await() })

        compose.onNodeWithText(rightClickLine(on = true, trouble = false, checking = false)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = true, trouble = true, checking = false)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = true, trouble = null, checking = true)).assertExists()

        slowRead.complete(true)
        compose.waitForIdle()

        compose.onNodeWithText(rightClickLine(on = true, trouble = false, checking = false)).assertExists()
    }

    /** Реестр не прочитался вовсе: вердикта взять неоткуда, и выдумывать его нельзя. */
    @Test
    fun `нечитаемый реестр не превращается в «Не показывается»`() {
        show(enabled.copy(rightClick = false), holds = { null })

        compose.onNodeWithText(rightClickLine(on = false, trouble = false, checking = false)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = false, trouble = true, checking = false)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = false, trouble = null, checking = false)).assertExists()
    }

    /**
     * Тап пишет в реестр и заводит ярлык — это `reg` и PowerShell, секунды. Пока это идёт,
     * окно живо и перерисовывается, а подпись не утверждает исход, которого ещё нет: раньше
     * работа шла прямо на потоке кадров, и окно замирало на каждом нажатии.
     */
    @Test
    fun `пока тап делает своё, окно живо и вердикта не выносит`() {
        val slowTap = CompletableDeferred<Boolean>()
        show(enabled, holds = { true }, tap = { slowTap.await() })

        compose.onNodeWithText(rightClickLine(on = true, trouble = false, checking = false)).performClick()
        compose.waitForIdle()

        // Окно перерисовалось, пока `reg` и PowerShell ещё работают, — на потоке кадров это
        // было бы невозможно. И ни одного вердикта на экране нет: исход ещё не известен.
        compose.onNodeWithText(rightClickLine(on = false, trouble = false, checking = false)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = false, trouble = true, checking = false)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = false, trouble = null, checking = true)).assertExists()

        slowTap.complete(false)
        compose.waitForIdle()

        compose.onNodeWithText(rightClickLine(on = false, trouble = true, checking = false)).assertExists()
    }

    /**
     * Тот же экран, но правду о пункте считает настоящее правило, а не подставной ответ (#1082).
     *
     * Пункт в реестре стоит и ведёт в эту установку, ярлык «Отправить → Point» лежит на диске —
     * а куда он ведёт, Windows не ответила: читает его PowerShell через COM, и этот ответ может
     * не прийти. Раньше молчание Windows шло на экран словами «Не удалось включить»: человек с
     * исправной установкой читал про сбой записи, которого не было.
     */
    @Test
    fun `молчание Windows про ярлык не становится на экране сбоем записи`() {
        val exe = File("C:/Program Files/Point/Point.exe")
        show(
            enabled,
            holds = { on ->
                rightClickHolds(
                    on = on,
                    exe = exe,
                    menuPresent = true,
                    command = shellCommandFor(exe),
                    linkPresent = true,
                    linkTarget = null,
                )
            },
        )

        compose.onNodeWithText(rightClickLine(on = true, trouble = true, checking = false)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = true, trouble = false, checking = false)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = true, trouble = null, checking = false)).assertExists()
    }

    /**
     * То же молчание Windows, но на пути тапа (#1082).
     *
     * Правило «не прочиталось — не ответ» стояло только на чтении при показе. Человек нажимал
     * включить, ярлык ложился на диск, а PowerShell про его цель молчал — и запись отвечала
     * «не встала». На экране это читалось как сбой записи, которого не было. Здесь тап идёт
     * через настоящий `ShortcutSendToMenu`, а не через подставной ответ.
     */
    @Test
    fun `молчание Windows про ярлык не становится сбоем записи и на тапе`() {
        val exe = File(temp.newFolder("Point"), "Point.exe").apply { writeText("") }
        val folder = File(temp.newFolder("home"), "SendTo")
        val link = ShortcutSendToMenu(folder) { command ->
            // Ярлык лёг, а COM не ответил: ни кода, ни цели.
            if ("Save()" in command.last()) sendToShortcut(folder).writeText("")
            1 to ""
        }
        show(enabled.copy(rightClick = false), holds = { true }, tap = { link.register(exe) })

        compose.onNodeWithText(rightClickLine(on = false, trouble = false, checking = false)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(rightClickLine(on = true, trouble = true, checking = false)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = true, trouble = null, checking = false)).assertExists()
    }

    /**
     * Нажатия идут очередью (#1082).
     *
     * Работа тапа уехала в IO, но осталась без очереди: два быстрых нажатия писали в реестр и в
     * папку «Отправить» одновременно, и кто допишет последним, решал случай — реестр мог
     * остаться в положении, обратном переключателю.
     */
    @Test
    fun `второе нажатие ждёт своей очереди, а не пишет вместе с первым`() {
        val gate = CompletableDeferred<Unit>()
        val turns = mutableListOf<Boolean>()
        show(enabled, holds = { true }, tap = { on -> turns += on; gate.await(); true })

        compose.onNodeWithText(rightClickLine(on = true, trouble = false, checking = false)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(rightClickLine(on = false, trouble = null, checking = true)).performClick()
        compose.waitForIdle()

        // Первый тап держит очередь — второй в реестр ещё не полез.
        assertEquals(listOf(false), turns)

        gate.complete(Unit)
        compose.waitForIdle()

        // Ход за ходом, и каждый сделал то, что просил сам, а не то, что стоит на экране сейчас.
        assertEquals(listOf(false, true), turns)
        compose.onNodeWithText(rightClickLine(on = true, trouble = false, checking = false)).assertExists()
    }

    /**
     * «Проверяется» — слово о длящемся действии, и правдой оно остаётся, только пока действие
     * идёт. Чтение при входе одно, повтора нет: после неудачного чтения проверять уже некому, и
     * обещание проверки висело бы на экране навсегда (#1082).
     */
    @Test
    fun `после неудачного чтения экран не обещает проверку, которой нет`() {
        show(enabled, holds = { null })

        compose.onNodeWithText(rightClickLine(on = true, trouble = null, checking = true)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = true, trouble = null, checking = false)).assertExists()
    }
}
