package com.point.executors

import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.META_CLOUD_ATOMS_REF
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.Realizer
import com.point.core.flow.findOnPage
import com.point.core.flow.foundOnPageLabel
import com.point.core.flow.isSearchable
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class FindCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "find"

    override val meta = CapabilityMeta(priority = 40, latency = Latency.INSTANT)

    override fun label(state: ObjectState) = "Найти в документе"

    override fun accepts(state: ObjectState) = Feature.HAS_WORD_LAYER in state.features

    override fun produces(state: ObjectState) = state

    override fun intents(state: ObjectState) = setOf(Intent.UNDERSTAND)

    override fun missing(state: ObjectState): String? =
        if (state.kind == ObjectKind.PDF) "разложите на страницы" else null

    companion object { val ID = CapabilityId("find") }
}

class FindRealizer @Inject constructor() : Realizer {
    override val capabilityId = FindCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            val query = amendment.orEmpty()

            if (!isSearchable(query)) return@withContext ActionResult.NeedsInput("Что найти в документе?")
            val layer = atomLayer(input)
                ?: return@withContext ActionResult.Failure(
                    "Страница ещё не прочитана — искать не в чем",
                    recoverable = false,
                )
            val found = layer.findOnPage(query)
            ActionResult.Done(foundOnPageLabel(found.size))
        }

    private fun atomLayer(input: PointObject): AtomLayer? =
        (input.metadata[META_OCR_ATOMS_REF] ?: input.metadata[META_CLOUD_ATOMS_REF])?.let { ref ->
            runCatching { AtomCodec.decode(File(ref).readText()) }.getOrNull()
        }
}
