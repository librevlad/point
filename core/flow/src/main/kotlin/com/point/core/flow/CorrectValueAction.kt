package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Findings
import com.point.core.model.Intent
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance

/**
 * Явное исправление или подтверждение значения человеком — единственный путь, которым
 * знание получает `Provenance.HUMAN` (ADR-0001 §8).
 *
 * Ни Focus, ни crop, ни навигация, ни сам запуск действия HUMAN не ставят: провенанс
 * появляется только из введённого или подтверждённого здесь значения.
 */
class CorrectValueCapability : Capability {

    override val id = ID

    override val icon = "text"

    override val meta = CapabilityMeta(priority = 82, localOnly = true)

    override fun label(state: ObjectState) = "Исправить значение"

    override fun accepts(state: ObjectState) = state.kind in EXTRACTED_KINDS

    override fun produces(state: ObjectState) = state

    override fun intents(state: ObjectState) = setOf(Intent.UNDERSTAND)

    companion object {

        val ID = CapabilityId("correct-value")
    }
}

class CorrectValueRealizer : Realizer {

    override val capabilityId = CorrectValueCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        val key = correctableKey(input.metadata)
            ?: return ActionResult.Failure("Здесь нечего исправлять", recoverable = true)
        val current = input.metadata[key].orEmpty()

        if (amendment == null) {
            return ActionResult.NeedsInput(
                "Как правильно?",
                suggestions = listOfNotNull(current.takeIf(String::isNotBlank)),
            )
        }
        val value = amendment.trim()
        if (value.isEmpty()) {
            return ActionResult.Failure("Пустое значение — исправление отменено", recoverable = true)
        }

        // Знание, а не новый объект: правка уходит исходом «выполнено» (ADR-0001 §18)
        // и сливается единым mergeKnowledge — там человеческое слово разрешает спор.
        val findings = Findings(
            metadata = mapOf(
                key to value,
                key + META_SOURCE_SUFFIX to Provenance.HUMAN.wire,
            ),
        )
        val confirmed = normConsensus(value) == normConsensus(current)
        return ActionResult.Done(
            if (confirmed) "Подтверждено вами" else "Исправлено: $value",
            findings,
        )
    }
}

/**
 * Исправлять есть смысл только смысловой факт — не локализацию, не состояние исследования
 * и не аннотации.
 */
internal fun correctableKey(metadata: Map<String, String>): String? =
    metadata.keys.firstOrNull {
        (it.startsWith(META_ENTITY_PREFIX) || it.startsWith(META_GRAPH_ROLE_PREFIX)) &&
            !isAnnotationKey(it) && !isStateKey(it)
    }
