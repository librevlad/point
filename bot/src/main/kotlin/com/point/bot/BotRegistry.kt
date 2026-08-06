package com.point.bot

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.LatentBubble
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState

/** The bot's registry over the SAME contracts as phone/desktop — fixed priority order. */
class BotRegistry(private val capabilities: Set<Capability>) : CapabilityRegistry {
    override fun bubblesFor(state: ObjectState): List<Bubble> =
        capabilities
            .filter { it.accepts(state) }
            .sortedWith(compareBy({ it.meta.priority }, { it.id.value }))
            .map { Bubble(it.icon, it.label(state), it.id, it.produces(state) ?: state) }

    override fun all(): Collection<Capability> = capabilities

    override fun intentsFor(state: ObjectState): List<Intent> = emptyList()
    override fun latentBubblesFor(state: ObjectState): List<LatentBubble> = emptyList()
    override fun byId(id: CapabilityId): Capability = capabilities.first { it.id == id }
}

class BotResolver(realizers: Set<Realizer>) : Resolver {
    private val byId = realizers.associateBy { it.capabilityId }
    override fun realizerFor(capabilityId: CapabilityId): Realizer = byId.getValue(capabilityId)
}

/** Friendly Russian name for a kind — the bot's one-line "Понял: …" header. */
internal fun kindLabel(kind: ObjectKind): String = when (kind) {
    ObjectKind.IMAGE -> "Изображение"
    ObjectKind.TEXT -> "Текст"
    ObjectKind.PDF -> "PDF"
    ObjectKind.ZIP -> "Архив"
    ObjectKind.OFFICE -> "Документ"
    ObjectKind.URL -> "Ссылка"
    ObjectKind.COLLECTION -> "Коллекция"
    // Kinds are open (#222): an extraction kind we have no wording for reads as «Объект»,
    // exactly like UNKNOWN.
    else -> "Объект"
}
