package com.point.data

import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
import com.point.core.flow.SpreadsheetReader
import com.point.core.flow.readPeriod
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import javax.inject.Inject

/**
 * Есть ли у этой таблицы период — правилом, на устройстве (#224).
 *
 * Якорь действия «На новый период»: пока в таблице не прочитан календарь дат, пузырька нет
 * вовсе. Проверить это можно только заглянув внутрь файла, а первый экран обязан уложиться в
 * 300 мс без единого чтения, — поэтому здесь обогащение, а не классификация: пузырёк
 * появляется позже, когда разбор действительно нашёл период.
 *
 * Ни ключа, ни сети, ни модели: столбец подряд идущих дат читается по самому листу.
 */
class PeriodEnricher @Inject constructor(
    private val sheets: SpreadsheetReader,
) : Enricher {

    /** Разбор xlsx — это распаковка zip и разбор XML: для волны обогащения это [EnrichCost.SLOW],
     *  и планировщик вправе не звать нас, если признак ничего нового не откроет. */
    override val meta = EnricherMeta(
        cost = EnrichCost.SLOW,
        mayYield = setOf(Feature.HAS_PERIOD),
        label = "Смотрю таблицу…",
    )

    override fun appliesTo(state: ObjectState) = state.kind == ObjectKind.OFFICE

    override suspend fun enrich(obj: PointObject): EnrichmentDelta {
        // Не таблица (docx/pptx) или битый файл — читатель вернёт пусто, и это не сбой:
        // признак просто не зажигается, а действие остаётся невидимым.
        val rows = runCatching { sheets.readRows(obj) }.getOrDefault(emptyList())
        readPeriod(rows) ?: return EnrichmentDelta()
        return EnrichmentDelta(features = setOf(Feature.HAS_PERIOD))
    }
}
