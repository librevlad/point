package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val JOB_REPLY_PROMPT =
    "Напиши короткий отклик на эту вакансию: 3-5 предложений, по-деловому и без воды, " +
        "на языке вакансии. Только текст отклика, без пояснений."

class JobReplyCapability(
    private val keys: AiReadiness,
) : Capability {
    override val id = ID
    override val icon = "reply"
    override val meta = CapabilityMeta(priority = 30, cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)
    override fun label(state: ObjectState) = labelNeedingKey("Отклик", keys.keySet())
    override fun accepts(state: ObjectState) = state.has(Feature.IS_JOB)
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    companion object { val ID = CapabilityId("job-reply") }
}

class JobReplyRealizer(
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
                reportStage("Пишу отклик")

                // Вакансия уже в запросе — снимок объявления модели не нужен (#1244).
                ActionResult.Success(
                    llm.run(textStandIn(input), JOB_REPLY_PROMPT + about + "\n\nВакансия:\n" + vacancy),
                )
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось написать отклик", recoverable = true) }
        }
    }

    private companion object {
        const val MAX_CHARS = 12_000
    }
}
