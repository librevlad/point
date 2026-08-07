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

class PeriodEnricher @Inject constructor(
    private val sheets: SpreadsheetReader,
) : Enricher {

    override val meta = EnricherMeta(
        cost = EnrichCost.SLOW,
        mayYield = setOf(Feature.HAS_PERIOD),
        label = "Смотрю документ…",
    )

    override fun appliesTo(state: ObjectState) = state.kind == ObjectKind.OFFICE

    override suspend fun enrich(obj: PointObject): EnrichmentDelta {

        val rows = runCatching { sheets.readRows(obj) }.getOrDefault(emptyList())
        readPeriod(rows) ?: return EnrichmentDelta()
        return EnrichmentDelta(features = setOf(Feature.HAS_PERIOD))
    }
}
