package com.point.core.model

/**
 * Кадр журнала — состояние продолжения текущего flow, а не хранилище Graph.
 *
 * Живёт ровно столько, сколько сам journey: пишется при каждом шаге, исчезает при
 * завершении flow вместе со scratch. Восстановление после process death обязано вернуть
 * и найденные объекты со связями — потеря уже полученного знания является дефектом.
 *
 * Focus хранится wire-строками (`region`, `ids`): модель не знает о типах слоя flow;
 * форматы совпадают с `focus.region` / `focus.ids` в metadata.
 */
data class FlowSnapshotFrame(
    val id: String,
    val kind: ObjectKind,
    val mime: String,
    val ref: String,
    val metadata: Map<String, String> = emptyMap(),
    val viaCapabilityId: String? = null,
    val viaTitle: String? = null,

    val found: List<PointObject> = emptyList(),

    val relations: List<Relation> = emptyList(),

    val focusRegion: String? = null,

    val focusIds: String? = null,
)
