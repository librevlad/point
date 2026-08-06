package com.point.executors

import com.point.core.flow.capabilities.ArchiveCapability
import com.point.core.flow.ArchiveExtractor
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
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

class ArchiveRealizer @Inject constructor(
    private val archive: ArchiveExtractor,
) : Realizer {
    override val capabilityId = ArchiveCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                // Одна стадия — не украшение (#288): распаковка большого архива идёт секунды и
                // десятки секунд, а разбить её честно нельзя — контракт [ArchiveExtractor] отдаёт
                // весь каталог одним вызовом и о своём ходе ничего не сообщает. Назвать работу,
                // которая правда идёт, — уже правда; выдумывать внутри неё шаги мы не станем.
                reportStage("Распаковываю архив")
                val dir = archive.extract(input)
                val count = File(dir.value).walkTopDown().count { it.isFile }
                if (count == 0) {
                    ActionResult.Failure("Пустой или неподдерживаемый архив", recoverable = true)
                } else {
                    // A first-class collection object — the flow continues on it.
                    ActionResult.Success(
                        ResultObject(
                            ObjectKind.COLLECTION,
                            "inode/directory",
                            dir,
                            mapOf("op" to "unpack", "count" to count.toString()),
                        ),
                    )
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка распаковки", recoverable = true) }
        }
}
