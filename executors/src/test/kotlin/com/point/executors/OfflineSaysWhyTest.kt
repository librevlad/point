package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.NetworkAvailability
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Без сети сетевое действие называет причину вместо обещания (#569).
 *
 * Прятать его нельзя: человек видит своё положение до тапа, а не после тридцати секунд
 * ожидания. И про местное не говорится ничего лишнего — оно работает как работало.
 */
class OfflineSaysWhyTest {

    private val state = ObjectState(ObjectKind.IMAGE)

    private fun cap(id: String, network: Boolean) = object : Capability {
        override val id = CapabilityId(id)
        override val icon = id
        override val meta = CapabilityMeta(network = network)
        override fun label(state: ObjectState) = id
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = state
    }

    private fun registry(online: Boolean) = DefaultCapabilityRegistry(
        capabilities = setOf(cap("understand", network = true), cap("scan", network = false)),
        policy = object : com.point.core.flow.BubblePolicy {
            override fun rank(state: ObjectState, candidates: List<Capability>) = candidates
        },
        network = NetworkAvailability { online },
    )

    @Test fun `без сети сетевое действие называет причину`() {
        val bubbles = registry(online = false).bubblesFor(state)

        val understand = bubbles.first { it.capabilityId == CapabilityId("understand") }
        assertEquals("нет интернета", understand.unusableReason)
    }

    @Test fun `без сети действие остаётся на месте, а не прячется`() {
        val bubbles = registry(online = false).bubblesFor(state)

        assertTrue("сетевое действие исчезло с экрана", bubbles.any { it.capabilityId == CapabilityId("understand") })
    }

    @Test fun `местное действие без сети молчит — оно работает как работало`() {
        val bubbles = registry(online = false).bubblesFor(state)

        val scan = bubbles.first { it.capabilityId == CapabilityId("scan") }
        assertNull("местному действию приписали чужую беду", scan.unusableReason)
    }

    @Test fun `сеть есть — причин нет ни у кого`() {
        val bubbles = registry(online = true).bubblesFor(state)

        assertTrue(bubbles.all { it.unusableReason == null })
    }
}
