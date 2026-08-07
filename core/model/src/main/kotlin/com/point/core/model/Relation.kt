package com.point.core.model

data class Relation(
    val fromId: String,
    val type: RelationType,
    val toId: String,
)

@JvmInline
value class RelationType(val name: String) {

    override fun toString(): String = name

    companion object {

        val FOUND_IN = RelationType("found_in")

        val IDENTIFIES = RelationType("identifies")

        val ISSUED_BY = RelationType("issued_by")

        val BELONGS_TO = RelationType("belongs_to")

        val LOCATED_AT = RelationType("located_at")

        val SENDER = RelationType("sender")
        val RECEIVER = RelationType("receiver")
        val CARRIER = RelationType("carrier")

        val SHIPPED_BY = RelationType("shipped_by")

        val SIGNED_BY = RelationType("signed_by")
    }
}
