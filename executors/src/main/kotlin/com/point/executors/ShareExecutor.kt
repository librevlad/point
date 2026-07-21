package com.point.executors

import com.point.core.flow.Executor
import com.point.core.flow.Sharer
import com.point.core.model.ExecutorId
import com.point.core.model.ExecutorResult
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import javax.inject.Inject

/** Any object -> system Share sheet. Terminal (no new object). */
class ShareExecutor @Inject constructor(
    private val sharer: Sharer,
) : Executor {
    override val id = ExecutorId("share")
    override val order = 80
    override val icon = "share"
    override fun title(state: ObjectState) = "Поделиться"
    override fun accepts(state: ObjectState) = true
    override fun produces(state: ObjectState) = state

    override suspend fun execute(input: PointObject, amendment: String?): ExecutorResult =
        runCatching {
            sharer.share(input)
            ExecutorResult.Done("Открыт диалог «Поделиться»")
        }.getOrElse { ExecutorResult.Failure(it.message ?: "Не удалось поделиться", recoverable = true) }
}
