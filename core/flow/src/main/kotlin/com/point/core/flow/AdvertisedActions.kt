package com.point.core.flow

import com.point.core.model.ObjectState

/**
 * Что устройство объявляет второй поверхности (#588).
 *
 * Point — один продукт на два устройства, и общие умения обязаны быть видны и там, и там, пока
 * есть связь. До этого телефон объявлял компьютеру **два действия из сорока семи** — список был
 * записан руками и не рос вместе с продуктом. Новая способность появлялась на телефоне и на
 * второй поверхности не появлялась никогда: про неё просто забывали.
 *
 * Теперь список **выводится**, как выводится Flow Graph: добавил способность — она поехала на
 * вторую поверхность сама. Второго перечня «что мы умеем» в проекте не заводится: место, где он
 * разойдётся с первым, — это место, где человек увидит кнопку, которой нет.
 *
 * Не объявляется ровно два рода действий, и оба помечены [CapabilityMeta.localOnly]: те, что
 * зациклятся (отправить на компьютер — с компьютера), и те, чей смысл в самом устройстве
 * («Открыть» открывает ЗДЕСЬ, у второй поверхности для этого свой двойник).
 */
fun advertisedActions(
    capabilities: Collection<Capability>,
    probes: List<ObjectState> = inventoryProbes(),
): List<PcRemoteAction> = capabilities
    .filterNot { it.meta.localOnly }
    .sortedWith(compareBy({ it.meta.priority }, { it.id.value }))
    .mapNotNull { capability ->
        val accepted = probes.filter(capability::accepts)
        if (accepted.isEmpty()) return@mapNotNull null
        val kinds = accepted.map { it.kind.name }.distinct().toSet()
        PcRemoteAction(
            id = capability.id.value,
            // Имя берётся у первого принятого состояния: у части способностей оно от него зависит
            // («Копировать» / «Копировать картинку»), и человеку на второй поверхности нужно то,
            // которое к его объекту и относится.
            label = capability.label(accepted.first()),
            // Пустой набор видов значит «любой»: если способность принимает всё, перечислять
            // все виды поимённо — это лишний список, который устареет при новом виде объекта.
            kinds = if (kinds.size == ObjectKindCount) emptySet() else kinds,
        )
    }

/** Сколько всего видов объекта знает модель — граница «принимает всё» для [advertisedActions]. */
private val ObjectKindCount: Int get() = com.point.core.model.ObjectKind.entries.size
