package com.point.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.tr
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Клавиши в поле вопроса действия — у поля, а не у окна (находка ревью #1014).
 *
 * Человек скопировал фамилию в мессенджере, открыл на компьютере прочитанный скан, нажал
 * «Найти в документе» и Ctrl+V в поле «Что найти в документе?». Поле оставалось пустым: корень
 * окна слышал клавиши раньше поля и брал буфер новым объектом. Esc тем же путём закрывал весь
 * объект, а вопрос оставался висеть на нём. Проверено в окне целиком — тем путём, каким
 * нажимает человек, а не вводом текста мимо клавиатуры.
 */
@OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
)
class AskKeepsItsKeysTest {

    /** Что человек скопировал в мессенджере. */
    private val copied = "Іванов"

    /** Буфер человека — свой у теста: системный принадлежит тому, кто сидит за компьютером. */
    private class Copied(private val text: String) : ClipboardManager {
        override fun getText() = AnnotatedString(text)

        override fun setText(annotatedString: AnnotatedString) = Unit
    }

    private val heard: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    private val taken = AtomicInteger(0)

    private val hidden = AtomicInteger(0)

    private val asks = object : Realizer {
        override val capabilityId = CapabilityId("ask")
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
            heard += amendment
            return if (amendment == null) ActionResult.NeedsInput("Что найти в документе?") else ActionResult.Done(amendment)
        }
    }

    private val state = DesktopState(
        registry = DesktopRegistry(
            setOf(
                object : Capability {
                    override val id = CapabilityId("ask")
                    override val icon = "find"
                    override val meta = CapabilityMeta()
                    override fun label(state: ObjectState) = "Найти"
                    override fun accepts(state: ObjectState) = true
                    override fun produces(state: ObjectState) = state
                },
            ),
        ),
        resolver = object : Resolver {
            override fun realizerFor(capabilityId: CapabilityId): Realizer = asks
        },
        clipboard = { },
    )

    /** Работа окна идёт настоящими потоками: ждём события, а не срока. */
    private fun waitFor(what: () -> Boolean) {
        repeat(300) {
            if (what()) return
            Thread.sleep(10)
        }
    }

    /** Объект открыт, его действие спросило, что найти, — курсор стоит в поле вопроса. */
    private fun asked(): ImageComposeScene {
        val item = textArrival(id = "договір")
        state.onReceived(item, ObjectSource.LOCAL)
        state.onBubble(item, state.bubblesFor(item).single())
        waitFor { state.inputAsk.value != null }
        assertNotNull("действие не спросило — проверять клавиши не в чем", state.inputAsk.value)
        return compactScene(
            state,
            onHide = { hidden.incrementAndGet() },
            clipboard = Copied(copied),
            onClipboardTaken = { taken.incrementAndGet() },
        ).also { it.frames() }
    }

    @Test
    fun `Ctrl+V в поле вопроса вставляет скопированное, а не заводит новый объект`() {
        val scene = asked()
        try {
            scene.sendKeyEvent(KeyEvent(key = Key.V, type = KeyEventType.KeyDown, isCtrlPressed = true))
            scene.frames()
            scene.sendKeyEvent(KeyEvent(key = Key.Enter, type = KeyEventType.KeyDown))
            waitFor { heard.size == 2 }

            assertEquals("буфер ушёл новым объектом, а не в поле вопроса", 0, taken.get().toLong())
            assertNotEquals("окно полезло в буфер мимо поля вопроса", tr("В буфере пусто"), state.message.value)
            assertEquals("искали не то, что человек вставил", listOf(null, copied), heard.toList())
        } finally {
            scene.close()
        }
    }

    @Test
    fun `Esc в поле вопроса снимает вопрос, а объект остаётся открытым`() {
        val scene = asked()
        try {
            scene.sendKeyEvent(escapeKey())
            scene.frames()
            assertNull("Esc не снял вопрос — он так и висит на объекте", state.inputAsk.value)

            // Объект ещё открыт: следующий Esc ведёт к списку, и только третий прячет окно.
            scene.sendKeyEvent(escapeKey())
            scene.frames()
            assertEquals("Esc из поля вопроса закрыл вместе с вопросом и объект", 0, hidden.get().toLong())

            scene.sendKeyEvent(escapeKey())
            scene.frames()
            assertEquals("со списка Esc не спрятал окно — клавиши после вопроса умерли", 1, hidden.get().toLong())
            assertEquals("снятый вопрос запустил действие", listOf<String?>(null), heard.toList())
        } finally {
            scene.close()
        }
    }
}
