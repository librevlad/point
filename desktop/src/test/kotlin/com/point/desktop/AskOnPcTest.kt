package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.FindCapability
import com.point.core.flow.KnownCapabilities
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Действие компьютера может спросить человека (ADR-0001 §18, #1014).
 *
 * Прежде вопрос пропадал молча: исполнитель отвечал «что найти?», а окно не показывало ни
 * вопроса, ни поля — нажатие «Найти в документе» не делало ничего. Теперь вопрос стоит у
 * объекта, ответ запускает то же действие с ответом, а прочитанный скан на компьютере ищется
 * так же, как на телефоне.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AskOnPcTest {

    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    private class Says(id: String, private val name: String) : Capability {
        override val id = CapabilityId(id)
        override val icon = "x"
        override val meta = CapabilityMeta()
        override fun label(state: ObjectState) = name
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = state
    }

    /** Исполнитель, который без ответа спрашивает, а с ответом — делает. */
    private class Asks(val prompt: String, val suggestions: List<String> = emptyList()) : Realizer {
        override val capabilityId = CapabilityId("ask")
        val heard = mutableListOf<String?>()

        fun answerFor(amendment: String) = "→ $amendment"

        override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
            heard += amendment
            return if (amendment == null) ActionResult.NeedsInput(prompt, suggestions) else ActionResult.Done(answerFor(amendment))
        }
    }

    private class Pick(private val byId: Map<String, Realizer>) : Resolver {
        override fun realizerFor(capabilityId: CapabilityId): Realizer = byId.getValue(capabilityId.value)
    }

    private fun askingState(asks: Asks): DesktopState = DesktopState(
        DesktopRegistry(setOf(Says("ask", "Спросить"))),
        Pick(mapOf("ask" to asks)),
        clipboard = { },
        background = dispatcher,
        io = dispatcher,
    )

    private fun DesktopState.receivedText(): InboxItem {
        val inbox = Inbox(temp.newFolder())
        onReceived(inbox.addFile(temp.newFile("заметка.txt").apply { writeText("текст") }.absolutePath))
        return items.value.single()
    }

    @Test fun `действие спросило — вопрос стоит у объекта, а ответ запускает его же с ответом`() = runTest(dispatcher) {
        val asks = Asks(prompt = "?", suggestions = listOf("один", "два"))
        val state = askingState(asks)
        val item = state.receivedText()

        state.onBubble(item, state.bubblesFor(item).single())
        advanceUntilIdle()

        val ask = state.inputAsk.value
        assertNotNull("вопрос действия пропал молча — человеку нечем ответить", ask)
        assertEquals(asks.prompt, ask!!.prompt)
        assertEquals(asks.suggestions, ask.suggestions)
        assertEquals("вопрос стоит не у того объекта", item.obj.id, ask.item.obj.id)

        state.answerInput("  ")
        advanceUntilIdle()
        assertNotNull("пустой ответ снял вопрос", state.inputAsk.value)
        assertEquals("пустой ответ запустил действие", 1, asks.heard.size)

        state.answerInput("два")
        advanceUntilIdle()

        assertEquals(listOf(null, "два"), asks.heard)
        assertNull("отвеченный вопрос остался висеть", state.inputAsk.value)
        assertEquals(asks.answerFor("два"), state.message.value)
    }

    /** Ожидание — не исход (ADR-0001 §18): в «ПУТИ» его сменяет исход того же шага, а не строка рядом. */
    @Test fun `ответ заменяет в пути ожидание, а не встаёт второй строкой`() = runTest(dispatcher) {
        val state = askingState(Asks(prompt = "?"))
        val item = state.receivedText()

        state.onBubble(item, state.bubblesFor(item).single())
        advanceUntilIdle()
        state.answerInput("да")
        advanceUntilIdle()

        val steps = state.journal.value.single { it.path == item.obj.uri.value }.steps.filter { it.capabilityId == "ask" }
        assertEquals(listOf(StepOutcome.DONE), steps.map { it.outcome })
    }

    @Test fun `человек передумал — вопрос уходит, действие не запускается`() = runTest(dispatcher) {
        val asks = Asks(prompt = "?")
        val state = askingState(asks)
        val item = state.receivedText()

        state.onBubble(item, state.bubblesFor(item).single())
        advanceUntilIdle()
        state.dismissInput()
        state.answerInput("поздно")
        advanceUntilIdle()

        assertNull(state.inputAsk.value)
        assertEquals("снятый вопрос всё равно запустил действие", listOf<String?>(null), asks.heard)
    }

    /** Просьбу телефона спрашивают там, где человек, — на телефоне, а не на экране компьютера. */
    @Test fun `вопрос по просьбе телефона на компьютере не встаёт`() = runTest(dispatcher) {
        val state = askingState(Asks(prompt = "?"))
        val item = state.receivedText()

        state.runRemoteAction("ask", item)
        advanceUntilIdle()

        assertNull(state.inputAsk.value)
    }

    /**
     * Исполнители документа работают на настоящем `Dispatchers.IO`: ждём их исхода, чередуя
     * планировщик теста и реальное время.
     */
    private fun TestScope.settle(until: () -> Boolean) {
        repeat(300) {
            advanceUntilIdle()
            if (until()) return
            Thread.sleep(10)
        }
        advanceUntilIdle()
    }

    private fun scannedPdf(pages: Int): File {
        val file = temp.newFile("skan.pdf")
        org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
            repeat(pages) { doc.addPage(org.apache.pdfbox.pdmodel.PDPage()) }
            doc.save(file)
        }
        return file
    }

    /**
     * Сцепка на компьютере целиком (#1014, решение владельца 19.08.2026): скан читается общим
     * «Прочитать документ», и «Найти в документе», которого у непрочитанного скана нет,
     * появляется, спрашивает запрос и называет страницу, на которой нашлось.
     */
    @Test fun `прочитанный скан на компьютере предлагает «Найти в документе» и находит со страницей`() = runTest(dispatcher) {
        val pageTexts = listOf("Договір поставки", "Одержувач Іванов")
        var page = 0
        val resolver = DesktopResolver(
            setOf(
                pcReadDocumentRealizer(readPage = { pageTexts[page++] }),
                com.point.core.flow.FindRealizer(),
            ),
        )
        val state = DesktopState(
            DesktopRegistry(desktopCapabilities(), runnable = resolver::canRun),
            resolver,
            clipboard = { },
            background = dispatcher,
            io = dispatcher,
        )
        state.onReceived(Inbox(temp.newFolder("inbox")).addFile(scannedPdf(pages = 2).absolutePath))
        advanceUntilIdle()

        val scan = state.items.value.single()
        assertTrue("скан не узнан при приёме", scan.obj.state.has(Feature.IS_IMAGE_PDF))
        assertFalse(
            "у непрочитанного скана искать не в чем, а действие предложено",
            state.bubblesFor(scan).any { it.capabilityId == FindCapability.ID },
        )

        state.onBubble(scan, state.bubblesFor(scan).single { it.capabilityId == KnownCapabilities.READ_DOCUMENT })
        settle { state.items.value.single().obj.state.has(Feature.HAS_TEXT) }

        val read = state.items.value.single()
        assertTrue("текст не лёг знанием на сам PDF", read.obj.state.has(Feature.HAS_TEXT))
        val find = state.bubblesFor(read).singleOrNull { it.capabilityId == FindCapability.ID }
        assertNotNull("у прочитанного скана нет «Найти в документе»", find)

        state.onBubble(read, find!!)
        settle { state.inputAsk.value != null }
        assertNotNull("поиск не спросил, что искать", state.inputAsk.value)

        val heard = com.point.core.flow.foundInDocumentLabel(1, listOf(2))
        state.answerInput("іванов")
        settle { state.message.value == heard }

        assertEquals("найденное названо со страницей, где оно стоит", heard, state.message.value)
        assertNull(state.inputAsk.value)
        assertEquals(ObjectKind.PDF, state.items.value.single().obj.state.kind)
    }
}
