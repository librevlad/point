package com.point.core.flow

import com.point.core.model.ObjectState

fun advertisedActions(
    capabilities: Collection<Capability>,
    probes: List<ObjectState> = inventoryProbes(),
): List<PcRemoteAction> = capabilities
    .filterNot { it.meta.localOnly || it.meta.investigation }
    .sortedWith(compareBy({ it.meta.priority }, { it.id.value }))
    .flatMap { capability ->
        val accepted = probes.filter(capability::accepts)
        if (accepted.isEmpty()) return@flatMap emptyList<PcRemoteAction>()
        val label = capability.label(accepted.first())

        // Вид и признак — пары, а не независимые списки (#1174). «Открыть ссылку»
        // принимает голый URL и любой объект с HAS_URL; слитые в общие kinds+features
        // эти двери делали объявление всеядным — голый текст получал «Открыть ссылку».
        // Дверь на вид: пусто, если вид принят голым, иначе признаки, открывающие его.
        val doors = accepted.map { it.kind }.distinct().mapNotNull { kind ->
            val bare = capability.accepts(ObjectState(kind))
            val keys = if (bare) {
                emptySet()
            } else {
                probes.filter { it.kind == kind && it.features.size == 1 && capability.accepts(it) }
                    .map { it.features.first().name }.toSet()
            }
            // Вид открывается только сочетанием признаков — такую дверь по проводу не
            // передать честно; лучше не объявить, чем предложить негодному объекту.
            if (!bare && keys.isEmpty()) null else kind.name to keys
        }
        doors.groupBy({ it.second }, { it.first }).map { (features, kinds) ->
            PcRemoteAction(
                id = capability.id.value,
                label = label,
                kinds = if (kinds.size == ObjectKindCount) emptySet() else kinds.toSet(),
                features = features,
                priority = capability.meta.priority,
            )
        }
    }

private val ObjectKindCount: Int get() = com.point.core.model.ObjectKind.entries.size
