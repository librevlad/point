package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Exporter
import com.point.core.flow.Latency
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

class SaveAllCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "save-all"

    override val meta = CapabilityMeta(latency = Latency.FAST)
    override fun label(state: ObjectState) = "Сохранить всё"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.COLLECTION
    override fun produces(state: ObjectState) = state

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
