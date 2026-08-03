package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.Exporter
import com.point.core.flow.Realizer
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

/** COLLECTION -> every file saved to shared storage. Terminal. The first
 *  collection-level action; more (share all, OCR all…) plug in the same way.
 *
 *  Стадии (#288): сохранение — не один шаг, а столько шагов, сколько файлов в коллекции, и
 *  каждый из них копирует настоящие мегабайты в хранилище устройства. Двадцать снятых страниц
 *  идут заметные секунды, а раньше человек видел только счётчик времени. Ревью прошлого среза
 *  оставило «Сохранить всё» молчать сознательно и записало это вслух — чтобы «не заметили» не
 *  превратилось в «решили»; здесь решение пересмотрено, потому что счёт по файлам и есть
 *  настоящий ход работы, а не выдуманный чек-лист. */
class SaveAllCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "save-all"
    override fun label(state: ObjectState) = "Сохранить всё"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.COLLECTION
    override fun produces(state: ObjectState) = state // terminal

    companion object { val ID = CapabilityId("save-all") }
}

class SaveAllRealizer @Inject constructor(
    private val exporter: Exporter,
) : Realizer {
    override val capabilityId = SaveAllCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val files = File(input.uri.value).walkTopDown().filter { it.isFile }.toList()
                var saved = 0
                for ((index, file) in files.withIndex()) {
                    // «N из M» считается по файлам, которые берутся в работу, а не по удавшимся:
                    // человек ждёт весь перебор, включая тот файл, который не сохранится. Итог
                    // про удавшиеся скажет ActionResult.Done, и эти два числа честно разные.
                    reportStage("Сохраняю ${index + 1} из ${files.size}")
                    runCatching { exporter.export(fileObject(file)) }.onSuccess { saved++ }
                }
                if (saved == 0) {
                    ActionResult.Failure("Нечего сохранять", recoverable = true)
                } else {
                    ActionResult.Done("Сохранено файлов: $saved")
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка сохранения", recoverable = true) }
        }

    private fun fileObject(file: File) = PointObject(
        id = UUID.randomUUID().toString(),
        mime = "application/octet-stream",
        uri = ScratchRef(file.absolutePath),
        state = ObjectState(ObjectKind.UNKNOWN),
        metadata = mapOf("name" to file.name),
    )
}
