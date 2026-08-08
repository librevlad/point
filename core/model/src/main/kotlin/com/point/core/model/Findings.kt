package com.point.core.model

/**
 * Знание, которое шаг принёс в Graph (ADR-0001 §18 — исход «выполнено» покрывает и новое знание).
 *
 * Это не новый примитив: те же самые Objects, Relations, свойства и признаки, которые Graph
 * уже содержит (§2). Тип живёт рядом с [ActionResult], потому что его возвращает Realizer.
 */
data class Findings(
    val features: Set<Feature> = emptySet(),

    val metadata: Map<String, String> = emptyMap(),

    val objects: List<PointObject> = emptyList(),

    val relations: List<Relation> = emptyList(),
) {
    val isEmpty: Boolean
        get() = features.isEmpty() && metadata.isEmpty() && objects.isEmpty() && relations.isEmpty()
}
