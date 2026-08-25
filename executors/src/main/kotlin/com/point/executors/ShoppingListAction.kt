package com.point.executors

import com.point.core.flow.AiReadiness
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.Realizer
import com.point.core.flow.reportStage
import com.point.core.flow.labelNeedingKey
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val SHOPPING_LIST_PROMPT =
    "Составь список покупок по этому рецепту. Отвечай ТОЛЬКО пунктами списка Markdown — " +
        "по одному ингредиенту с количеством на строку, без пояснений и заголовков.\n\nРецепт:\n"

class ShoppingListCapability @Inject constructor(
    private val keys: AiReadiness,
) : Capability {
    override val id = ID
    override val icon = "cart"
    override val meta = CapabilityMeta(priority = 30, cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)
    override fun label(state: ObjectState) = labelNeedingKey("Список покупок", keys.keySet())
    override fun accepts(state: ObjectState) = state.has(Feature.IS_RECIPE)
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    companion object { val ID = CapabilityId("shopping-list") }
}

class ShoppingListRealizer @Inject constructor(
    private val llm: LlmClient,
) : Realizer {
    override val capabilityId = ShoppingListCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = entitySourceText(input).take(MAX_CHARS)
                if (text.isBlank()) return@withContext ActionResult.Failure("Нет текста рецепта", recoverable = true)
                reportStage("Собираю список")

                // Рецепт уже в запросе — снимок страницы модели не нужен (#1244).
                ActionResult.Success(llm.run(textStandIn(input), SHOPPING_LIST_PROMPT + text))
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось составить список", recoverable = true) }
        }

    private companion object {
        const val MAX_CHARS = 12_000
    }
}
