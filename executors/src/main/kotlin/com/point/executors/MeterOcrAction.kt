package com.point.executors

import com.point.core.flow.MeterReader
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Чтение табло прибора — **среднее звено** цепочки «Распознать текст» (#262).
 *
 * Порядок цепочки и есть весь смысл: [DeviceOcrRealizer] (10, местный) читает страницу целиком и
 * остаётся первым; сюда (50, местный) доходят, только когда он вернул шум; [CloudOcrRealizer]
 * (90, облачный) — последним, за явным согласием, потому что уводит объект с устройства.
 *
 * Так обычное чтение не подменяется: на скриншоте переписки или на чеке первое звено справляется,
 * и до прибора очередь не доходит вовсе. А на фото счётчика — где замер корпуса намерил ровно
 * ноль из трёх — между «движок не смог» и «плати и отправляй фотографию своего двора в чужой
 * сервис» появляется бесплатный офлайновый шаг.
 *
 * **Отказ здесь честный и разный.** «Табло не нашли» и «нашли, но цифр не собралось» — две разные
 * новости: в первом случае кадр, скорее всего, не про прибор, во втором стоит переснять без
 * блика. Обе — `recoverable`, то есть цепочка идёт дальше, а человек видит причину, а не пустоту.
 */
class MeterOcrRealizer @Inject constructor(
    private val store: ObjectStore,
    private val reader: MeterReader,
) : Realizer {
    override val capabilityId = OcrCapability.ID
    override val meta = RealizerMeta(priority = 50, kind = RealizerKind.LOCAL)

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
