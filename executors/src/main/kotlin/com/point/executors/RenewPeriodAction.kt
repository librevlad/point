package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.DocumentPeriod
import com.point.core.flow.Latency
import com.point.core.flow.META_SEMANTIC_SUMMARY
import com.point.core.flow.Realizer
import com.point.core.flow.RenewedTable
import com.point.core.flow.SpreadsheetReader
import com.point.core.flow.SpreadsheetWriter
import com.point.core.flow.renewPeriod
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
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
import javax.inject.Inject

/**
 * Таблица за прошлый период → такая же таблица на следующий (#224).
 *
 * Пузырёк появляется **только там, где он осмыслен**: признак [Feature.HAS_PERIOD] зажигается,
 * когда в таблице действительно прочитан календарь дат (`PeriodEnricher`). Пока периода нет,
 * действия нет вовсе — предложить «продлить» документ, про период которого мы ничего не знаем,
 * значит пообещать выдумку.
 *
 * Работа целиком на устройстве: ни сети, ни ключа, ни модели — таблицу мы уже прочитали, а
 * сдвиг дат и очистка заполняемого считаются по ней самой.
 */
class RenewPeriodCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "renew"

    /** Разбор xlsx идёт секунды на большом файле — те же слова и та же честность, что у
     *  «Извлечь текст» над документом. */
    override val meta = CapabilityMeta(latency = Latency.FAST)
    override fun label(state: ObjectState) = "На новый период"
    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.OFFICE && state.has(Feature.HAS_PERIOD)

    override fun produces(state: ObjectState) = ObjectState(ObjectKind.OFFICE)

    companion object { val ID = CapabilityId("renew-period") }
}

class RenewPeriodRealizer @Inject constructor(
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
                // Признак мог загореться на другой копии таблицы (или устареть) — тогда честный
                // отказ, а не бланк, собранный неизвестно за какой период. Неисправимо: второй
                // тап прочитает ту же таблицу и не найдёт в ней того же.
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

/**
 * Что действие сделало — одной строкой, продуктовым языком (#224).
 *
 * Очищенные столбцы называются поимённо и **вместе с правилом**, по которому их выбрали. Иначе
 * человек получает бланк, из которого молча пропала графа: стирание, о котором он узнаёт по
 * расхождению с бумагой, — это потеря, а не работа.
 *
 * Порядок слов выбран под обрезку: подпись объекта показывается двумя строками, поэтому первым
 * стоит новый период, вторым — что стёрто, и только потом остальное.
 */
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

/** Больше трёх названий в подписи не читаются — остальные считаются числом. */
private fun names(columns: List<String>): String =
    if (columns.size <= MAX_NAMED_COLUMNS) columns.joinToString(", ")
    else columns.take(MAX_NAMED_COLUMNS).joinToString(", ") + " и ещё ${columns.size - MAX_NAMED_COLUMNS}"

private const val MAX_NAMED_COLUMNS = 3

private fun fileStamp(period: DocumentPeriod): String =
    "${human(period.from)}-${human(period.to)}"

private fun human(date: LocalDate): String = date.format(HUMAN_DATE)

/** Прошлый период — без года: он тот же, что у нового, и место в подписи дороже повтора. */
private fun short(date: LocalDate): String = date.format(SHORT_DATE)

private val HUMAN_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")
