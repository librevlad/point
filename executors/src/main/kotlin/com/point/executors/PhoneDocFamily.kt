package com.point.executors

import com.point.core.flow.AiReadiness
import com.point.core.flow.Capability
import com.point.core.flow.CurrentKnowledge
import com.point.core.flow.DocxWriter
import com.point.core.flow.GraphState
import com.point.core.flow.LlmClient
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.TextRecognizer
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.Intent
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Preview
import javax.inject.Inject

/**
 * Двери Hilt к действиям документов и текста, живущим в `:core:flow` (#1379, волна 1).
 *
 * Те же правила, что у `PhoneAiFamily`: ядро про `javax.inject` не знает, а члены
 * переписаны руками — `by`-делегирование в `@Inject`-классе роняет KSP всего модуля.
 */
class WordCapabilityOnPhone @Inject constructor() : Capability {
    private val inner = com.point.core.flow.WordCapability()
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

class WordRealizerOnPhone @Inject constructor(known: CurrentKnowledge, docx: DocxWriter, recognizer: TextRecognizer) : Realizer {
    private val inner = com.point.core.flow.WordRealizer(known, docx, recognizer)
    override val capabilityId get() = inner.capabilityId
    override val meta get() = inner.meta
    override fun isAvailable(): Boolean = inner.isAvailable()
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun unavailableReason(): String? = inner.unavailableReason()
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        inner.perform(input, amendment)
    override suspend fun preview(input: PointObject): Preview? = inner.preview(input)
}

class WordPlusCapabilityOnPhone @Inject constructor(keys: AiReadiness) : Capability {
    private val inner = com.point.core.flow.WordPlusCapability(keys)
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

class WordPlusRealizerOnPhone @Inject constructor(llm: LlmClient, known: CurrentKnowledge, docx: DocxWriter, recognizer: TextRecognizer) : Realizer {
    private val inner = com.point.core.flow.WordPlusRealizer(llm, known, docx, recognizer)
    override val capabilityId get() = inner.capabilityId
    override val meta get() = inner.meta
    override fun isAvailable(): Boolean = inner.isAvailable()
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun unavailableReason(): String? = inner.unavailableReason()
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        inner.perform(input, amendment)
    override suspend fun preview(input: PointObject): Preview? = inner.preview(input)
}

class FixErrorsCapabilityOnPhone @Inject constructor(keys: AiReadiness) : Capability {
    private val inner = com.point.core.flow.FixErrorsCapability(keys)
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

class FixErrorsStrongerCapabilityOnPhone @Inject constructor(keys: AiReadiness) : Capability {
    private val inner = com.point.core.flow.FixErrorsStrongerCapability(keys)
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

class FixErrorsRealizerOnPhone @Inject constructor(llm: LlmClient, known: CurrentKnowledge, store: ObjectStore) : Realizer {
    private val inner = com.point.core.flow.FixErrorsRealizer(llm, known, store)
    override val capabilityId get() = inner.capabilityId
    override val meta get() = inner.meta
    override fun isAvailable(): Boolean = inner.isAvailable()
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun unavailableReason(): String? = inner.unavailableReason()
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        inner.perform(input, amendment)
    override suspend fun preview(input: PointObject): Preview? = inner.preview(input)
}

class FixErrorsStrongerRealizerOnPhone @Inject constructor(llm: LlmClient) : Realizer {
    private val inner = com.point.core.flow.FixErrorsStrongerRealizer(llm)
    override val capabilityId get() = inner.capabilityId
    override val meta get() = inner.meta
    override fun isAvailable(): Boolean = inner.isAvailable()
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun unavailableReason(): String? = inner.unavailableReason()
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        inner.perform(input, amendment)
    override suspend fun preview(input: PointObject): Preview? = inner.preview(input)
}

class ShoppingListCapabilityOnPhone @Inject constructor(keys: AiReadiness) : Capability {
    private val inner = com.point.core.flow.ShoppingListCapability(keys)
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

class ShoppingListRealizerOnPhone @Inject constructor(llm: LlmClient) : Realizer {
    private val inner = com.point.core.flow.ShoppingListRealizer(llm)
    override val capabilityId get() = inner.capabilityId
    override val meta get() = inner.meta
    override fun isAvailable(): Boolean = inner.isAvailable()
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun unavailableReason(): String? = inner.unavailableReason()
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        inner.perform(input, amendment)
    override suspend fun preview(input: PointObject): Preview? = inner.preview(input)
}

class JobReplyCapabilityOnPhone @Inject constructor(keys: AiReadiness) : Capability {
    private val inner = com.point.core.flow.JobReplyCapability(keys)
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

class JobReplyRealizerOnPhone @Inject constructor(llm: LlmClient) : Realizer {
    private val inner = com.point.core.flow.JobReplyRealizer(llm)
    override val capabilityId get() = inner.capabilityId
    override val meta get() = inner.meta
    override fun isAvailable(): Boolean = inner.isAvailable()
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun unavailableReason(): String? = inner.unavailableReason()
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        inner.perform(input, amendment)
    override suspend fun preview(input: PointObject): Preview? = inner.preview(input)
}

// ---- чтение снимка: те же исполнители из ядра (#1377) ----

class DeviceOcrRealizerOnPhone @Inject constructor(
    store: ObjectStore,
    recognizer: TextRecognizer,
) : Realizer {
    private val inner = com.point.core.flow.DeviceOcrRealizer(store, recognizer)
    override val capabilityId get() = inner.capabilityId
    override val meta get() = inner.meta
    override fun isAvailable(): Boolean = inner.isAvailable()
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun unavailableReason(): String? = inner.unavailableReason()
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        inner.perform(input, amendment)
    override suspend fun preview(input: PointObject): Preview? = inner.preview(input)
}

class CloudOcrCapabilityOnPhone @Inject constructor() : Capability {
    private val inner = com.point.core.flow.CloudOcrCapability()
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

class ExternalEyeOcrRealizerOnPhone @Inject constructor(eye: com.point.core.flow.ExternalEye, store: ObjectStore) : Realizer {
    private val inner = com.point.core.flow.ExternalEyeOcrRealizer(eye, store)
    override val capabilityId get() = inner.capabilityId
    override val meta get() = inner.meta
    override fun isAvailable(): Boolean = inner.isAvailable()
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun unavailableReason(): String? = inner.unavailableReason()
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        inner.perform(input, amendment)
    override suspend fun preview(input: PointObject): Preview? = inner.preview(input)
}

class CloudOcrRealizerOnPhone @Inject constructor(llm: LlmClient, privacy: com.point.core.flow.CloudPrivacySettings) : Realizer {
    private val inner = com.point.core.flow.CloudOcrRealizer(llm, privacy)
    override val capabilityId get() = inner.capabilityId
    override val meta get() = inner.meta
    override fun isAvailable(): Boolean = inner.isAvailable()
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun unavailableReason(): String? = inner.unavailableReason()
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        inner.perform(input, amendment)
    override suspend fun preview(input: PointObject): Preview? = inner.preview(input)
}

class ExternalEyeCloudOcrRealizerOnPhone @Inject constructor(eye: com.point.core.flow.ExternalEye, store: ObjectStore) : Realizer {
    private val inner = com.point.core.flow.ExternalEyeCloudOcrRealizer(eye, store)
    override val capabilityId get() = inner.capabilityId
    override val meta get() = inner.meta
    override fun isAvailable(): Boolean = inner.isAvailable()
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun unavailableReason(): String? = inner.unavailableReason()
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        inner.perform(input, amendment)
    override suspend fun preview(input: PointObject): Preview? = inner.preview(input)
}

class CloudOcrDirectRealizerOnPhone @Inject constructor(llm: LlmClient, privacy: com.point.core.flow.CloudPrivacySettings, store: ObjectStore) : Realizer {
    private val inner = com.point.core.flow.CloudOcrDirectRealizer(llm, privacy, store)
    override val capabilityId get() = inner.capabilityId
    override val meta get() = inner.meta
    override fun isAvailable(): Boolean = inner.isAvailable()
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun unavailableReason(): String? = inner.unavailableReason()
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        inner.perform(input, amendment)
    override suspend fun preview(input: PointObject): Preview? = inner.preview(input)
}
