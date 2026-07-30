package com.point.core.model

/**
 * A typed, directed edge between two [PointObject]s (#222).
 *
 * Relations are where **roles** live. This is what keeps [ObjectKind] from exploding: the
 * consignee of a waybill is not a `CMRConsignee` kind, it is an `Organization` on the far
 * end of a `receiver` edge. Swap the document type and the kinds stay the same — only the
 * edges differ.
 *
 * Built **in code**, never by a model: an extractor or a classifier answers with references
 * to elements it can see, and the pipeline turns those references into objects and edges.
 */
data class Relation(
    val fromId: String,
    val type: RelationType,
    val toId: String,
)

/**
 * Open by design, same reasoning as [ObjectKind]: a new document domain brings new roles,
 * not new architecture. [name] is the wire format — persisted, so keep it stable.
 */
@JvmInline
value class RelationType(val name: String) {

    override fun toString(): String = name

    companion object {
        /** The object was extracted from / is located inside that one (page, paragraph, cell). */
        val FOUND_IN = RelationType("found_in")

        /** An identifier identifies that object. `Identifier -> identifies -> Shipment`. */
        val IDENTIFIES = RelationType("identifies")

        /** Who issued the identifier, the stamp, the document. */
        val ISSUED_BY = RelationType("issued_by")

        /** A signature or a possession belongs to a person. */
        val BELONGS_TO = RelationType("belongs_to")

        /** An address is where that organization or place sits. */
        val LOCATED_AT = RelationType("located_at")

        /** Party roles on a transport document. */
        val SENDER = RelationType("sender")
        val RECEIVER = RelationType("receiver")
        val CARRIER = RelationType("carrier")

        /** Who shipped the goods. */
        val SHIPPED_BY = RelationType("shipped_by")

        /** The document was signed by that person. */
        val SIGNED_BY = RelationType("signed_by")
    }
}
