package com.point.desktop

import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Findings
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Аудит десктопа 2026-08-09, блок 1.1: познавательные действия рождали новый объект,
 * а знание на исходник не писал никто. Контракт фазы A: Done+findings ложится в сам
 * объект тем же mergeKnowledge, что и на телефоне (Конституция §4, инвариант 6).
 */
class KnowledgeLandsOnSourceTest {

    @get:Rule val temp = TemporaryFolder()

    private fun realizerOf(result: ActionResult) = object : Realizer {
        override val capabilityId = CapabilityId("find-in-text")
        override suspend fun perform(input: PointObject, amendment: String?) = result
    }

    private fun state(result: ActionResult, store: JournalStore? = null) = DesktopState(
        DesktopRegistry(emptySet()),
        object : Resolver {
            override fun realizerFor(capabilityId: CapabilityId) = realizerOf(result)
        },
        clipboard = { },
        journalStore = store,
    )

    private fun textItem(file: File, metadata: Map<String, String> = mapOf("name" to "чек.txt")) = InboxItem(
        PointObject("src", "text/plain", ScratchRef(file.absolutePath), ObjectState(ObjectKind.TEXT), metadata),
    )

    @Test
    fun `знание из Done ложится в исходник, нового объекта не появляется`() {
        val file = temp.newFile("чек.txt").apply { writeText("тел +380671234567") }
        val st = state(
            ActionResult.Done(
                "Нашёл: телефон",
                Findings(
                    features = setOf(Feature.HAS_PHONE),
                    metadata = mapOf(
                        "entity.phone" to "+380671234567",
                        "investigated.find-in-text" to "found",
                    ),
                ),
            ),
        )
        val item = textItem(file)
        st.onReceived(item)

        runBlocking { st.runRemoteActionNow("find-in-text", item, budgetMs = 5_000) }

        val kept = st.items.value.single()
        assertEquals("+380671234567", kept.obj.metadata["entity.phone"])
        assertEquals("found", kept.obj.metadata["investigated.find-in-text"])
        assertTrue(kept.obj.state.features.contains(Feature.HAS_PHONE))
    }

    @Test
    fun `свежее прочтение не затирает известное — расхождение остаётся видимым`() {
        val file = temp.newFile("спор.txt").apply { writeText("текст") }
        val st = state(
            ActionResult.Done("Нашёл", Findings(metadata = mapOf("entity.phone" to "+380222222222"))),
        )
        val item = textItem(file, mapOf("name" to "спор.txt", "entity.phone" to "+380111111111"))
        st.onReceived(item)

        runBlocking { st.runRemoteActionNow("find-in-text", item, budgetMs = 5_000) }

        val meta = st.items.value.single().obj.metadata
        assertEquals("+380111111111", meta["entity.phone"])
        assertTrue(
            "второе прочтение обязано остаться видимым",
            meta.any { (k, v) -> k.startsWith("entity.phone.") && v.contains("+380222222222") },
        )
    }

    @Test
    fun `прибытие оставляет след, пока объект не открыли`() {
        // Аудит компакта, раунд 2: peek легко пропустить — след живёт до первого открытия.
        val file = temp.newFile("новое.txt").apply { writeText("текст") }
        val st = state(ActionResult.Done("не нужен"))
        val item = textItem(file, mapOf("name" to "новое.txt"))

        st.onReceived(item)
        assertTrue("след нового обязан появиться", item.obj.id in st.fresh.value)

        st.markSeen(item.obj.id)
        assertTrue("после открытия след снят", item.obj.id !in st.fresh.value)
    }

    @Test
    fun `узнанное на компьютере переживает его рестарт`() {
        val store = FileJournalStore(File(temp.root, "journal"))
        val file = temp.newFile("живучий.txt").apply { writeText("текст") }
        val st = state(
            ActionResult.Done("Нашёл", Findings(metadata = mapOf("entity.phone" to "+380671234567"))),
            store,
        )
        val item = textItem(file, mapOf("name" to "живучий.txt"))
        st.onReceived(item)
        runBlocking { st.runRemoteActionNow("find-in-text", item, budgetMs = 5_000) }

        val reborn = state(ActionResult.Done("не нужен"), store)
        val entry = reborn.journal.value.single()
        assertEquals("+380671234567", entry.meta["entity.phone"])
    }
}
