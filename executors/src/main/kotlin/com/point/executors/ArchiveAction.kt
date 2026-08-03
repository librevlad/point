package com.point.executors

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

/** Archive (zip/tar/gz/bz2/xz/7z/rar) -> a COLLECTION of the unpacked files. */
class ArchiveCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "unzip"

    /** Не [Latency.INSTANT] (#288): большой архив распаковывается секунды и десятки секунд — та же
     *  правка и по той же причине, что у «Страницы». Работа растёт с содержимым архива, поэтому
     *  и не [Latency.SLOW]: она рассказывает о себе на объекте, а не забирает экран. */
    override val meta = CapabilityMeta(latency = Latency.FAST)
    override fun label(state: ObjectState) = "Распаковать"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.ZIP
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.COLLECTION)

    companion object { val ID = CapabilityId("archive") }
}

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
