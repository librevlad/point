package com.point.desktop.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.tr
import com.point.desktop.DesktopRegistry
import com.point.desktop.DesktopState
import com.point.desktop.Inbox
import com.point.desktop.InboxItem
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Вопрос действия виден на экране объекта и на него можно ответить (ADR-0001 §18, #1014).
 *
 * Состояние вопроса проверено в `AskOnPcTest`; здесь — что человек его видит: экран собирается
 * как у человека, вопрос читается с экрана, ответ печатается в поле и уходит кнопкой.
 */
class AskIsAnsweredOnPcTest {

    @get:Rule val compose = createComposeRule()

    @get:Rule val temp = TemporaryFolder()

    private val prompt = "Что найти в документе?"

    private val heard: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    private val asks = object : Realizer {
        override val capabilityId = CapabilityId("ask")
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
            heard += amendment
            return if (amendment == null) ActionResult.NeedsInput(prompt) else ActionResult.Done(amendment)
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

    private fun asked(): InboxItem {
        val item = Inbox(temp.newFolder("inbox"))
            .addFile(temp.newFile("договір.txt").apply { writeText("Одержувач Іванов") }.absolutePath)
        state.onReceived(item)
        state.onBubble(item, state.bubblesFor(item).single())
        waitFor { state.inputAsk.value != null }
        return state.items.value.single()
    }

    private fun show(item: InboxItem) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PointDesktopTheme {
                ObjectPane(state = state, item = item)
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @Test
    fun `вопрос действия виден у объекта, а ответ из поля запускает действие`() {
        show(asked())

        compose.onNodeWithText(prompt).assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("Іванов")
        compose.onNodeWithText(tr("Готово")).performClick()
        waitFor { heard.size == 2 }

        assertEquals(listOf(null, "Іванов"), heard.toList())
        assertNull(state.inputAsk.value)
    }

    @Test
    fun `«Отмена» снимает вопрос и ничего не запускает`() {
        show(asked())

        compose.onNodeWithText(tr("Отмена")).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        assertNull(state.inputAsk.value)
        assertEquals(listOf<String?>(null), heard.toList())
        compose.onNodeWithText(prompt).assertDoesNotExist()
    }
}
