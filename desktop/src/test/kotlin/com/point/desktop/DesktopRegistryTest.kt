package com.point.desktop

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopRegistryTest {

    private val registry = DesktopRegistry(
        setOf(PcOpenCapability(), PcRevealCapability(), PcCopyCapability(), PcSaveAsCapability()),
    )

    @Test
    fun `an image gets open, copy, reveal and save — in priority order`() {

        assertEquals(
            listOf("Открыть", "Копировать картинку", "Показать в папке", "Сохранить в…"),
            registry.bubblesFor(ObjectState(ObjectKind.IMAGE)).map { it.title },
        )
    }

    @Test
    fun `text additionally gets copy, ranked between open and reveal`() {
        assertEquals(
            listOf("Открыть", "Копировать", "Показать в папке", "Сохранить в…"),
            registry.bubblesFor(ObjectState(ObjectKind.TEXT)).map { it.title },
        )
    }

    // ---- Этап 6: Intent участвует и в desktop-ранжировании (ADR-0001 §14) ----

    private class Declared(
        id: String,
        priority: Int,
        private val serves: Set<com.point.core.model.Intent>,
    ) : com.point.core.flow.Capability {
        override val id = com.point.core.model.CapabilityId(id)
        override val icon = ""
        override val meta = com.point.core.flow.CapabilityMeta(priority = priority)
        override fun label(state: ObjectState) = id.value
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = state
        override fun intents(state: ObjectState) = serves
    }

    private val send10 = Declared("send-a", priority = 10, serves = setOf(com.point.core.model.Intent.SEND))
    private val open20 = Declared("open-b", priority = 20, serves = setOf(com.point.core.model.Intent.OPEN))
    private val open30 = Declared("open-c", priority = 30, serves = setOf(com.point.core.model.Intent.OPEN))

    private val mixed = DesktopRegistry(setOf(send10, open20, open30))

    private fun graphWith(intent: com.point.core.model.Intent?) = com.point.core.flow.GraphState(
        com.point.core.model.PointObject(
            "obj", "image/png", com.point.core.model.ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE),
        ),
        intent = intent,
    )

    @Test
    fun `a matching intent lifts its capabilities above the rest`() {
        val titles = mixed.bubblesFor(graphWith(com.point.core.model.Intent.OPEN)).map { it.capabilityId.value }

        assertEquals(listOf("open-b", "open-c", "send-a"), titles)
    }

    @Test
    fun `a non-matching capability stays in the list`() {
        val titles = mixed.bubblesFor(graphWith(com.point.core.model.Intent.OPEN)).map { it.capabilityId.value }

        assertEquals("Intent не убирает действия", true, titles.contains("send-a"))
    }

    @Test
    fun `without an intent the old desktop order holds`() {
        val plain = mixed.bubblesFor(ObjectState(ObjectKind.IMAGE)).map { it.capabilityId.value }
        val nullIntent = mixed.bubblesFor(graphWith(null)).map { it.capabilityId.value }

        assertEquals(listOf("send-a", "open-b", "open-c"), plain)
        assertEquals(plain, nullIntent)
    }

    @Test
    fun `capabilities sharing the intent keep their relative priority`() {
        val titles = mixed.bubblesFor(graphWith(com.point.core.model.Intent.OPEN)).map { it.capabilityId.value }

        assertEquals("равные по смыслу — по прежнему priority", listOf("open-b", "open-c"), titles.take(2))
    }

    // ---- Фаза A редизайна: двери-обманки исчезают, отказ исполнителя честен ----

    private class OfficeOnlyRealizer : com.point.core.flow.Realizer {
        override val capabilityId = com.point.core.model.CapabilityId("pdf")
        override fun accepts(state: ObjectState) = state.kind == ObjectKind.OFFICE
        override fun unavailableReason() = "В PDF на компьютере превращаются только документы"
        override suspend fun perform(
            input: com.point.core.model.PointObject,
            amendment: String?,
        ): com.point.core.model.ActionResult = com.point.core.model.ActionResult.Done("ок")
    }

    @Test
    fun `дверь без исполнителя под этот объект не показывается`() {
        // Живой аналог: «В PDF» на картинке всегда падало — capability шире реализатора.
        val wide = Declared("pdf", priority = 5, serves = emptySet())
        val resolver = DesktopResolver(setOf(OfficeOnlyRealizer()))
        val honest = DesktopRegistry(setOf(wide), runnable = resolver::canRun)

        assertEquals(
            emptyList<String>(),
            honest.bubblesFor(ObjectState(ObjectKind.IMAGE)).map { it.capabilityId.value },
        )
        assertEquals(
            listOf("pdf"),
            honest.bubblesFor(ObjectState(ObjectKind.OFFICE)).map { it.capabilityId.value },
        )
    }

    @Test
    fun `нет исполнителя — честная причина, а не первый попавшийся`() {
        val resolver = DesktopResolver(setOf(OfficeOnlyRealizer()))

        val thrown = runCatching {
            resolver.realizerFor(com.point.core.model.CapabilityId("pdf"), ObjectState(ObjectKind.IMAGE))
        }.exceptionOrNull()

        assertEquals(
            "В PDF на компьютере превращаются только документы",
            (thrown as NoWayHere).why,
        )
    }
}
