package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.Realizer
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val JOB_REPLY_PROMPT =
    "Напиши короткий отклик на эту вакансию: 3-5 предложений, по-деловому и без воды, " +
        "на языке вакансии. Только текст отклика, без пояснений."

/**
 * «Отклик» (#89): a job posting, once recognised by study, offers a ready reply. One
 * optional input — a line about the candidate — then the vacancy and that line travel
 * to the LLM together. Type-gated like «Список покупок»: IS_JOB, nothing else.
 */
class JobReplyCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "reply"
    override val meta = CapabilityMeta(priority = 30, cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)
    override fun label(state: ObjectState) = "Отклик"
    override fun accepts(state: ObjectState) = state.has(Feature.IS_JOB)
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    companion object { val ID = CapabilityId("job-reply") }
}

class JobReplyRealizer @Inject constructor(
    private val llm: LlmClient,
) : Realizer {
    override val capabilityId = JobReplyCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        if (amendment == null) {
            return ActionResult.NeedsInput(
                "Пара слов о вас — опыт, стек (пусто = универсальный отклик)",
                suggestions = emptyList(),
            )
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val vacancy = entitySourceText(input).take(MAX_CHARS)
                if (vacancy.isBlank()) return@withContext ActionResult.Failure("Нет текста вакансии", recoverable = true)
                val about = amendment.takeIf { it.isNotBlank() }
                    ?.let { "\n\nО кандидате: $it" }.orEmpty()
                reportStage("Модель пишет отклик") // #288: сетевое ожидание названо своими словами
                ActionResult.Success(llm.run(input, JOB_REPLY_PROMPT + about + "\n\nВакансия:\n" + vacancy))
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось написать отклик", recoverable = true) }
        }
    }

    private companion object {
        const val MAX_CHARS = 12_000
    }
}
