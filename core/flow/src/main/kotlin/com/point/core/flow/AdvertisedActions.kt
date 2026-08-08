package com.point.core.flow

import com.point.core.model.ObjectState

fun advertisedActions(
    capabilities: Collection<Capability>,
    probes: List<ObjectState> = inventoryProbes(),
): List<PcRemoteAction> = capabilities
    .filterNot { it.meta.localOnly || it.meta.investigation }
    .sortedWith(compareBy({ it.meta.priority }, { it.id.value }))
    .mapNotNull { capability ->
        val accepted = probes.filter(capability::accepts)
        if (accepted.isEmpty()) return@mapNotNull null
        val kinds = accepted.map { it.kind.name }.distinct().toSet()

        val needsFeature = accepted.none { it.features.isEmpty() }
        val features = if (!needsFeature) {
            emptySet()
        } else {
            probes.filter { it.features.size == 1 && capability.accepts(it) }
                .map { it.features.first().name }.toSet()
        }
        PcRemoteAction(
            id = capability.id.value,

            label = capability.label(accepted.first()),

            kinds = if (kinds.size == ObjectKindCount) emptySet() else kinds,
            features = features,
        )
    }

private val ObjectKindCount: Int get() = com.point.core.model.ObjectKind.entries.size
