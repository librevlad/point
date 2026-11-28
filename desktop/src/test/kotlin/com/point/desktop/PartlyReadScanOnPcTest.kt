package com.point.desktop

import com.point.core.flow.FindCapability
import com.point.core.flow.FindRealizer
import com.point.core.flow.InvestigationState
import com.point.core.flow.KnownCapabilities
import com.point.core.flow.ReadDocumentCapability
import com.point.core.flow.advertisedActions
import com.point.core.flow.foundInDocumentLabel
import com.point.core.flow.foundOnPageLabel
import com.point.core.flow.investigationStateOf
import com.point.core.flow.ownWords
import com.point.core.flow.serviceRefusal
import com.point.core.model.Feature
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Скан, который сервис прочитал не целиком, на компьютере не получает «Ничего не нашлось» и
 * дочитывается (карточка «Не отвечать «Ничего не нашлось» по скану, прочитанному не целиком»,
 * подтверждено на компьютере пробами 28.11.2026).
 *
 * Сервис упёрся в дневной предел на части страниц: чтение честно сказало «не прочиталось
 * страниц: …», а поиск по словам с этих страниц отвечал «Ничего не нашлось», и дочитать их было
 * нечем — дверь чтения закрылась, как только у документа появился текст. Здесь сцепка целиком,
 * как в окне: настоящий pdfbox рисует страницы, состояние окна сливает знание, поиск спрашивает
 * запрос и отвечает.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PartlyReadScanOnPcTest {

    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    /** Исполнители документа работают на настоящем `Dispatchers.IO`: ждём их исхода. */
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

    private fun TestScope.find(state: DesktopState, query: String): String {
        val item = state.items.value.single()
        val before = state.message.value
        state.onBubble(item, state.bubblesFor(item).single { it.capabilityId == FindCapability.ID })
        settle { state.inputAsk.value != null }
        state.answerInput(query)
        settle { state.message.value.let { it != null && it != before } }
        return state.message.value.orEmpty()
    }

    @Test fun `страница, на которой сервис отказал, дочитывается на компьютере одна, и поиск находит её слова`() = runTest(dispatcher) {
        val pages = listOf("Договір поставки", "Одержувач Іванов", "Підпис сторін")
        var refused = false
        val sent = mutableListOf<String>()
        val resolver = DesktopResolver(
            setOf(
                pcReadDocumentRealizer(readPage = { page ->
                    sent += page.name
                    val number = page.name.filter(Char::isDigit).toInt()
                    if (number == 2 && !refused) {
                        refused = true
                        ownWords(serviceRefusal(429))
                    }
                    pages[number - 1]
                }),
                FindRealizer(),
            ),
        )
        val state = DesktopState(
            DesktopRegistry(desktopCapabilities(), runnable = resolver::canRun),
            resolver,
            clipboard = { },
            background = dispatcher,
            io = dispatcher,
        )

        // Телефон объявляет своё «Прочитать документ» — так, как объявляет его на самом деле.
        state.setPhoneCaps(advertisedActions(listOf(ReadDocumentCapability())), persist = false)
        state.onReceived(Inbox(temp.newFolder("inbox")).addFile(scannedPdf(pages = 3).absolutePath))
        advanceUntilIdle()

        val scan = state.items.value.single()
        state.onBubble(scan, state.bubblesFor(scan).single { it.capabilityId == KnownCapabilities.READ_DOCUMENT })
        settle { state.items.value.single().obj.state.has(Feature.HAS_TEXT) }

        val partly = state.items.value.single()
        assertEquals(
            "прочитанное с отказом выдано за прочитанное целиком",
            InvestigationState.INSUFFICIENTLY_INVESTIGATED,
            investigationStateOf(partly.obj.metadata, KnownCapabilities.IMAGE_TEXT),
        )

        val tail = find(state, "іванов")
        assertNotEquals("про страницу, которую сервис не прочёл, сказано «ничего не нашлось»", foundOnPageLabel(0), tail)
        assertTrue("ответ не говорит, сколько страниц прочитано: $tail", "2" in tail && "3" in tail)

        val again = state.bubblesFor(partly).singleOrNull { it.capabilityId == KnownCapabilities.READ_DOCUMENT }
        assertNotNull("после отказа сервиса дочитать нечем", again)
        assertEquals(
            "одна работа двумя дверями — своя «дочитать» и телефонное «прочитать»",
            1,
            state.actionsFor(partly).count {
                it.bubble?.capabilityId == KnownCapabilities.READ_DOCUMENT || it.remote?.id == KnownCapabilities.READ_DOCUMENT.value
            },
        )

        sent.clear()
        state.onBubble(state.items.value.single(), again!!)
        settle {
            investigationStateOf(state.items.value.single().obj.metadata, KnownCapabilities.IMAGE_TEXT) ==
                InvestigationState.FOUND
        }

        assertEquals("в сервис второй раз ушли уже прочитанные страницы", listOf("page-0002.png"), sent)
        assertEquals(foundInDocumentLabel(1, listOf(2)), find(state, "іванов"))
        assertTrue(
            "у прочитанного целиком скана осталась своя дверь чтения",
            state.bubblesFor(state.items.value.single()).none { it.capabilityId == KnownCapabilities.READ_DOCUMENT },
        )
    }
}
