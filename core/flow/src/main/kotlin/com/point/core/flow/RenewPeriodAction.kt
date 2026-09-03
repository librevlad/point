package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class RenewPeriodCapability : Capability {
    override val id = ID
    override val icon = "renew"

    override val meta = CapabilityMeta(latency = Latency.FAST)
    override fun label(state: ObjectState) = "На новый период"
    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.OFFICE && state.has(Feature.HAS_PERIOD)

    override fun produces(state: ObjectState) = ObjectState(ObjectKind.OFFICE)

    override fun yields(state: ObjectState) = ActionYield.New(ObjectKind.OFFICE, "таблицу")

    companion object { val ID = CapabilityId("renew-period") }
}

class RenewPeriodRealizer(
    private val sheets: SpreadsheetReader,
    private val writer: SpreadsheetWriter,
) : Realizer {

    override val capabilityId = RenewPeriodCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage(OFFICE_READ_STAGE)
                val rows = sheets.readRows(input)
                val renewed = renewPeriod(rows)

                    ?: return@runCatching ActionResult.Failure(
                        "В таблице нет столбца с датами подряд — продлевать нечего",
                        recoverable = false,
                    )
                reportStage("Собираю бланк")
                val ref = writer.write(renewed.rows)
                ActionResult.Success(
                    ResultObject(
                        ObjectKind.OFFICE,
                        XLSX_MIME,
                        ref,
                        mapOf(
                            "op" to "renew-period",
                            "name" to "бланк ${fileStamp(renewed.period)}.xlsx",
                            "rows" to renewed.rows.size.toString(),
                            "shifted" to renewed.shifted.toString(),
                            META_SEMANTIC_SUMMARY to renewalSummary(renewed),
                        ),
                    ),
                )
            }.getOrElse {
                ActionResult.Failure(it.message ?: "Не удалось продлить таблицу", recoverable = true)
            }
        }

    private companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}

internal fun renewalSummary(renewed: RenewedTable): String = buildString {
    append("Бланк на ").append(human(renewed.period.from)).append(" – ").append(human(renewed.period.to))
    append(" (был ").append(short(renewed.previous.from)).append(" – ")
    append(short(renewed.previous.to)).append(")")
    if (renewed.cleared.isNotEmpty()) {
        append(" · очищено, у каждой даты своё: ").append(names(renewed.cleared))
    }
    if (renewed.kept.isNotEmpty()) {
        append(" · оставлено: ").append(names(renewed.kept))
    }
}

private fun names(columns: List<String>): String =
    if (columns.size <= MAX_NAMED_COLUMNS) columns.joinToString(", ")
    else columns.take(MAX_NAMED_COLUMNS).joinToString(", ") + " и ещё ${columns.size - MAX_NAMED_COLUMNS}"

private const val MAX_NAMED_COLUMNS = 3

private fun fileStamp(period: DocumentPeriod): String =
    "${human(period.from)}-${human(period.to)}"

private fun human(date: LocalDate): String = date.format(HUMAN_DATE)

private fun short(date: LocalDate): String = date.format(SHORT_DATE)

private val HUMAN_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")
