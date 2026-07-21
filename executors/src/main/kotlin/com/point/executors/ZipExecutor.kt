package com.point.executors

import com.point.core.flow.ArchiveExtractor
import com.point.core.flow.Executor
import com.point.core.model.ExecutorId
import com.point.core.model.ExecutorResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import javax.inject.Inject

/** Archive (zip/tar/gz/bz2/xz) -> unpacked contents in scratch. Terminal for MVP. */
class ZipExecutor @Inject constructor(
    private val archive: ArchiveExtractor,
) : Executor {
    override val id = ExecutorId("zip")
    override val icon = "unzip"
    override fun title(state: ObjectState) = "Распаковать"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.ZIP
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.UNKNOWN)

    override suspend fun execute(input: PointObject, amendment: String?): ExecutorResult =
        runCatching {
            val count = archive.extract(input)
            if (count == 0) {
                ExecutorResult.Failure("Пустой или неподдерживаемый архив", recoverable = true)
            } else {
                ExecutorResult.Done("Распаковано файлов: $count")
            }
        }.getOrElse { ExecutorResult.Failure(it.message ?: "Ошибка распаковки", recoverable = true) }
}
