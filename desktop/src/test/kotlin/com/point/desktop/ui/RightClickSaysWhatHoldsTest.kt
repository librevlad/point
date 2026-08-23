package com.point.desktop.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.point.desktop.PcConfig
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test

/**
 * Переключатель «Открыть в Point» в меню файла говорит то, что есть на деле (#1082).
 *
 * Аудит: правда о пункте жила в памяти экрана и менялась только тапом — после перезапуска
 * переключатель снова говорил «Показывается», хотя команды в реестре не было. Здесь экран
 * рендерится как у человека и читается то, что на нём написано, — не чистая функция рядом.
 */
class RightClickSaysWhatHoldsTest {

    @get:Rule val compose = createComposeRule()

    private val enabled = PcConfig(name = "PC", rightClick = true)

    private fun show(
        config: PcConfig,
        holds: suspend (Boolean) -> Boolean?,
        tap: suspend (Boolean) -> Boolean = { true },
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

        compose.onNodeWithText(rightClickLine(on = true, trouble = false)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = true, trouble = true)).assertExists()
    }

    @Test
    fun `пункт стоит — экран говорит об этом без тапа`() {
        show(enabled, holds = { true })

        compose.onNodeWithText(rightClickLine(on = true, trouble = false)).assertExists()
    }

    @Test
    fun `выключенный флаг над оставшимся пунктом не говорит «Не показывается»`() {
        show(enabled.copy(rightClick = false), holds = { false })

        compose.onNodeWithText(rightClickLine(on = false, trouble = false)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = false, trouble = true)).assertExists()
    }

    @Test
    fun `тап отвечает по эффекту — не вставшая запись названа словом`() {
        show(enabled.copy(rightClick = false), holds = { true }, tap = { false })

        compose.onNodeWithText(rightClickLine(on = false, trouble = false)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(rightClickLine(on = true, trouble = false)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = true, trouble = true)).assertExists()
    }

    @Test
    fun `чтение, начатое до тапа, не перебивает ответ тапа`() {
        val slowRead = CompletableDeferred<Boolean>()
        show(enabled, holds = { slowRead.await() }, tap = { true })

        // Человек выключил, пока реестр ещё читался для прежнего положения.
        compose.onNodeWithText(rightClickLine(on = true, trouble = null)).performClick()
        compose.waitForIdle()
        slowRead.complete(false)
        compose.waitForIdle()

        compose.onNodeWithText(rightClickLine(on = false, trouble = false)).assertExists()
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

        compose.onNodeWithText(rightClickLine(on = true, trouble = false)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = true, trouble = true)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = true, trouble = null)).assertExists()

        slowRead.complete(true)
        compose.waitForIdle()

        compose.onNodeWithText(rightClickLine(on = true, trouble = false)).assertExists()
    }

    /** Реестр не прочитался вовсе: вердикта взять неоткуда, и выдумывать его нельзя. */
    @Test
    fun `нечитаемый реестр не превращается в «Не показывается»`() {
        show(enabled.copy(rightClick = false), holds = { null })

        compose.onNodeWithText(rightClickLine(on = false, trouble = false)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = false, trouble = true)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = false, trouble = null)).assertExists()
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

        compose.onNodeWithText(rightClickLine(on = true, trouble = false)).performClick()
        compose.waitForIdle()

        // Окно перерисовалось, пока `reg` и PowerShell ещё работают, — на потоке кадров это
        // было бы невозможно. И ни одного вердикта на экране нет: исход ещё не известен.
        compose.onNodeWithText(rightClickLine(on = false, trouble = false)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = false, trouble = true)).assertDoesNotExist()
        compose.onNodeWithText(rightClickLine(on = false, trouble = null)).assertExists()

        slowTap.complete(false)
        compose.waitForIdle()

        compose.onNodeWithText(rightClickLine(on = false, trouble = true)).assertExists()
    }
}
