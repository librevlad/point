package com.point.executors

import com.point.core.flow.AiReadiness
import com.point.core.flow.Capability
import com.point.core.flow.CloudPrivacySettings
import com.point.core.flow.CurrentKnowledge
import com.point.core.flow.GraphState
import com.point.core.flow.LlmClient
import com.point.core.flow.ObjectStore
import com.point.core.flow.OnDeviceTranslator
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.flow.labelWithoutKeyNote
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.Intent
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Preview
import javax.inject.Inject

/**
 * «Понять», «Перевести» и «AI» живут в `:core:flow` — одно действие на телефон и компьютер
 * (#1379, решение владельца: «пк должен все уметь не хуже телефона»).
 *
 * Здесь — только двери Hilt, как у «В Excel» (#1369): ядро про `javax.inject` не знает.
 * Члены переписаны руками, а не `by`-делегатом: kotlin-делегирование в `@Inject`-классе
 * роняет KSP всего модуля россыпью «error.NonExistentClass» без указания на виновника.
 */
class UnderstandCapabilityOnPhone @Inject constructor(keys: AiReadiness) : Capability {
    private val inner = com.point.core.flow.UnderstandCapability(keys)
    override val id get() = inner.id
    override val icon get() = inner.icon
    override val meta get() = inner.meta
    override fun label(state: ObjectState): String = inner.label(state)
    override fun label(graph: GraphState): String = inner.label(graph)
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun accepts(graph: GraphState): Boolean = inner.accepts(graph)
    override fun produces(state: ObjectState): ObjectState? = inner.produces(state)
    override fun yields(state: ObjectState): ActionYield = inner.yields(state)
    override fun intents(state: ObjectState): Set<Intent> = inner.intents(state)
}

class UnderstandRealizerOnPhone @Inject constructor(llm: LlmClient) : Realizer {
    private val inner = com.point.core.flow.UnderstandRealizer(llm)
    override val capabilityId get() = inner.capabilityId
    override val meta get() = inner.meta
    override fun isAvailable(): Boolean = inner.isAvailable()
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun unavailableReason(): String? = inner.unavailableReason()
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        inner.perform(input, amendment)
    override suspend fun preview(input: PointObject): Preview? = inner.preview(input)
}

class TranslateCapabilityOnPhone @Inject constructor(
    private val keys: AiReadiness,
    private val privacy: CloudPrivacySettings,
) : Capability {
    private val inner = com.point.core.flow.TranslateCapability(keys)

    // Без ключа или в закрытом режиме переводит сам телефон (#433): действие не просит ключ
    // и не идёт через сеть — так о нём и говорится.
    private fun onDevice() = translatesOnDeviceHere(keys, privacy)
    override val id get() = inner.id
    override val icon get() = inner.icon
    override val meta get() = if (onDevice()) inner.meta.copy(network = false, auth = false) else inner.meta
    override fun label(state: ObjectState): String =
        if (onDevice()) labelWithoutKeyNote(inner.label(state)) else inner.label(state)
    override fun label(graph: GraphState): String =
        if (onDevice()) labelWithoutKeyNote(inner.label(graph)) else inner.label(graph)
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun accepts(graph: GraphState): Boolean = inner.accepts(graph)
    override fun produces(state: ObjectState): ObjectState? = inner.produces(state)
    override fun yields(state: ObjectState): ActionYield = inner.yields(state)
    override fun intents(state: ObjectState): Set<Intent> = inner.intents(state)
    override fun missing(state: ObjectState): String? = inner.missing(state)
}

class TranslateRealizerOnPhone @Inject constructor(
    llm: LlmClient,
    known: CurrentKnowledge,
    private val keys: AiReadiness,
    private val privacy: CloudPrivacySettings,
) : Realizer {
    private val inner = com.point.core.flow.TranslateRealizer(llm, known)
    override val capabilityId get() = inner.capabilityId
    override val meta get() = inner.meta
    override fun isAvailable(): Boolean = inner.isAvailable() && !translatesOnDeviceHere(keys, privacy)
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun unavailableReason(): String? = inner.unavailableReason()
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        inner.perform(input, amendment)
    override suspend fun preview(input: PointObject): Preview? = inner.preview(input)
}

/** Перевод самим телефоном — ML Kit за [OnDeviceTranslator] (#433). */
class OnDeviceTranslateRealizerOnPhone @Inject constructor(
    translator: OnDeviceTranslator,
    known: CurrentKnowledge,
    store: ObjectStore,
    keys: AiReadiness,
    privacy: CloudPrivacySettings,
) : Realizer {
    private val inner = com.point.core.flow.OnDeviceTranslateRealizer(translator, known, store) {
        translatesOnDeviceHere(keys, privacy)
    }
    override val capabilityId get() = inner.capabilityId
    override val meta get() = inner.meta
    override fun isAvailable(): Boolean = inner.isAvailable()
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        inner.perform(input, amendment)
}

private fun translatesOnDeviceHere(keys: AiReadiness, privacy: CloudPrivacySettings): Boolean =
    com.point.core.flow.translatesOnDevice(
        keySet = runCatching { keys.keySet() }.getOrDefault(false),
        level = runCatching { privacy.level() }.getOrDefault(com.point.core.flow.PrivacyLevel.DEFAULT),
    )

class AiCapabilityOnPhone @Inject constructor(keys: AiReadiness) : Capability {
    private val inner = com.point.core.flow.AiCapability(keys)
    override val id get() = inner.id
    override val icon get() = inner.icon
    override val meta get() = inner.meta
    override fun label(state: ObjectState): String = inner.label(state)
    override fun label(graph: GraphState): String = inner.label(graph)
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun accepts(graph: GraphState): Boolean = inner.accepts(graph)
    override fun produces(state: ObjectState): ObjectState? = inner.produces(state)
    override fun yields(state: ObjectState): ActionYield = inner.yields(state)
    override fun intents(state: ObjectState): Set<Intent> = inner.intents(state)
}

class AiRealizerOnPhone @Inject constructor(llm: LlmClient, resolver: dagger.Lazy<Resolver>) : Realizer {
    private val inner = com.point.core.flow.AiRealizer(llm, lazy { resolver.get() })
    override val capabilityId get() = inner.capabilityId
    override val meta get() = inner.meta
    override fun isAvailable(): Boolean = inner.isAvailable()
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun unavailableReason(): String? = inner.unavailableReason()
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        inner.perform(input, amendment)
    override suspend fun preview(input: PointObject): Preview? = inner.preview(input)
}
