package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.flow.MeterReader
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Чтение табло прибора — **отдельное действие за явным тапом**, а не звено цепочки
 * «Распознать текст» (#262).
 *
 * **Почему отдельное — это измерено, а не вкус.** Первая версия среза поставила чтение прибора
 * средним звеном: страница (10) → табло (50) → облако (90). Прогон поиска по всем 23 кадрам
 * корпуса показал, чем это кончается:
 *
 * - место, «похожее на табло», находится на **22 кадрах из 23** — в том числе логотип
 *   «monobank» на квитанции, строка письма, ряд дат в рукописной ведомости и гравий во дворе;
 * - `score` при этом **не разделяет**: фото накладной (0,196) и рукописная ведомость (0,194)
 *   стоят выше любого из трёх настоящих счётчиков (0,178 / 0,117 / 0,115);
 * - судить прочитанным тоже нечем: движку разрешены **только цифры**, поэтому на строке букв он
 *   выдаёт цифры, и проверка «собралось ≥ 3 цифр» пропускает всё.
 *
 * Внутри цепочки это значит вот что: до чтения прибора очередь доходит ровно тогда, когда
 * страницу прочитать не удалось, то есть на сфотографированном документе, — и там оно вернуло бы
 * `Success` с выдуманным числом, а облако, единственное звено, которое такой документ читает, не
 * запустилось бы вовсе. Человек попросил распознать письмо и получил бы «0100801» без единого
 * слова о том, что это догадка. Это ровно то, чего инварианты Point не разрешают: неуверенность
 * сглажена, догадка выдана за прочитанное.
 *
 * **Решает человек** — он один и знает, что на снимке прибор. Тап по «Прочитать показание»
 * бесплатен, офлайновый и объект с устройства не уводит; «Распознать текст» при этом работает
 * ровно как раньше (страница → облако), то есть соседние кадры правка не трогает. Порода та же,
 * что у `CloudOcrCapability`: где машина решить не может, спрашивают не эвристику, а человека.
 */
class MeterOcrCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "meter"

    /** Местное и бесплатное, но не мгновенное: перебор наклонов и до трёх проходов движка. */
    override val meta = CapabilityMeta(priority = 60, cost = Cost.FREE, latency = Latency.SLOW)
    override fun label(state: ObjectState) = "Прочитать показание"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    companion object { val ID = CapabilityId("meter-ocr") }
}

/**
 * Как читается показание: найти табло, вырезать, довернуть, увеличить, читать только цифры
 * (`MeterReader` → `TesseractMeterReader`). Всё офлайн и бесплатно, снимок с устройства не уходит.
 *
 * **Отказ честный и разный.** «Табло не нашли» и «нашли, но цифр не собралось» — две разные
 * новости, и обе `recoverable`: человек видит причину, а рядом остаются прежние пузырьки, включая
 * облачное чтение.
 */
class MeterOcrRealizer @Inject constructor(
    private val store: ObjectStore,
    private val reader: MeterReader,
) : Realizer {
    override val capabilityId = MeterOcrCapability.ID
    override val meta = RealizerMeta(priority = 10, kind = RealizerKind.LOCAL)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            reportStage("Ищу табло прибора")
            val readout = runCatching { reader.read(input) }.getOrElse {
                return@withContext ActionResult.Failure(
                    it.message ?: "Табло прибора прочитать не удалось",
                    recoverable = true,
                )
            }
            if (readout.nothingFound) {
                return@withContext ActionResult.Failure(NO_DISPLAY, recoverable = true)
            }
            if (readout.foundButUnread) {
                return@withContext ActionResult.Failure(UNREADABLE, recoverable = true)
            }
            // Показание уходит дословно, с ведущими нулями барабана: сколько разрядов значащие,
            // знает поставщик услуги, а не Point (#262, meterWithoutDrumZeros).
            val text = readout.displays.joinToString("\n") { it.digits }
            runCatching {
                val ref = store.newScratchFile("txt")
                File(ref.value).writeText(text)
                ActionResult.Success(
                    ResultObject(
                        ObjectKind.TEXT,
                        "text/plain",
                        ref,
                        mapOf("op" to "ocr", "engine" to "on-device", "reader" to "meter"),
                    ),
                )
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка записи результата", recoverable = true) }
        }

    private companion object {
        const val NO_DISPLAY = "Табло прибора на кадре не найдено"
        const val UNREADABLE = "Табло нашлось, но цифры не читаются — снимите ближе и без блика"
    }
}
