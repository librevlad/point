package com.point.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Esc в компакт-окне идёт на один уровень назад, как «←» текущего экрана (#1025).
 *
 * Живая Windows, владелец 20.08.2026: из настроек Esc прятал окно целиком — человек
 * терял место, где стоял. Приоритет: раздел настроек → корень настроек → список;
 * объект → список; список → спрятать окно.
 */
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
}
