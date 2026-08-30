package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.model.ActionResult
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * «Отменить» на компьютере отменяет ту работу, которая показана (#1319).
 *
 * Кнопка стоит над идущей работой независимо от того, кто её начал: рука за этим
 * компьютером или просьба телефона. Пока признак работы и её отменяемость были двумя
 * полями, над просьбой соседа кнопка обещала и не делала ничего — человек считал, что
 * отменил, уходил, а просьба доводилась до конца и уезжала исходом на телефон (#1073).
 */
class CancelStopsShownWorkTest {

    @get:Rule val temp = TemporaryFolder()

    private class Says(id: String) : Capability {
        override val id = CapabilityId(id)
        override val icon = "x"
        override val meta = CapabilityMeta()
        override fun label(state: ObjectState) = id.value
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
    }

    /** Работа, которая сама не кончится: её прекращает только человек. */
    private class Endless(id: String) : Realizer {
        override val capabilityId = CapabilityId(id)

        @Volatile var cancelled = false

        @Volatile var finished = false

        override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
            try {
                kotlinx.coroutines.delay(20_000)
            } catch (stopped: CancellationException) {
                cancelled = true
                throw stopped
            }
            finished = true
            return ActionResult.Done("сделано")
        }
    }

    private class Pick(private val byId: Map<String, Realizer>) : Resolver {
        override fun realizerFor(capabilityId: CapabilityId): Realizer = byId.getValue(capabilityId.value)
    }

    private fun waitUntil(timeoutMs: Long = 5_000, what: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!what() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("не дождались условия за ${timeoutMs}мс", what())
    }

    private fun pc(work: Endless, outbox: Outbox? = null) = DesktopState(
        DesktopRegistry(setOf(Says(work.capabilityId.value))),
        Pick(mapOf(work.capabilityId.value to work)),
        clipboard = { },
        outbox = outbox,
    )

    private fun arrived(origin: String? = null): InboxItem {
        val file = File(temp.newFolder(), "договор.jpg").apply { writeText("байты") }
        return InboxItem(
            PointObject(
                "здесь", "image/jpeg", ScratchRef(file.absolutePath), ObjectState(ObjectKind.IMAGE),
                metadata = origin?.let { mapOf(com.point.core.flow.META_ORIGIN_ID to it) }.orEmpty(),
            ),
        )
    }

    /**
     * Путь человека: телефон попросил, компьютер взялся и показал работу, человек нажал
     * «Отменить». Работа прекращена, а просьба вернулась соседу отказом — тем же путём,
     * каким доезжает любой другой исход (#1073), а не тишиной с вечным «ещё работаю».
     */
    @Test
    fun `отмена прекращает работу по просьбе телефона и возвращает соседу отказ`() {
        val outbox = Outbox(temp.newFolder("исход"))
        val work = Endless("read")
        val state = pc(work, outbox)

        val answer = state.runRemoteActionNow("read", arrived(origin = "phone-obj"), budgetMs = 50)

        assertEquals(com.point.core.flow.PC_STILL_WORKING, (answer as ActionResult.Done).message)
        waitUntil { state.working.value != null }

        state.cancelWork()

        waitUntil { work.cancelled }
        waitUntil { state.working.value == null }
        assertFalse("отменённая работа доведена до конца", work.finished)

        waitUntil { outbox.entries().isNotEmpty() }
        val entry = outbox.entries().single()
        val f = com.point.core.flow.PcResultFields
        assertEquals(
            "отменённая просьба вернулась телефону не отказом",
            com.point.core.flow.PcActionOutcome.Failed(com.point.core.flow.PC_CANCELLED),
            f.outcomeOf(entry.meta),
        )
        assertEquals(
            "исход едет к объекту телефона, а не к копии на компьютере",
            "phone-obj",
            entry.meta[com.point.core.flow.PcExecFields.HOME],
        )
        assertNull("отменённая работа не рождала объекта", outbox.file(entry.id))
    }

    /** Нажатое на самом компьютере отменяется тем же путём — отдельного поля для него нет. */
    @Test
    fun `отмена прекращает работу, начатую кликом на компьютере`() {
        val work = Endless("read")
        val state = pc(work)
        val item = arrived()

        state.onBubble(item, Bubble("x", "Прочитать", CapabilityId("read"), ObjectState(ObjectKind.TEXT)))
        waitUntil { state.working.value != null }

        state.cancelWork()

        waitUntil { work.cancelled }
        assertFalse("отменённая работа доведена до конца", work.finished)
    }
}
