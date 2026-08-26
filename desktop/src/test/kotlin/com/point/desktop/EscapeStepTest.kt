package com.point.desktop

import java.util.concurrent.atomic.AtomicInteger
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
        val state = desktopState()
        val scene = compactScene(state, onHide = { hidden.incrementAndGet() })
        try {
            scene.frames() // окно открыто на списке

            val item = textArrival(id = "приехал")
            state.onReceived(item, ObjectSource.PHONE_RELAY)
            scene.frames() // объект приехал с телефона и раскрылся сам
            assertTrue(
                "объект не раскрылся — проверять Esc внутри него не на чем",
                item.obj.id !in state.fresh.value,
            )

            scene.sendKeyEvent(escapeKey())
            scene.frames()
            assertEquals("Esc внутри объекта спрятал окно вместо шага назад", 0, hidden.get().toLong())

            scene.sendKeyEvent(escapeKey())
            scene.frames()
            assertEquals("Esc на списке не спрятал окно", 1, hidden.get().toLong())
        } finally {
            scene.close()
        }
    }
}
