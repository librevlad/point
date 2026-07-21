package com.point.executors

import com.point.core.flow.ArchiveExtractor
import com.point.core.flow.Capability
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import javax.inject.Inject

/** Archive (zip/tar/gz/bz2/xz/7z/rar) -> unpacked contents in scratch. */
class ArchiveCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "unzip"
    override fun label(state: ObjectState) = "Распаковать"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.ZIP
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.UNKNOWN)

    companion object { val ID = CapabilityId("archive") }
}

class ArchiveRealizer @Inject constructor(
    private val archive: ArchiveExtractor,
) : Realizer {
    override val capabilityId = ArchiveCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            val count = archive.extract(input)
            if (count == 0) {
                ActionResult.Failure("Пустой или неподдерживаемый архив", recoverable = true)
            } else {
                ActionResult.Done("Распаковано файлов: $count")
            }
        }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка распаковки", recoverable = true) }
}
