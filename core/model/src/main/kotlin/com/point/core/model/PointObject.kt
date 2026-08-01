package com.point.core.model

/**
 * An in-flight object, living as its own copy inside the private scratch store.
 *
 * Every step operates on this copy (never on the original Share `content://` Uri,
 * whose read grant dies with the receiving Activity). Cleared when the flow ends.
 *
 * @param uri reference to this object's copy (an [ObjectRef]; local scratch today).
 * @param confidence **устарело** — см. [provenance] (#264).
 * @param provenance откуда взялось значение этого объекта (#264): ПРОЧИТАНО со страницы,
 *   ВЫВЕДЕНО правилом, ПРОЧИТАНО МОДЕЛЬЮ, ПОДТВЕРЖДЕНО ЧЕЛОВЕКОМ — либо [Provenance.GIVEN]
 *   у файла, который человек просто принёс и который никто не читал.
 *
 *   Поле — **типизированная проекция** аннотации `<key>.src` собственного факта узла, а не
 *   второй источник истины: единственный законный способ его выставить — прочитать из среза
 *   метаданных самого узла (`provenanceOf(metadata, key)` в `:core:flow`). Поэтому происхождение
 *   переживает журнал бесплатно — метаданные журналируются целиком, а объекты графа не
 *   журналируются вовсе и пересобираются энричерами из тех же метаданных.
 * @param sourceObjects ids this object was derived from — the provenance edge of the graph.
 *   Empty for the object the user shared.
 * @param creatorAction which extractor or capability produced it, so a wrong reading can be
 *   traced back to the component that made it.
 */
data class PointObject(
    val id: String,
    val mime: String,
    val uri: ObjectRef,
    val state: ObjectState,
    val metadata: Map<String, String> = emptyMap(),
    @Deprecated(
        "Происхождение вместо уверенности: Provenance (#264). Одно число несравнимо между " +
            "ридерами, а экран всё равно переводил любое <1f в одно «возможно». Удалить после " +
            "мержа веток, живущих на этом поле.",
        ReplaceWith("provenance"),
    )
    val confidence: Float = 1f,
    val provenance: Provenance = Provenance.GIVEN,
    val sourceObjects: List<String> = emptyList(),
    val creatorAction: String? = null,
)
