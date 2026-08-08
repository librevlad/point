package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.InvestigationState
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.flow.withInvestigation
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production-wiring Этапа 6/7: Intent на desktop выводится из знания объекта
 * (`investigated.*` в metadata, приехавшей с телефона) через тот же `leadingIntent`
 * и участвует в ранжировании как буст, не фильтр — по пути DesktopState → bubblesFor.
 */
class DesktopIntentWiringTest {

    private class Declared(id: String, priority: Int, private val serves: Set<Intent>) : Capability {
        override val id = CapabilityId(id)
        override val icon = ""
        override val meta = CapabilityMeta(priority = priority)
        override fun label(state: ObjectState) = id.value
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = state
        override fun intents(state: ObjectState) = serves
    }

    private val state = DesktopState(
        DesktopRegistry(
            setOf(
                Declared("understand-it", priority = 90, serves = setOf(Intent.UNDERSTAND)),
                Declared("send-it", priority = 10, serves = setOf(Intent.SEND)),
            ),
        ),
        object : Resolver {
            override fun realizerFor(capabilityId: CapabilityId): Realizer = error("не нужен")
        },
        clipboard = { },
    )

    private fun item(metadata: Map<String, String>) = InboxItem(
        PointObject("obj", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE), metadata),
    )

    @Test
    fun `открытый вопрос с телефона делает понимание уместным и поднимает его`() {

        val asked = withInvestigation(emptyMap(), CapabilityId("qr"), InvestigationState.NOT_FOUND)

        val order = state.bubblesFor(item(asked)).map { it.capabilityId.value }

        assertEquals("смысл выведен из знания и поднял совпадающих", "understand-it", order.first())
        assertTrue("Intent не фильтрует", order.contains("send-it"))
    }

    @Test
    fun `без открытых вопросов порядок прежний — по priority`() {
        val order = state.bubblesFor(item(emptyMap())).map { it.capabilityId.value }

        assertEquals(listOf("send-it", "understand-it"), order)
    }

    @Test
    fun `закрытый вопрос гасит смысл — порядок возвращается`() {
        val answered = withInvestigation(emptyMap(), CapabilityId("qr"), InvestigationState.FOUND)

        val order = state.bubblesFor(item(answered)).map { it.capabilityId.value }

        assertEquals(listOf("send-it", "understand-it"), order)
    }
}
