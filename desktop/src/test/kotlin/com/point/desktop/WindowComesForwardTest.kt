package com.point.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Человек позвал Point — окно выходит вперёд (#1019). «Открыть в Point» из проводника
 * принимал объект молча: окно оставалось третьим в порядке окон, под тем приложением, в
 * котором человек работал. Три объекта подряд ушли в Point вслепую, а каждый повтор зова
 * заводил ещё один объект.
 */
class WindowComesForwardTest {

    private enum class Step { TOPMOST, NOT_TOPMOST, FRONT, FOCUS }

    private class FakeWindow(override var iconified: Boolean = false) : RaisableWindow {
        val steps = mutableListOf<Step>()

        override var alwaysOnTop: Boolean = false
            set(value) {
                field = value
                steps += if (value) Step.TOPMOST else Step.NOT_TOPMOST
            }

        override fun toFront() {
            steps += Step.FRONT
        }

        override fun requestFocus() {
            steps += Step.FOCUS
        }
    }

    @Test
    fun `окно поднимается, пока объявлено верхним — иначе система не отдаёт передний план`() {
        val window = FakeWindow()

        raiseAboveOthers(window)

        val front = window.steps.indexOf(Step.FRONT)
        assertTrue("окно не поднимали вовсе", front > 0)
        assertEquals("подъём вне шага «поверх всех» система игнорирует", Step.TOPMOST, window.steps[front - 1])
    }

    @Test
    fun `после подъёма окно не остаётся поверх чужой работы`() {
        val window = FakeWindow()

        raiseAboveOthers(window)

        assertFalse("«поверх всех» вернулось насовсем", window.alwaysOnTop)
        assertEquals("окно оставили висеть над чужой работой", Step.NOT_TOPMOST, window.steps.last())
    }

    @Test
    fun `свёрнутое окно разворачивается — поднимать значок на панели бесполезно`() {
        val window = FakeWindow(iconified = true)

        raiseAboveOthers(window)

        assertFalse("окно осталось свёрнутым", window.iconified)
    }

    @Test
    fun `принятый из проводника объект просит поднять окно, а не только показать его`() {
        val main = File("src/main/kotlin/com/point/desktop/Main.kt").readText()
        val handOff = main.substringAfter("collectHandOffs").substringBefore("takeWake")

        assertTrue(
            "принятый объект только снимает скрытие: уже видимое окно от этого не меняется",
            handOff.contains("raises.value += 1"),
        )
        assertTrue("окно само не поднимается на зов", main.contains("raiseAboveOthers("))
    }
}
