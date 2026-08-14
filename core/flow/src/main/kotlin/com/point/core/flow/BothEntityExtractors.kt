package com.point.core.flow

/**
 * Два способа искать значения в тексте — и оба работают (#934).
 *
 * Один и тот же счёт: компьютер находил в нём телефон `067 636 05 60`, телефон — нет.
 * Причина не в умении, а в том, что на Android поиск значений был отдан **только** движку
 * сущностей: он понимает язык, но украинский местный номер за телефон не считает. Правила,
 * которыми ищет компьютер, лежали рядом в общем ядре и на телефоне не звались.
 *
 * Здесь они складываются. Первым идёт тот, кто богаче: у движка сущностей значение приходит
 * со строкой документа вокруг него. Правила добавляют то, чего он не увидел. Одно и то же
 * значение дважды не приходит.
 */
class BothEntityExtractors(
    private val extractors: List<EntityExtractor>,
) : EntityExtractor {

    override suspend fun extract(text: String): List<Entity> = extractors
        .flatMap { runCatching { it.extract(text) }.getOrDefault(emptyList()) }
        .distinctBy { it.type to normConsensus(it.value) }
}
