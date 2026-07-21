package com.point.executors

import com.point.core.flow.Executor
import com.point.core.flow.Exporter
import com.point.core.model.ExecutorId
import com.point.core.model.ExecutorResult
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import javax.inject.Inject

/** Any object -> shared storage (Downloads). Terminal. */
class SaveExecutor @Inject constructor(
    private val exporter: Exporter,
) : Executor {
    override val id = ExecutorId("save")
    override val icon = "save"
    override fun title(state: ObjectState) = "Сохранить"
    override fun accepts(state: ObjectState) = true
    override fun produces(state: ObjectState) = state

    override suspend fun execute(input: PointObject, amendment: String?): ExecutorResult =
        runCatching {
            ExecutorResult.Done("Сохранено: ${exporter.export(input)}")
        }.getOrElse { ExecutorResult.Failure(it.message ?: "Не удалось сохранить", recoverable = true) }
}
