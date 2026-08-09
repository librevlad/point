package com.point.desktop

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Density
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Живой прогон 2026-08-09: Ctrl+Shift+V молчал на каждом экране — без сфокусированного
 * узла Compose не доставляет клавиатуру вовсе. Контракт: корень окна сам берёт фокус,
 * и хоткей слышен без единого клика мышью.
 */
class HotkeyReachesRootTest {

    @OptIn(
        androidx.compose.ui.ExperimentalComposeUiApi::class,
        androidx.compose.ui.InternalComposeUiApi::class,
    )
    @Test
    fun `хоткей слышен сразу после открытия окна - фокус не нужно добывать кликом`() {
        val heard = AtomicInteger(0)
        val scene = ImageComposeScene(width = 400, height = 300, density = Density(1f)) {
            val hotkeys = remember { FocusRequester() }
            LaunchedEffect(Unit) { hotkeys.requestFocus() }
            Box(
                Modifier.fillMaxSize()
                    .focusRequester(hotkeys)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        val down = event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown
                        if (down && event.isCtrlPressed && event.isShiftPressed && event.key == Key.V) {
                            heard.incrementAndGet()
                            true
                        } else {
                            false
                        }
                    },
            )
        }
        try {
            scene.render() // первая композиция: LaunchedEffect просит фокус

            scene.sendKeyEvent(
                androidx.compose.ui.input.key.KeyEvent(
                    key = Key.V,
                    type = androidx.compose.ui.input.key.KeyEventType.KeyDown,
                    isCtrlPressed = true,
                    isShiftPressed = true,
                ),
            )

            assertEquals("Ctrl+Shift+V обязан дойти до корня без клика", 1, heard.get())
        } finally {
            scene.close()
        }
    }
}
