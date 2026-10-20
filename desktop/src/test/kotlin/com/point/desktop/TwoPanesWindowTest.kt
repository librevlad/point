package com.point.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.point.desktop.ui.PointWindow
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Окно в две панели (макет 19.10.2026): список и объект стоят рядом, а не сменяют друг друга.
 *
 * В компакте объект закрывал список — чтобы перейти к соседнему, человек шёл назад. В окне по
 * макету пришедший объект раскрывается справа, а «Недавнее» остаётся видно слева.
 */
@OptIn(ExperimentalTestApi::class)
class TwoPanesWindowTest {

    @Test
    fun `пришедший объект раскрывается справа, и список остаётся рядом`() = runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
        val state = desktopState()
        showCompact { window(state) }

        state.onReceived(textArrival(id = "первый", name = FIRST), ObjectSource.PHONE_RELAY)
        state.onReceived(textArrival(id = "второй", name = SECOND), ObjectSource.PHONE_RELAY)
        settle()

        val seen = sceneNodes()
        assertTrue("второго объекта не видно ни в списке, ни справа: ${seen.visibleText()}", seen.shows(SECOND))
        assertTrue("список пропал, пока объект открыт: ${seen.visibleText()}", seen.shows(FIRST))
        assertTrue("объект не раскрылся справа — нет его действий и текста: ${seen.visibleText()}", seen.shows(TEXT))
        assertTrue("шире окна: ${seen.tooWide(WINDOW_WIDTH)}", seen.tooWide(WINDOW_WIDTH).isEmpty())
    }

    @Test
    fun `Esc в окне сначала закрывает объект, и только потом прячет окно`() {
        val hidden = AtomicInteger(0)
        val state = desktopState()
        val scene = compactScene(state, onHide = { hidden.incrementAndGet() }, width = WINDOW_WIDTH, height = WINDOW_HEIGHT)
        try {
            scene.frames()
            state.onReceived(textArrival(id = "приехал"), ObjectSource.PHONE_RELAY)
            scene.frames()

            scene.sendKeyEvent(escapeKey())
            scene.frames()
            assertEquals("Esc при открытом объекте спрятал окно вместо шага назад", 0, hidden.get())

            scene.sendKeyEvent(escapeKey())
            scene.frames()
            assertEquals("Esc без открытого объекта не спрятал окно", 1, hidden.get())
        } finally {
            scene.close()
        }
    }

    @androidx.compose.runtime.Composable
    private fun window(state: DesktopState) = PointWindow(
        state = state,
        config = PcConfig(name = "Компьютер"),
        account = signedInAccount(),
        openObject = MutableStateFlow(null),
        onObjectOpened = {},
    )

    private companion object {
        const val FIRST = "Счёт за свет"
        const val SECOND = "Счёт за воду"

        /** Текст объекта из фикстуры `textArrival`: его видно только у раскрытого объекта. */
        const val TEXT = "Оплатите счёт 4411"
    }
}
