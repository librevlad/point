package com.point.data

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.Findings
import com.point.core.flow.KIND_ADDRESS
import com.point.core.flow.KIND_DATE
import com.point.core.flow.KIND_EMAIL
import com.point.core.flow.KIND_PHONE
import com.point.core.flow.KIND_URL
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.model.Feature
import com.point.core.model.ObjectState
import com.point.core.model.PointObject

class MetadataEntityInvestigation @javax.inject.Inject constructor() : Capability {

    override val id = ID

    override val icon = ""

    override val meta = CapabilityMeta(
        investigation = true,
        latency = Latency.INSTANT,
        mayYield = FEATURE_BY_SUFFIX.values.toSet() + com.point.core.flow.SEMANTIC_TYPES.values +
            Feature.HAS_WORD_LAYER,
        mayYieldKinds = setOf(KIND_PHONE, KIND_EMAIL, KIND_URL, KIND_ADDRESS, KIND_DATE),
    )

    override fun label(state: ObjectState) = ""

    override fun accepts(state: ObjectState) = true

    override fun produces(state: ObjectState) = state

    companion object {

        val ID = com.point.core.model.CapabilityId("metadata-entities")
    }
}

class MetadataEntityInvestigationRealizer @javax.inject.Inject constructor() : Realizer {

    override val capabilityId = MetadataEntityInvestigation.ID

    override val meta = com.point.core.flow.RealizerMeta(actor = "file-metadata")

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        com.point.core.flow.investigated { findings(input) }

    private suspend fun findings(obj: PointObject): Findings {

        val (objects, relations) = entityObjects(obj, obj.metadata, creator = CREATOR)
        return Findings(
            features = FEATURE_BY_SUFFIX.mapNotNullTo(mutableSetOf()) { (suffix, feature) ->
                feature.takeIf { !obj.metadata[META_ENTITY_PREFIX + suffix].isNullOrBlank() }
            } + setOfNotNull(

                com.point.core.flow.SEMANTIC_TYPES[obj.metadata[com.point.core.flow.META_SEMANTIC_TYPE]],

                Feature.HAS_WORD_LAYER.takeIf {
                    !obj.metadata[com.point.core.flow.META_OCR_ATOMS_REF].isNullOrBlank() ||
                        !obj.metadata[com.point.core.flow.META_CLOUD_ATOMS_REF].isNullOrBlank()
                },
            ),
            objects = objects,
            relations = relations,
        )
    }

    private companion object {
        const val CREATOR = "metadata-entity-enricher"
    }
}

internal val FEATURE_BY_SUFFIX = mapOf(
    "phone" to Feature.HAS_PHONE,
    "email" to Feature.HAS_EMAIL,
    "url" to Feature.HAS_URL,
    "address" to Feature.HAS_ADDRESS,
    "date" to Feature.HAS_DATE,
    "card" to Feature.HAS_CARD,
)

