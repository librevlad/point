package com.point.executors

import com.point.core.flow.CLASSIFIER_ROLES
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.META_GRAPH_ROLE_PREFIX
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.Realizer
import com.point.core.flow.classifierPrompt
import com.point.core.flow.layoutOf
import com.point.core.flow.parseClassification
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * «Кто есть кто» — the model as a classifier over layout (#222, шаг 6).
 *
 * Answers the one question rules cannot: on a waybill, which line is the sender and which the
 * carrier. Addresses, dates and numbers are already found on device, for free — asking a paid
 * model to repeat that would be slower, costlier and no better.
 *
 * Never automatic. Network, paid, explicit tap, behind the one-time cloud consent (#10) — the
 * first screen stays untouched.
 */
class ClassifyCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "ai"
    override val meta = CapabilityMeta(
        priority = 33, cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true,
    )
    override fun label(state: ObjectState) = "Кто есть кто"
    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.TEXT || state.has(Feature.HAS_TEXT)
    override fun produces(state: ObjectState) = state
    override fun intents(state: ObjectState) = setOf(Intent.UNDERSTAND)

    companion object { val ID = CapabilityId("classify") }
}

/**
 * Sends the layout, gets back element ids, and **builds the graph in code**.
 *
 * The model never names an object, a kind or a relation. It points at `P7`; the role table says
 * what a `sender` is; this realizer copies `P7`'s own text as the value. A returned id that is
 * not in the layout never reaches any of that — [parseClassification] drops it silently.
 *
 * Findings land in `graph.role.*` metadata rather than in the returned object's shape, so they
 * ride the mechanism that already works: the journal keeps them through process death, and
 * `GraphMetadataEnricher` turns them back into objects and relations with stable ids.
 */
class ClassifyRealizer @Inject constructor(
    private val llm: LlmClient,
) : Realizer {
    override val capabilityId = ClassifyCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val elements = layoutOf(entitySourceText(input))
                if (elements.isEmpty()) {
                    return@withContext ActionResult.Failure("Нет текста для разбора", recoverable = true)
                }
                val answer = llm.run(textOnly(input), classifierPrompt(elements))
                val found = parseClassification(File(answer.uri.value).readText(), elements)
                if (found.isEmpty()) {
                    return@withContext ActionResult.Failure("Стороны в документе не найдены", recoverable = true)
                }
                ActionResult.Success(
                    ResultObject(
                        input.state.kind, input.mime, input.uri,
                        metadata = input.metadata +
                            found.associate { META_GRAPH_ROLE_PREFIX + it.role.key to it.element.text } +
                            ("op" to "classify"),
                    ),
                )
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось разобрать документ", recoverable = true) }
        }

    /** Only text ever leaves the device here, and only the layout of it — the roles are a
     *  reading question, and a photo would cost vision tokens for no extra answer. */
    private fun textOnly(input: PointObject) =
        if (input.state.kind == ObjectKind.TEXT) input
        else input.copy(mime = "text/plain", metadata = input.metadata - META_OCR_TEXT_REF)
}

/** Roles the classifier can fill, for anyone who needs the list (tests, future UI). */
internal val classifierRoleKeys: List<String> = CLASSIFIER_ROLES.map { it.key }
