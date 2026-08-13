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

        /**
         * Исходник содержит этот объект (#946).
         *
         * Архив содержит файлы, документ содержит страницы, набор содержит вложения: объект
         * был внутри и достали его целиком, а не сделали заново.
         */
        val CONTAINS = RelationType("contains")

        /**
         * Объект получен из исходника (#946).
         *
         * Запись получена из текста, перевод — из документа, снимок страницы — из PDF.
         * Решение владельца 13.08.2026: связи разные и в графе не сливаются — «архив
         * содержит файлы» и «запись получена из текста» это не одно и то же.
         */
        val DERIVED_FROM = RelationType("derived_from")

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
