package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.Resolver
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Аудит 2026-08-09, блок 2.3: телефонные действия всегда стояли хвостом — порядок
 * отражал архитектуру (чей реестр), а не пользу (P10). Единый список: свои и
 * телефонные ранжируются вместе; недоступное видно с причиной (PC5).
 */
class UnifiedActionsTest {

    private class Declared(id: String, priority: Int) : Capability {
        override val id = CapabilityId(id)
        override val icon = ""
        override val meta = CapabilityMeta(priority = priority)
        override fun label(state: ObjectState) = id.value
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = state
    }

    /**
     * Механика включена намеренно (#785): сегодня телефон просьбы не исполняет, и все
     * его действия закрыты одной причиной. Правило же — «свои и чужие ранжируются вместе,
     * недоступное видно с причиной» — переживёт этот день, и проверять его надо на живой
     * механике, а не на выключенной.
     */
    private fun state(vararg mine: Pair<String, Int>): DesktopState {
        val caps = mine.map { (id, p) -> Declared(id, p) }.toSet<Capability>()
        return DesktopState(
            DesktopRegistry(caps),
            object : Resolver {
                override fun realizerFor(capabilityId: CapabilityId) = error("не нужен")
            },
            clipboard = { },
            phoneRunsRequests = true,
        )
    }

    private fun item() = InboxItem(
        PointObject("o", "text/plain", ScratchRef("/tmp/т.txt"), ObjectState(ObjectKind.TEXT)),
    )

    @Test
    fun `свои и телефонные действия ранжируются вместе — по пользе, а не по реестру`() {
        val st = state("pc-open" to 50, "pc-print" to 90)
        st.setPhoneCaps(listOf(PcRemoteAction("call", "Позвонить", priority = 10)))

        val titles = st.actionsFor(item()).map { it.title }

        assertEquals(listOf("Позвонить", "pc-open", "pc-print"), titles)
    }

    @Test
    fun `недоступное телефонное видно с причиной, а не скрыто — и стоит после доступных`() {
        val st = state("pc-open" to 50)
        st.setPhoneCaps(
            listOf(
                PcRemoteAction("print", "Напечатать", unavailable = "на телефоне нет принтера", priority = 5),
                PcRemoteAction("call", "Позвонить", priority = 10),
            ),
        )

        val actions = st.actionsFor(item())

        assertEquals(listOf("Позвонить", "pc-open", "Напечатать"), actions.map { it.title })
        assertEquals("на телефоне нет принтера", actions.last().unavailable)
    }

    /** #1174: голый текст получал телефонное «Открыть ссылку» — двери слились во всеядную. */
    @Test
    fun `двери одного умения не пускают негодный объект и не двоятся на экране`() {
        val st = state("pc-open" to 50)
        st.setPhoneCaps(
            listOf(
                PcRemoteAction("browse", "browse", kinds = setOf("URL"), priority = 10),
                PcRemoteAction("browse", "browse", features = setOf("HAS_URL"), priority = 10),
            ),
        )

        assertEquals(listOf("pc-open"), st.actionsFor(item()).map { it.title })

        val withUrl = InboxItem(
            PointObject(
                "o",
                "text/plain",
                ScratchRef("/tmp/т.txt"),
                ObjectState(ObjectKind.TEXT, setOf(com.point.core.model.Feature.HAS_URL)),
            ),
        )
        assertEquals(listOf("browse", "pc-open"), st.actionsFor(withUrl).map { it.title })
    }

    @Test
    fun `телефонное действие помечено стороной исполнения`() {
        val st = state("pc-open" to 50)
        st.setPhoneCaps(listOf(PcRemoteAction("call", "Позвонить", priority = 10)))

        val byTitle = st.actionsFor(item()).associateBy { it.title }

        assertEquals(true, byTitle["Позвонить"]!!.onPhone)
        assertEquals(false, byTitle["pc-open"]!!.onPhone)
    }
}
