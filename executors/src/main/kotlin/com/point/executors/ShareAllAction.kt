package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Realizer
import com.point.core.flow.Sharer
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

class ShareAllCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "share"

    // Шаг кончается системным диалогом «Поделиться»: дальше выбирает человек (#1131).
    override val meta = CapabilityMeta(handsOff = true)
    override fun label(state: ObjectState) = "Поделиться всем"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.COLLECTION
    override fun produces(state: ObjectState) = state

    companion object { val ID = CapabilityId("share-all") }
}

class ShareAllRealizer @Inject constructor(
    private val sharer: Sharer,
) : Realizer {
    override val capabilityId = ShareAllCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val objs = File(input.uri.value).walkTopDown()
                    .filter { it.isFile }
                    .map { fileObject(it) }
                    .toList()
                if (objs.isEmpty()) {
                    ActionResult.Failure("Нечего отправить", recoverable = true)
                } else {
                    sharer.shareAll(objs)
                    ActionResult.Done("Отправка файлов: ${objs.size}")
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось поделиться", recoverable = true) }
        }

    private fun fileObject(file: File) = PointObject(
        id = UUID.randomUUID().toString(),
        mime = "application/octet-stream",
        uri = ScratchRef(file.absolutePath),
        state = ObjectState(ObjectKind.UNKNOWN),
        metadata = mapOf("name" to file.name),
    )
}
