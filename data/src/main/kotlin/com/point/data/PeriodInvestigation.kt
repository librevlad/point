package com.point.data

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.Findings
import com.point.core.flow.SpreadsheetReader
import com.point.core.flow.readPeriod
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import javax.inject.Inject

class PeriodInvestigation @Inject constructor() : Capability {

    override val id = ID

    override val icon = ""

    override val meta = CapabilityMeta(
        investigation = true,
        latency = Latency.SLOW,
        mayYield = setOf(Feature.HAS_PERIOD),
    )

    override fun label(state: ObjectState) = "Смотрю документ…"

    override fun accepts(state: ObjectState) = state.kind == ObjectKind.OFFICE

    override fun produces(state: ObjectState) = state

    companion object {

        val ID = com.point.core.model.CapabilityId("period")
    }
}

class PeriodInvestigationRealizer @Inject constructor(
    private val sheets: SpreadsheetReader,
) : Realizer {

    override val capabilityId = PeriodInvestigation.ID

    override val meta = com.point.core.flow.RealizerMeta(actor = "period-rules")

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        com.point.core.flow.investigated { findings(input) }

    private suspend fun findings(obj: PointObject): Findings {

        // Период ищется на каждом листе книги (#1417): у книг владельца первый лист часто шапка
        // или шаблон, и «периода нет» по нему было ответом за листы, которых не открывали
        // (`not investigated`, а не `not found`). Найденный лист называется — по нему и продлевают.
        val sheet = sheets.readSheets(obj).firstOrNull { readPeriod(it.rows) != null } ?: return Findings()
        return Findings(
            features = setOf(Feature.HAS_PERIOD),
            metadata = if (sheet.name.isBlank()) emptyMap() else mapOf(com.point.core.flow.META_PERIOD_SHEET to sheet.name),
        )
    }
}

