package com.point.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.point.desktop.ui.windowKeys
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Клавиши окна на пути человека (#1025).
 *
 * Живой прогон 2026-08-09: Ctrl+Shift+V молчал на каждом экране — без сфокусированного узла
 * Compose не доставляет клавиатуру вовсе, поэтому корень окна берёт фокус сам. Живая Windows
 * 20.08.2026: единственный запрос при старте уходил в пустоту, и Esc молчал до Tab'а.
 *
 * Отсюда же вторая половина обещания: фокус, который просят на каждом возврате в окно,
 * выдёргивает курсор из поля, в которое человек печатает. Здесь проверено и то, и другое —
 * поведением сцены, а не чтением исходника.
 */
@OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
)
class HotkeyReachesRootTest {

    /** Фокус окна у ОС: в жизни его отнимает и возвращает человек, уходя в другое окно. */
    private class OsWindow(focused: Boolean) : WindowInfo {
        override var isWindowFocused: Boolean by mutableStateOf(focused)
    }

    private val pasted = AtomicInteger(0)
    private val backed = AtomicInteger(0)

    /** Курсор стоит в поле — то есть буквы человека пойдут туда, а не мимо. */
    private var caretInField = false

    private var typed by mutableStateOf("")

    /** Тот же разбор клавиш, что делает окно: Ctrl+Shift+V — взять буфер, Esc — шаг назад. */
    private fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val paste = event.isCtrlPressed && event.isShiftPressed && event.key == Key.V
        val back = event.key == Key.Escape
        if (paste) pasted.incrementAndGet()
        if (back) backed.incrementAndGet()
        return paste || back
    }

    private fun window(
        window: OsWindow = OsWindow(focused = true),
        content: @Composable () -> Unit = {},
    ) = ImageComposeScene(width = 300, height = 200, density = Density(1f)) {
        CompositionLocalProvider(LocalWindowInfo provides window) {
            Box(Modifier.fillMaxSize().windowKeys(::onKey)) { content() }
        }
    }

    /** Поле ввода окна — такое же, как поле ключа в настройках. */
    @Composable
    private fun KeyField() {
        BasicTextField(
            value = typed,
            onValueChange = { typed = it },
            modifier = Modifier.fillMaxWidth().height(40.dp)
                .onFocusChanged { caretInField = it.isFocused },
        )
    }

    /**
     * Несколько кадров подряд: ответ окна на фокус — это цепочка (событие → перерисовка →
     * эффект), и одним кадром она не проходит.
     */
    private var frame = 0L

    private fun ImageComposeScene.frames() {
        repeat(5) {
            frame += 16_000_000L
            render(frame)
        }
    }

    private fun ImageComposeScene.click(at: Offset) {
        sendPointerEvent(PointerEventType.Press, at)
        sendPointerEvent(PointerEventType.Release, at)
    }

    private fun down(key: Key, ctrl: Boolean = false, shift: Boolean = false) = KeyEvent(
        key = key,
        type = KeyEventType.KeyDown,
        isCtrlPressed = ctrl,
        isShiftPressed = shift,
    )

    @Test
    fun `хоткей слышен сразу после открытия окна — фокус не нужно добывать кликом`() {
        val scene = window()
        try {
            scene.frames()

            scene.sendKeyEvent(down(Key.V, ctrl = true, shift = true))

            assertEquals("Ctrl+Shift+V не дошёл до корня окна", 1, pasted.get().toLong())
        } finally {
            scene.close()
        }
    }

    @Test
    fun `возврат в окно не выдёргивает курсор из поля, в котором человек печатает`() {
        val osWindow = OsWindow(focused = true)
        val scene = window(osWindow) { KeyField() }
        try {
            scene.frames()

            // Человек щёлкнул в поле ключа и печатает.
            scene.click(Offset(150f, 20f))
            scene.frames()
            assertTrue("клик по полю не поставил в него курсор — проверять нечего", caretInField)

            // Ушёл в браузер за ключом и вернулся в окно Point.
            osWindow.isWindowFocused = false
            scene.frames()
            osWindow.isWindowFocused = true
            scene.frames()

            assertTrue("курсор выдернули из поля — буквы человека пойдут мимо", caretInField)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `Esc слышен и тогда, когда курсор стоит в поле`() {
        val scene = window { KeyField() }
        try {
            scene.frames()
            scene.click(Offset(150f, 20f))
            scene.frames()
            assertTrue("клик по полю не поставил в него курсор — проверять нечего", caretInField)

            scene.sendKeyEvent(down(Key.Escape))

            assertEquals("Esc из поля не дошёл до окна — шага назад не будет", 1, backed.get().toLong())
        } finally {
            scene.close()
        }
    }

    @Test
    fun `поле ушло с экрана — окно снова слышит клавиши, а не молчит`() {
        var shown by mutableStateOf(true)
        val osWindow = OsWindow(focused = true)
        val scene = ImageComposeScene(width = 300, height = 200, density = Density(1f)) {
            CompositionLocalProvider(LocalWindowInfo provides osWindow) {
                Box(Modifier.fillMaxSize().windowKeys(::onKey)) {
                    if (shown) KeyField()
                }
            }
        }
        try {
            scene.frames()
            scene.click(Offset(150f, 20f))
            scene.frames()
            assertTrue("клик по полю не поставил в него курсор — проверять нечего", caretInField)

            // Раздел с полем закрылся — фокус ушёл в никуда.
            shown = false
            scene.frames()

            scene.sendKeyEvent(down(Key.Escape))

            assertEquals("после ухода поля Esc молчит — клавиши пришлось бы добывать Tab'ом", 1, backed.get().toLong())
        } finally {
            scene.close()
        }
    }
}
